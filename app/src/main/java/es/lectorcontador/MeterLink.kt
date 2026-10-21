package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Ejecuta una lectura completa IEC 62056-21 modo C a través de un puerto USB-serie. */
class MeterLink(
    private val port: UsbSerialPort,
    private val onStatus: (String) -> Unit = {},
) {
    suspend fun readOut(): MeterReading = withContext(Dispatchers.IO) {
        setBaud(Iec62056.INITIAL_BAUD)
        runCatching { port.purgeHwBuffers(true, true) }

        onStatus("Enviando petición al contador…")
        port.write(Iec62056.REQUEST, WRITE_TIMEOUT_MS)

        val identBytes = readUntil(IDENT_TIMEOUT_MS) {
            Iec62056.findIdentification(asText(it)) != null
        } ?: throw IOException(
            "El contador no responde. Comprueba que la sonda está bien alineada con el puerto óptico."
        )
        val ident = Iec62056.findIdentification(asText(identBytes))!!
        val newBaud = Iec62056.baudFor(ident.baudChar)!!
        onStatus("Contador ${ident.manufacturer} ${ident.text}".trim())

        // El estándar pide esperar un poco antes de contestar con el ACK.
        delay(300)
        port.write(Iec62056.ackReadout(ident.baudChar), WRITE_TIMEOUT_MS)
        // El ACK (6 bytes a 300 baud) tarda ~200 ms en salir antes de cambiar la velocidad.
        delay(350)
        setBaud(newBaud)

        onStatus("Leyendo datos a $newBaud baudios…")
        val frameBytes = readUntil(DATA_TIMEOUT_MS) { Iec62056.extractFrame(it) != null }
            ?: throw IOException("Tiempo agotado esperando los datos del contador.")

        // Cierre de sesión (no es crítico si falla)
        runCatching { port.write(Iec62056.breakFrame(), WRITE_TIMEOUT_MS) }

        when (val frame = Iec62056.extractFrame(frameBytes)) {
            is FrameResult.Ok -> {
                val values = Iec62056.parseDataBlock(frame.payload)
                if (values.isEmpty()) throw IOException("El contador respondió, pero sin valores reconocibles.")
                MeterReading(
                    timestamp = System.currentTimeMillis(),
                    identification = "${ident.manufacturer} ${ident.text}".trim(),
                    values = values,
                    raw = frame.payload,
                )
            }
            is FrameResult.BadChecksum ->
                throw IOException("Error de checksum (BCC) en la trama. Reintenta la lectura.")
            null -> throw IOException("Trama incompleta.")
        }
    }

    private fun setBaud(baud: Int) {
        port.setParameters(baud, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
    }

    private suspend fun readUntil(timeoutMs: Long, done: (ByteArray) -> Boolean): ByteArray? {
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val n = port.read(buf, READ_POLL_MS)
            if (n > 0) {
                acc.write(buf, 0, n)
                val current = acc.toByteArray()
                if (done(current)) return current
            }
        }
        return null
    }

    private fun asText(bytes: ByteArray): String =
        String(ByteArray(bytes.size) { (bytes[it].toInt() and 0x7F).toByte() }, Charsets.US_ASCII)

    private companion object {
        const val WRITE_TIMEOUT_MS = 1000
        const val READ_POLL_MS = 200
        const val IDENT_TIMEOUT_MS = 4000L
        const val DATA_TIMEOUT_MS = 20000L
    }
}
