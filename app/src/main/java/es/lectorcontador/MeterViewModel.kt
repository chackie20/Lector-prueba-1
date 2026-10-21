package es.lectorcontador

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(
    val usbPresent: Boolean = false,
    val busy: Boolean = false,
    val live: Boolean = false,
    val liveIntervalSec: Int = 10,
    val status: String = "",
    val error: String? = null,
    val last: MeterReading? = null,
    val history: List<StoredReading> = emptyList(),
    val diagBusy: Boolean = false,
    val diagLines: List<String> = emptyList(),
    /** Tipo de lectura elegido: decide qué registros se muestran y se guardan en detalle. */
    val mode: ReadMode = ReadMode.CONSUMO,
    /** Protocolo y parámetros de comunicación (pestaña Ajustes). */
    val config: MeterConfig = MeterConfig(),
)

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val connector = UsbConnector(app)
    private val store = ReadingStore(app)
    private val configStore = ConfigStore(app)
    private val mutex = Mutex()
    private var liveJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(history = store.load(), usbPresent = connector.hasDevice(), config = configStore.load())
        }
    }

    /** Cambia un ajuste de comunicación y lo guarda. */
    fun updateConfig(transform: (MeterConfig) -> MeterConfig) {
        _state.update { it.copy(config = transform(it.config)) }
        configStore.save(_state.value.config)
    }

    fun refreshUsb() {
        _state.update { it.copy(usbPresent = connector.hasDevice()) }
    }

    /** Lectura puntual del tipo elegido (consumo, FV o todo): se guarda en el histórico. */
    fun readOnce(mode: ReadMode) {
        if (_state.value.live) return
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { perform(save = true) }
    }

    /** Cambia el tipo de lectura que se muestra (también en modo en vivo). */
    fun setMode(mode: ReadMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setLive(on: Boolean) {
        liveJob?.cancel()
        liveJob = null
        if (!on) {
            _state.update { it.copy(live = false, busy = false) }
            return
        }
        _state.update { it.copy(live = true, error = null) }
        liveJob = viewModelScope.launch {
            var failures = 0
            // Con IEC 870-5-102 cada intento envía la clave de acceso: se para al primer fallo
            // para no provocar el bloqueo del contador por claves erróneas repetidas.
            val maxFailures = if (_state.value.config.protocol == Protocol.IEC870) 1 else MAX_LIVE_FAILURES
            while (isActive) {
                if (perform(save = false)) failures = 0 else failures++
                if (failures >= maxFailures) {
                    _state.update { it.copy(live = false) }
                    break
                }
                delay(_state.value.liveIntervalSec * 1000L)
            }
        }
    }

    private fun diagLog(line: String) {
        _state.update { it.copy(diagLines = it.diagLines + line) }
    }

    /** Abre la sonda, ejecuta [block] y deja el informe en [UiState.diagLines]. */
    private fun runDiag(block: suspend (UsbSerialPort) -> Unit) {
        val st = _state.value
        if (st.live || st.diagBusy || st.busy) return
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(diagBusy = true, diagLines = emptyList(), error = null) }
                try {
                    val port = connector.open()
                    try {
                        block(port)
                    } finally {
                        runCatching { port.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagLog("Error: ${e.message ?: "desconocido"}")
                } finally {
                    _state.update { it.copy(diagBusy = false) }
                }
            }
        }
    }

    /** IEC 62056-21: prueba varias velocidades/formatos con texto ASCII. */
    fun runDiagnostic() = runDiag { port ->
        MeterDiagnostics(port) { line -> diagLog(line) }.run()
    }

    /**
     * Diagnóstico específico del ZIV 5CTB: hace el saludo óptico a 300 7E1,
     * entra en DLMS a 9600 8N1 y además de comprobar la respuesta solicita
     * el consumo activo total (1.18.0 / 1.8.0). Es una lectura de solo lectura.
     */
    fun runDiagnosticZiv() = runDiag { port ->
        diagLog("ZIV 5CTB: iniciando diagnóstico de lectura…")
        diagLog("Saludo: 300 baudios, 7E1")
        diagLog("Interacción: 9600 baudios, 8N1")
        try {
            val reading = MeterDlmsZivLink(port, _state.value.config) { line -> diagLog(line) }.readOut()
            diagLog("RESPUESTA DLMS: contador ${reading.identification}")
            reading.serial?.let { diagLog("Número de contador: $it") }
            diagLog("Consumo total: ${reading.totalKwh?.let { String.format(java.util.Locale.US, "%.3f kWh", it) } ?: "no disponible"}")
            val total = reading.values.firstOrNull { it.code == "1.18.0" || it.code == "1.8.0" }
            if (total != null) diagLog("OBIS consumo total: ${total.code}(${total.value}${total.unit?.let { "*$it" } ?: ""})")
            diagLog("Diagnóstico ZIV finalizado correctamente.")
        } catch (e: Exception) {
            diagLog("ZIV: no se pudo obtener el consumo total: ${e.message ?: "error desconocido"}")
        }
    }

    /** IEC 870-5-102: petición de estado del enlace con cada velocidad y formato. */
    fun runDiagnostic102() = runDiag { port ->
        val found = Meter102Diagnostics(port, _state.value.config) { line -> diagLog(line) }.probe()
        if (found != null) {
            updateConfig { it.copy(protocol = Protocol.IEC870, baud = found.baud, evenParity = found.evenParity) }
        }
    }

    /** IEC 870-5-102: busca la dirección de enlace probando 0..255 y 65535. */
    fun scanLinkAddress() = runDiag { port ->
        val address = Meter102Diagnostics(port, _state.value.config) { line -> diagLog(line) }.scanAddresses()
        if (address != null) {
            updateConfig { it.copy(protocol = Protocol.IEC870, linkAddress = address) }
            diagLog("Dirección de enlace guardada en Ajustes: $address")
        }
    }

    fun setInterval(seconds: Int) {
        _state.update { it.copy(liveIntervalSec = seconds) }
    }

    /** Resumen de lecturas y, si existe, detalle de todos los códigos OBIS guardados. */
    fun exportFiles(): List<File> = store.exportAll()

    fun clearHistory() {
        store.clear()
        _state.update { it.copy(history = emptyList()) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** @return true si la lectura fue correcta. */
    private suspend fun perform(save: Boolean): Boolean = mutex.withLock {
        _state.update { it.copy(busy = true, error = null, status = "Conectando con la sonda…") }
        try {
            val port = connector.open()
            val cfg = _state.value.config
            val reading = try {
                when (cfg.protocol) {
                    Protocol.IEC870 -> Meter102Link(port, cfg) { msg -> _state.update { it.copy(status = msg) } }.readOut()
                    Protocol.DLMS_ZIV -> MeterDlmsZivLink(port, cfg) { msg -> _state.update { it.copy(status = msg) } }.readOut()
                    Protocol.IEC62056 -> MeterLink(port) { msg -> _state.update { it.copy(status = msg) } }.readOut()
                }
            } finally {
                runCatching { port.close() }
            }
            if (save) {
                store.append(reading.toStored())
                store.appendObis(reading.timestamp, reading.valuesFor(_state.value.mode))
            }
            _state.update {
                it.copy(
                    last = reading,
                    usbPresent = true,
                    status = "Lectura correcta",
                    history = if (save) store.load() else it.history,
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(error = e.message ?: "Error desconocido", usbPresent = connector.hasDevice())
            }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        const val MAX_LIVE_FAILURES = 3
    }
}
