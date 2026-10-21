package es.lectorcontador

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Velocidad y formato con los que respondió el contador. */
data class LineSettings(val baud: Int, val evenParity: Boolean) {
    val text: String get() = "$baud baudios ${if (evenParity) "8E1" else "8N1"}"
}

/**
 * Diagnóstico de IEC 870-5-102. Solo envía la petición de "estado del enlace" (de solo lectura):
 * no inicia sesión ni escribe nada en el contador.
 */
class Meter102Diagnostics(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val log: (String) -> Unit,
) {
    private val ch = Ft12Channel(port)

    private fun statusRequest(address: Int) =
        Ft12.fixed(Ft12.masterControl(Ft12.FN_LINK_STATUS, false, false), address)

    /** Prueba cada velocidad y formato con la dirección de enlace de Ajustes. */
    suspend fun probe(): LineSettings? = withContext(Dispatchers.IO) {
        val dev = port.driver.device
        log("Sonda USB: VID 0x%04X, PID 0x%04X".format(dev.vendorId, dev.productId))
        log("Petición de estado del enlace IEC 870-5-102 a la dirección ${cfg.linkAddress}, con cada velocidad y formato.")
        log("")

        var found: LineSettings? = null
        outer@ for (even in listOf(true, false)) {
            for (baud in listOf(9600, 4800, 2400, 19200, 1200)) {
                coroutineContext.ensureActive()
                ch.configure(baud, even)
                val request = statusRequest(cfg.linkAddress)
                ch.send(request)
                val frame = ch.waitFrame(1200)
                val head = LineSettings(baud, even).text + ":"
                val raw = ch.lastRaw
                when {
                    frame != null -> {
                        log("$head RESPUESTA IEC 870-5-102: $frame")
                        found = LineSettings(baud, even)
                        break@outer
                    }
                    raw.isEmpty() -> log("$head sin respuesta")
                    raw.contentEquals(request) -> log("$head solo eco de la petición")
                    else -> {
                        log("$head llegan ${raw.size} bytes que no son una trama FT1.2 válida")
                        log("   ASCII: ${ByteFormat.ascii(raw)}")
                        log("   HEX:   ${ByteFormat.hex(raw)}")
                    }
                }
            }
        }

        log("")
        val f = found
        if (f != null) {
            log("RESULTADO: el contador habla IEC 870-5-102 a ${f.text}. Se ha guardado en Ajustes.")
        } else {
            log(
                "RESULTADO: sin respuesta válida con la dirección de enlace ${cfg.linkAddress}. " +
                    "Si no la conoces, usa «Buscar dirección de enlace». Si llegaron bytes que no son una trama " +
                    "FT1.2, comparte este informe."
            )
        }
        f
    }

    /**
     * Prueba las direcciones de enlace 0..255 y 65535 con la velocidad y el formato de Ajustes.
     * Es de solo lectura (estado del enlace). Devuelve la dirección con la que contestó el contador.
     */
    suspend fun scanAddresses(): Int? = withContext(Dispatchers.IO) {
        log("Buscando la dirección de enlace a ${LineSettings(cfg.baud, cfg.evenParity).text} (unos 70 segundos)…")
        ch.configure(cfg.baud, cfg.evenParity)
        val addresses = (0..255).toList() + 65535
        for ((n, address) in addresses.withIndex()) {
            coroutineContext.ensureActive()
            ch.send(statusRequest(address))
            val frame = ch.waitFrame(250)
            if (frame != null) {
                // El contador responde con su propia dirección: es la que hay que usar.
                log("Respuesta del contador: dirección de enlace ${frame.address}  ($frame)")
                return@withContext frame.address
            }
            if (n % 32 == 31) log("Probadas las direcciones hasta la $address…")
        }
        log("Sin respuesta en ninguna dirección. Comprueba la velocidad y el formato en Ajustes y la alineación de la sonda.")
        null
    }
}
