package es.lectorcontador

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import gurux.dlms.GXDLMSClient
import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.objects.GXDLMSData
import com.hoho.android.usbserial.driver.UsbSerialPort

class MeterDlmsZivLink(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val onStatus: (String) -> Unit = {}
) {
    private lateinit var dlms: GXDLMSClient

    suspend fun readOut(): MeterReading = withContext(Dispatchers.IO) {
        enterModeE()

        val auth = if (cfg.dlmsPassword.isBlank()) Authentication.NONE else Authentication.LOW
        dlms = GXDLMSClient(
            true,
            cfg.dlmsClientAddress,
            cfg.dlmsServerAddress,
            auth,
            cfg.dlmsPassword.ifBlank { null },
            InterfaceType.HDLC_WITH_MODE_E
        )

        onStatus("ZIV: estableciendo sesión DLMS...")
        val reply = GXReplyData()
        val snrm = dlms.snrmRequest()
        if (snrm != null && snrm.isNotEmpty()) {
            readDataBlock(snrm, reply)
            dlms.parseUAResponse(reply.data)
        }

        reply.clear()
        readDataBlock(dlms.aarqRequest(), reply)
        dlms.parseAareResponse(reply.data)

        if (dlms.authentication.ordinal > Authentication.LOW.ordinal) {
            reply.clear()
            val assocRequests = dlms.applicationAssociationRequest()
            for (p in assocRequests) {
                readDataBlock(p, reply)
            }
            dlms.parseApplicationAssociationResponse(reply.data)
        }

        onStatus("ZIV: leyendo energía y valores instantáneos...")
        val values = mutableListOf<ObisValue>()
        val requested = linkedSetOf<String>()
        requested += "1.18.0"
        for (i in 1..PERIODS) requested += "1.18.$i"
        requested += "1.28.0"
        for (i in 1..PERIODS) requested += "1.28.$i"

        for (obis in requested) {
            try {
                val valStr = readObisString(obis)
                if (valStr != null) {
                    values += ObisValue(code = obis, value = valStr, unit = "")
                }
            } catch (e: Exception) {
                // Se omiten lecturas OBIS que el contador no responda
            }
        }

        val rawSummary = values.joinToString("\n") { "${it.code}: ${it.value}" }

        MeterReading(
            timestamp = System.currentTimeMillis(),
            identification = "ZIV 5CTB",
            raw = rawSummary,
            values = values
        )
    }

    private fun readObisString(obis: String): String? {
        val reply = GXReplyData()
        val item = GXDLMSData(obis)
        val readReq = dlms.read(item, 2)
        if (readReq.isNotEmpty()) {
            readDataBlock(readReq[0], reply)
            return dlms.updateValue(item, 2, reply.value)?.toString()
        }
        return null
    }

    private fun readDataBlock(request: ByteArray, reply: GXReplyData) {
        port.write(request, 1000)
        val buffer = ByteArray(1024)
        val len = port.read(buffer, 2000)
        if (len > 0) {
            reply.data.set(buffer, 0, len)
        }
    }

    private fun enterModeE() {
        onStatus("ZIV: saludo inicial modo E...")
    }

    companion object {
        private const val PERIODS = 6
    }
}
