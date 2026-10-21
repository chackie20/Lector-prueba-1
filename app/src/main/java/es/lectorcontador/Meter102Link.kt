package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Envía y recibe tramas FT1.2 por el puerto serie, ignorando el eco de lo enviado. */
class Ft12Channel(private val port: UsbSerialPort) {
    private var buffer = ByteArray(0)
    private val queue = ArrayDeque<Ft12Frame>()

    /** Bytes recibidos durante la última espera, válidos o no (para diagnóstico). */
    var lastRaw: ByteArray = ByteArray(0)
        private set

    fun configure(baud: Int, evenParity: Boolean) {
        port.setParameters(
            baud, 8, UsbSerialPort.STOPBITS_1,
            if (evenParity) UsbSerialPort.PARITY_EVEN else UsbSerialPort.PARITY_NONE,
        )
        purge()
    }

    fun purge() {
        buffer = ByteArray(0)
        queue.clear()
        lastRaw = ByteArray(0)
        runCatching { port.purgeHwBuffers(true, true) }
    }

    fun send(bytes: ByteArray) {
        port.write(bytes, 1000)
    }

    fun pushFront(frame: Ft12Frame) {
        queue.addFirst(frame)
    }

    /** Espera una trama del contador. Con [timeoutMs] = 0 solo devuelve las que ya estén recibidas. */
    suspend fun waitFrame(timeoutMs: Long): Ft12Frame? {
        lastRaw = ByteArray(0)
        queue.removeFirstOrNull()?.let { return it }
        val tmp = ByteArray(512)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val n = port.read(tmp, 100)
            if (n > 0) {
                val chunk = tmp.copyOf(n)
                lastRaw += chunk
                val ex = Ft12.extract(buffer + chunk)
                buffer = ex.rest
                ex.frames.filter { it.fromSlave }.forEach { queue.addLast(it) }
                queue.removeFirstOrNull()?.let { return it }
            }
        }
        return null
    }
}

/**
 * Lectura de un contador por IEC 870-5-102 (perfil español):
 *  1. petición de estado del enlace y reposición del enlace remoto,
 *  2. inicio de sesión con la clave de acceso (C_AC_NA_2),
 *  3. lectura de totales integrados absolutos (C_CI_NT_2) y sondeo de la respuesta,
 *  4. fin de sesión (C_FS_NA_2).
 */
class Meter102Link(
    port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val onStatus: (String) -> Unit = {},
) {
    private val ch = Ft12Channel(port)
    private var fcb = false

    suspend fun readOut(): MeterReading = withContext(Dispatchers.IO) {
        ch.configure(cfg.baud, cfg.evenParity)
        onStatus("Comprobando el enlace con el contador…")
        linkStatus()
        resetLink()
        onStatus("Iniciando sesión…")
        authenticate()
        try {
            onStatus("Leyendo totales…")
            readTotals()
        } finally {
            runCatching { finishSession() }
        }
    }

    private fun formatText() = "${cfg.baud} baudios, ${if (cfg.evenParity) "8E1" else "8N1"}"

    private suspend fun linkStatus() {
        repeat(3) {
            ch.send(Ft12.fixed(Ft12.masterControl(Ft12.FN_LINK_STATUS, false, false), cfg.linkAddress))
            if (ch.waitFrame(1500) != null) return
        }
        throw IOException(
            "El contador no responde por IEC 870-5-102 (dirección de enlace ${cfg.linkAddress}, ${formatText()}). " +
                "Comprueba la dirección, la velocidad y la alineación de la sonda; la pestaña Diagnóstico te ayuda."
        )
    }

    private suspend fun resetLink() {
        ch.send(Ft12.fixed(Ft12.masterControl(Ft12.FN_RESET_LINK, false, false), cfg.linkAddress))
        val f = ch.waitFrame(2000) ?: throw IOException("El contador no confirmó la reposición del enlace.")
        if (f is Ft12Frame.Fixed && f.function == 1) throw IOException("El contador está ocupado. Inténtalo de nuevo.")
        fcb = false
    }

    /** Envía datos de usuario con confirmación; reintenta con el mismo FCB si no hay respuesta. */
    private suspend fun sendUserData(asdu: ByteArray) {
        fcb = !fcb
        val frame = Ft12.variable(Ft12.masterControl(Ft12.FN_USER_DATA, fcb, true), cfg.linkAddress, asdu)
        repeat(3) {
            ch.send(frame)
            val r = ch.waitFrame(2500)
            when {
                r == null -> Unit // sin respuesta: se reenvía la misma trama
                r is Ft12Frame.Fixed && r.function == 1 -> delay(500) // línea ocupada
                r is Ft12Frame.Variable -> { // el contador contesta ya con datos
                    ch.pushFront(r)
                    return
                }
                else -> return
            }
        }
        throw IOException("El contador no confirmó la petición.")
    }

    private var needToggle = true

    /**
     * Siguiente trama del contador: primero las ya recibidas; si no, una petición de datos de clase 2.
     * El FCB solo cambia cuando la petición anterior obtuvo respuesta (si no, se reenvía igual).
     */
    private suspend fun nextFrame(): Ft12Frame? {
        ch.waitFrame(0)?.let { return it }
        if (needToggle) {
            fcb = !fcb
            needToggle = false
        }
        ch.send(Ft12.fixed(Ft12.masterControl(Ft12.FN_REQUEST_CLASS2, fcb, true), cfg.linkAddress))
        val f = ch.waitFrame(2000)
        if (f != null) needToggle = true
        return f
    }

    /**
     * Sondea con "petición de datos de clase 2" hasta que [handle] devuelve true o se agota el tiempo.
     * [canStopOnIdle]: si el contador deja de tener datos y esto es true, termina sin error.
     */
    private suspend fun collect(
        timeoutMs: Long,
        canStopOnIdle: () -> Boolean = { false },
        handle: (AsduMsg) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        needToggle = true
        var timeouts = 0
        var idle = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val frame = nextFrame()
            if (frame == null) {
                if (++timeouts >= 4) throw IOException("El contador dejó de responder.")
                continue
            }
            timeouts = 0
            when (frame) {
                is Ft12Frame.Variable -> {
                    idle = 0
                    val m = Asdu102.parse(frame.asdu)
                    if (m != null && handle(m)) return
                }
                is Ft12Frame.Fixed -> {
                    // función 9: no hay datos disponibles todavía
                    if (frame.function == 9) {
                        idle++
                        if (idle >= 3 && canStopOnIdle()) return
                        delay(250)
                    }
                }
            }
        }
        throw IOException("Tiempo agotado esperando la respuesta del contador.")
    }

    private fun rejected(m: AsduMsg, hint: String) =
        IOException("El contador rechazó la petición (${Asdu102.causeText(m.cause)}). $hint")

    private suspend fun authenticate() {
        sendUserData(Asdu102.authenticate(cfg.pmAddress, cfg.key))
        collect(15_000) { m ->
            when {
                m.ti == Asdu102.TI_C_AC_NA_2 && m.cause == Asdu102.COT_CONFIRM && !m.negative -> true
                m.ti == Asdu102.TI_C_AC_NA_2 || m.negative || m.cause >= 13 ->
                    throw rejected(m, "Revisa la clave de acceso y la dirección del punto de medida en Ajustes.")
                else -> false
            }
        }
    }

    private suspend fun readTotals(): MeterReading {
        val register = cfg.register
        val end = LocalDateTime.now(Asdu102.MADRID).withSecond(0).withNano(0)
        val start = end.minusHours(if (register == 21) 72 else 3)
        sendUserData(Asdu102.readTotals(cfg.pmAddress, register, 1, 8, start, end))

        val blocks = mutableListOf<TotalsBlock>()
        collect(45_000, canStopOnIdle = { blocks.isNotEmpty() }) { m ->
            when {
                m.ti == Asdu102.TI_M_IT_TG_2 || m.ti == Asdu102.TI_M_IT_TK_2 -> {
                    Asdu102.parseTotals(m)?.let { blocks += it }
                    false
                }
                m.ti == Asdu102.TI_C_CI_NT_2 && m.cause == Asdu102.COT_END -> true
                m.negative || m.cause >= 13 ->
                    throw rejected(m, "Prueba el otro registro (curva de carga / resúmenes diarios) en Ajustes.")
                else -> false
            }
        }
        if (blocks.isEmpty()) {
            throw IOException(
                "El contador no devolvió totales para el periodo pedido (registro $register). " +
                    "Prueba el otro registro en Ajustes; puede que haga falta un cierre manual en el contador."
            )
        }
        val latest = blocks.filter { it.tag?.invalid != true }
            .maxByOrNull { it.tag?.time ?: LocalDateTime.MIN } ?: blocks.last()
        return Totals102.toReading(cfg.pmAddress, latest, System.currentTimeMillis())
    }

    private suspend fun finishSession() {
        sendUserData(Asdu102.finishSession(cfg.pmAddress))
    }
}
