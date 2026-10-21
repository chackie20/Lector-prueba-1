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

        val aarq = dlms.aarqRequest()
        for (requestBlock in aarq) {
            readDataBlock(requestBlock, reply)
        }

        dlms.parseAareResponse(reply.data)

        if (dlms.authentication.ordinal > Authentication.LOW.ordinal) {
            reply.clear()

            val assocRequests = dlms.applicationAssociationRequest()
            for (requestBlock in assocRequests) {
                readDataBlock(requestBlock, reply)
            }

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
                    values
