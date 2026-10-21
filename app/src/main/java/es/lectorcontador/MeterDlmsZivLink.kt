package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import gurux.dlms.GXByteBuffer
import gurux.dlms.GXDLMSClient
import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.enums.ObjectType
import gurux.dlms.objects.GXDLMSRegister
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Lectura DLMS/COSEM del puerto óptico ZIV 5CTB-3AB-M.
 *
 * El acceso óptico de estos ZIV usa IEC 62056-21 para entrar en modo E y
 * después HDLC/DLMS. La sesión es de solo lectura y no escribe parámetros.
 */
class MeterDlmsZivLink(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val onStatus: (String) -> Unit = {},
) {
    private lateinit var dlms: GXDLMSClient
    private var rx = ByteArray(0)

    suspend fun readOut(): MeterReading = withContext(Dispatchers.IO) {
        enterModeE()

        val auth = if (cfg.dlmsPassword.isBlank()) Authentication.NONE else Authentication.LOW
        dlms = GXDLMSClient(
            true,
            cfg.dlmsClientAddress,
            cfg.dlmsServerAddress,
            auth,
            cfg.dlmsPassword.ifBlank { null },
            InterfaceType.HDLC_WITH_MODE_E,
        )

        onStatus("ZIV: estableciendo sesión DLMS…")
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
    readDataBlock(p, reply.data)
}
for (p: ByteArray in assocRequests) {
    readDataBlock(p, reply)
}
            dlms.parseApplicationAssociationResponse(reply.data)
        }

        onStatus("ZIV: leyendo energía y valores instantáneos…")
        val values = mutableListOf<ObisValue>()
        val requested = linkedSetOf<String>()
        requested += "1.18.0"
        for (i in 1..PERIODS) requested += "1.18.$i"
        requested += "1.28.0"
        for (i in 1..PERIODS) requested += "1.28.$i"
        requested += listOf("0.17.0", "0.24.0", "0.327.0", "0.317.0", "0.0.0", "0.0.96.1.0.255")

        for (ln in requested) {
            coroutineContext.ensureActive()
            try {
                val register = GXDLMSRegister(ln)
                if (ln.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
                    readObject(register, 3, reply)
                    readObject(register, 2, reply)
                    val value = register.value
                    if (value != null) values += ObisValue(ln, value.toString(), unitFor(ln))
                } else {
                    // Identificadores se modelan como DATA; algunos ZIV exponen el serial aquí.
                    val data = readDataObject(ln, reply)
                    if (data != null) values += ObisValue(ln, data.toString(), null)
                }
            } catch (_: Exception) {
                // Un registro opcional o protegido no debe impedir la lectura del resto.
            }
        }

        if (values.none { it.code == "1.18.0" || it.code == "1.8.0" }) {
            throw IOException("El ZIV respondió a DLMS, pero no permitió leer 1.18.0. Revisa las direcciones cliente/servidor o la clave.")
        }

        runCatching {
            reply.clear()
            readDataBlock(dlms.disconnectRequest(), reply)
        }

        MeterReading(
            timestamp = System.currentTimeMillis(),
            identification = "ZIV 5CTB-3AB-M",
            values = values,
            raw = values.joinToString("\n") { "${it.code}(${it.value}${it.unit?.let { u -> "*$u" } ?: ""})" },
        )
    }

    private suspend fun enterModeE() {
        onStatus("ZIV: iniciando puerto óptico…")
        port.setParameters(300, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
        runCatching { port.purgeHwBuffers(true, true) }
        port.write(Iec62056.REQUEST, 1000)
        val line = readLine(5000) ?: throw IOException("El ZIV no responde a /?! por el puerto óptico.")
        val ident = Iec62056.findIdentification(String(line, Charsets.US_ASCII))
            ?: throw IOException("El contador no ha enviado una identificación IEC 62056 válida.")
        // En este ZIV el saludo siempre se inicia a 300 7E1 y, tras el ACK,
        // la sesión de preguntas/respuestas se hace a 9600 8N1.
        // No usamos la velocidad anunciada por el identificador para evitar
        // que una identificación anómala nos cambie la velocidad de trabajo.
        val dataBaud = 9600
        onStatus("ZIV ${ident.text.ifBlank { "5CTB" }} · cambiando a $dataBaud baudios…")
        delay(250)
        port.write(Iec62056.ackModeE('5'), 1000)
        delay(450)
        port.setParameters(dataBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        rx = ByteArray(0)
    }

    private suspend fun readLine(timeoutMs: Long): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buf = ByteArray(256)
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val n = port.read(buf, 250)
            if (n > 0) {
                rx += buf.copyOf(n)
                if (rx.contains(0x0A.toByte())) {
                    val out = rx
                    rx = ByteArray(0)
                    return out
                }
            }
        }
        return null
    }

    private suspend fun readFrame(timeoutMs: Long = 5000): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buf = ByteArray(512)
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val n = port.read(buf, 250)
            if (n > 0) {
                rx += buf.copyOf(n)
  // Acceso directo por índice [0] para evitar el error 'Unresolved reference first'
// e indexOf con 1 solo parámetro
val startByte: Byte = 0x7E.toByte()
val index = rx.indexOf(startByte)
if (index >= 0 && rx.size > index) {
    val firstElement = rx[0]
    // Tu lógica de procesado usando rx[0] o rx[index]
}
                        return out
                    }
                }
            }
        }
        throw IOException("Tiempo agotado esperando la respuesta DLMS del ZIV.")
    }

    private suspend fun readDataBlock(data: Array<ByteArray>, reply: GXReplyData) {
        for (packet in data) {
            reply.clear()
            readDataBlock(packet, reply)
        }
    }

    private suspend fun readDataBlock(data: ByteArray, reply: GXReplyData) {
        if (data.isEmpty()) return
        var request = data
        do {
            port.write(request, 1500)
            val frame = readFrame()
            val buffer = GXByteBuffer(frame)
            val notify = GXReplyData()
            dlms.getData(buffer, reply, notify)
            if (reply.isMoreData) {
                request = dlms.receiverReady(reply.moreData)
            } else {
                break
            }
        } while (true)
    }

    private suspend fun readObject(obj: GXDLMSRegister, index: Int, reply: GXReplyData) {
        reply.clear()
        val data = dlms.read(obj, index)
        if (data.isEmpty()) return
        readDataBlock(data[0], reply)
        if (reply.error != 0) throw IOException("DLMS error ${reply.error} al leer ${obj.logicalName}[$index].")
        dlms.updateValue(obj, index, reply.value)
    }

    private suspend fun readDataObject(ln: String, reply: GXReplyData): Any? {
        val obj = gurux.dlms.objects.GXDLMSData(ln)
        reply.clear()
        val data = dlms.read(obj, 2)
        if (data.isEmpty()) return null
        readDataBlock(data[0], reply)
        if (reply.error != 0) return null
        return dlms.updateValue(obj, 2, reply.value)
    }

    private fun unitFor(ln: String): String? = when {
        ln == "0.17.0" || ln == "0.24.0" -> "kW"
        ln == "0.327.0" -> "V"
        ln == "0.317.0" -> "A"
        ln.startsWith("1.18.") || ln.startsWith("1.28.") -> "kWh"
        else -> null
    }
}
