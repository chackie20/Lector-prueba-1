```kotlin
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

        val auth = if (cfg.dlmsPassword.isBlank()) {
            Authentication.NONE
        } else {
            Authentication.LOW
        }

        dlms = GXDLMSClient(
            true,
            cfg.dlmsClientAddress,
            cfg.dlmsServerAddress,
            auth,
            cfg.dlmsPassword,
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

        val aarq = dlms.aarqRequest()
        for (request in aarq) {
            readDataBlock(request, reply)
        }
        dlms.parseAareResponse(reply.data)

        if (auth != Authentication.NONE && auth != Authentication.LOW) {
            reply.clear()
            val associationRequest: ByteArray =
                dlms.applicationAssociationRequest()

            readDataBlock(associationRequest, reply)
            dlms.parseApplicationAssociationResponse(reply.data)
        }

        onStatus("ZIV: leyendo energía y valores instantáneos...")

        val values = mutableListOf<ObisValue>()
        val requested = linkedSetOf<String>()

        requested.add("1.18.0")
        for (i in 1..PERIODS) {
            requested.add("1.18.$i")
        }

        requested.add("1.28.0")
        for (i in 1..PERIODS) {
            requested.add("1.28.$i")
        }

        for (obis in requested) {
            try {
                val valueString = readObisString(obis)
                if (valueString != null) {
                    values.add(
                        ObisValue(
                            code = obis,
                            value = valueString,
                            unit = ""
                        )
                    )
                }
            } catch (e: Exception) {
                onStatus("ZIV: no se pudo leer $obis")
            }
        }

        val rawSummary = values.joinToString("\n") {
            "${it.code}: ${it.value}"
        }

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
        val requests: Array<ByteArray> = dlms.read(item, 2)

        for (request in requests) {
            reply.clear()
            readDataBlock(request, reply)
        }

        val value: Any? = dlms.updateValue(item, 2, reply.value)
        return value?.toString()
    }

    private fun readDataBlock(
        request: ByteArray,
        reply: GXReplyData
    ) {
        port.write(request, 1000)

        val buffer = ByteArray(1024)
        val len = port.read(buffer, 2000)

        if (len <= 0) {
            throw IllegalStateException(
                "El contador ZIV no ha respondido a la petición DLMS"
            )
        }

        reply.data.set(buffer, 0, len)
    }

    private fun enterModeE() {
        onStatus("ZIV: saludo inicial modo E...")
    }

    companion object {
        private const val PERIODS = 6
    }
}
```
