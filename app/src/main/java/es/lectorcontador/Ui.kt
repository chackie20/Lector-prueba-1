package es.lectorcontador

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES: Locale = Locale.forLanguageTag("es-ES")

private fun fmt(v: Double?, unit: String, decimals: Int = 3): String =
    if (v == null) "—" else String.format(ES, "%.${decimals}f %s", v, unit)

@Composable
fun App(vm: MeterViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Evita que la pantalla se apague durante el modo en vivo.
    val view = LocalView.current
    SideEffect { view.keepScreenOn = s.live }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("⚡") }, label = { Text("Lectura") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("📊") }, label = { Text("Histórico") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("🔧") }, label = { Text("Diagnóstico") },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Text("⚙") }, label = { Text("Ajustes") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                0 -> ReadingScreen(s, vm, onOpenSettings = { tab = 3 })
                1 -> HistoryScreen(s, vm)
                2 -> DiagnosticScreen(s, vm)
                else -> SettingsScreen(s, vm)
            }
        }
    }
}

// ───────────────────────────── Pantalla de lectura ─────────────────────────────

@Composable
private fun ReadingScreen(s: UiState, vm: MeterViewModel, onOpenSettings: () -> Unit) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lector de contador", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (s.usbPresent) "Sonda USB detectada" else "Sonda USB no detectada — conecta el adaptador OTG",
            color = if (s.usbPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )

        MeterCard(s.config, onOpenSettings)

        Text("Leer y guardar", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canRead = !s.busy && !s.live && !s.diagBusy
            Button(
                onClick = { vm.readOnce(ReadMode.CONSUMO) },
                enabled = canRead,
                modifier = Modifier.weight(1f),
            ) { Text("Consumo") }
            Button(
                onClick = { vm.readOnce(ReadMode.FV) },
                enabled = canRead,
                modifier = Modifier.weight(1f),
            ) { Text("FV") }
            Button(
                onClick = { vm.readOnce(ReadMode.TODO) },
                enabled = canRead,
                modifier = Modifier.weight(1f),
            ) { Text("Todo") }
        }
        Text(
            "Consumo: energía importada y valores instantáneos. FV: energía y potencia exportadas. " +
                "Todo: todos los códigos OBIS que devuelva el contador.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("En vivo")
            Switch(checked = s.live, onCheckedChange = vm::setLive, enabled = !s.diagBusy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ver", style = MaterialTheme.typography.bodyMedium)
            ReadMode.entries.forEach { m ->
                FilterChip(
                    selected = s.mode == m,
                    onClick = { vm.setMode(m) },
                    label = { Text(m.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cada", style = MaterialTheme.typography.bodyMedium)
            listOf(10, 30, 60).forEach { sec ->
                FilterChip(
                    selected = s.liveIntervalSec == sec,
                    onClick = { vm.setInterval(sec) },
                    label = { Text("$sec s") },
                )
            }
        }

        if (s.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(s.status, style = MaterialTheme.typography.bodySmall)
        }

        s.error?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = vm::dismissError) { Text("Cerrar") }
                }
            }
        }

        s.last?.let { r ->
            ReadingCards(r, s.mode, showRaw) { showRaw = !showRaw }
        }
    }
}

/** Acceso rápido: foto del contador con su protocolo; al tocarla se abre su configuración. */
@Composable
private fun MeterCard(c: MeterConfig, onOpenSettings: () -> Unit) {
    val summary = if (c.protocol == Protocol.IEC870) {
        "${c.protocol.label} · enlace ${c.linkAddress} · PM ${c.pmAddress} · " +
            "${c.baud} ${if (c.evenParity) "8E1" else "8N1"}"
    } else if (c.protocol == Protocol.DLMS_ZIV) {
        "${c.protocol.label} · cliente ${c.dlmsClientAddress} · servidor ${c.dlmsServerAddress}"
    } else {
        c.protocol.label
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpenSettings)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.meter_photo),
                contentDescription = "Foto del contador ZIV 5CTB-3AB-M",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (c.protocol == Protocol.DLMS_ZIV) "ZIV 5CTB-3AB-M" else "Contador eléctrico",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(summary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Toca para abrir su configuración",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ReadingCards(r: MeterReading, mode: ReadMode, showRaw: Boolean, onToggleRaw: () -> Unit) {
    if (mode != ReadMode.FV) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Energía activa total (consumo)", style = MaterialTheme.typography.labelLarge)
                Text(fmt(r.totalKwh, "kWh"), style = MaterialTheme.typography.headlineMedium)
                PeriodRows("Energía por periodo", r.periodsKwh)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Instantáneo", style = MaterialTheme.typography.labelLarge)
                Text("Potencia: ${fmt(r.powerKw, "kW")}")
                Text("Tensión: ${fmt(r.voltageV, "V", 1)}")
                Text("Intensidad: ${fmt(r.currentA, "A", 2)}")
            }
        }
    }
    if (mode != ReadMode.CONSUMO) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Fotovoltaica: energía exportada", style = MaterialTheme.typography.labelLarge)
                if (r.exportKwh == null && r.exportPowerKw == null) {
                    Text(
                        "Este contador no devuelve registros de energía exportada (2.8.x) " +
                            "ni de potencia exportada (2.7.0).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(fmt(r.exportKwh, "kWh"), style = MaterialTheme.typography.headlineMedium)
                    PeriodRows("Exportada por periodo", r.exportPeriodsKwh)
                    Text("Potencia exportada: ${fmt(r.exportPowerKw, "kW")}")
                }
            }
        }
    }
    val shown = r.valuesFor(mode)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Contador", style = MaterialTheme.typography.labelLarge)
            Text(r.identification.ifBlank { "—" })
            r.serial?.let { Text("Nº de serie: $it") }
            Text(
                "Leído el " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(r.timestamp)),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onToggleRaw) {
                Text(if (showRaw) "Ocultar valores OBIS" else "Ver valores OBIS (${shown.size})")
            }
            if (showRaw) {
                shown.forEach { v ->
                    val label = ObisInfo.label(v.code)
                    Text(
                        if (label != null) "${v.code} · $label" else v.code,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(obisText(v), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PeriodRows(title: String, periods: List<Double?>) {
    if (periods.none { it != null }) return
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    periods.forEachIndexed { i, v ->
        if (v != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("P${i + 1}")
                Text(fmt(v, "kWh"))
            }
        }
    }
}

/** "001234.567 kWh (2610011500)": valor, unidad y datos extra de la línea OBIS. */
private fun obisText(v: ObisValue): String =
    v.value + (v.unit?.let { " $it" } ?: "") + v.extras.joinToString("") { " ($it)" }

// ───────────────────────────── Pantalla de histórico ─────────────────────────────

@Composable
private fun HistoryScreen(s: UiState, vm: MeterViewModel) {
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val daily = remember(s.history) { dailyConsumption(s.history) }
    val rowFmt = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()) }

    fun export() {
        // Resumen de lecturas + (si existe) detalle de todos los códigos OBIS.
        val files = vm.exportFiles()
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Exportar lecturas"))
    }

    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Histórico", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::export, enabled = s.history.isNotEmpty()) { Text("Exportar CSV") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = s.history.isNotEmpty()) { Text("Borrar todo") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Consumo diario (kWh)", style = MaterialTheme.typography.labelLarge)
                    if (daily.isEmpty()) {
                        Text(
                            "Hacen falta al menos dos lecturas guardadas para calcular consumos.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        DailyBarChart(daily, Modifier.padding(top = 8.dp))
                        Text(
                            "Diferencia entre lecturas consecutivas, asignada al día de la lectura más reciente.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { Text("Lecturas guardadas (${s.history.size})", style = MaterialTheme.typography.labelLarge) }
        items(s.history.asReversed().take(100)) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rowFmt.format(Instant.ofEpochMilli(r.epochMs)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    fmt(r.totalKwh, "kWh") + (r.exportKwh?.let { "  ·  FV ${fmt(it, "kWh")}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { Text("", Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar el histórico?") },
            text = { Text("Se eliminarán todas las lecturas guardadas en este móvil. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DailyBarChart(data: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd/MM") }

    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        if (data.isEmpty()) return@Canvas
        val maxV = data.maxOf { it.second }.coerceAtLeast(0.001)
        val topPad = 18.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val chartH = size.height - topPad - bottomPad
        val slot = size.width / data.size
        val barW = slot * 0.6f

        data.forEachIndexed { i, (date, value) ->
            val h = (value / maxV).toFloat() * chartH
            val x = i * slot + (slot - barW) / 2f
            drawRect(barColor, topLeft = Offset(x, topPad + chartH - h), size = Size(barW, h))

            val valueText = measurer.measure(String.format(ES, "%.1f", value), labelStyle)
            drawText(
                valueText,
                topLeft = Offset(i * slot + (slot - valueText.size.width) / 2f, topPad + chartH - h - valueText.size.height),
            )
            val dateText = measurer.measure(date.format(dateFmt), labelStyle)
            drawText(
                dateText,
                topLeft = Offset(i * slot + (slot - dateText.size.width) / 2f, size.height - bottomPad + 2.dp.toPx()),
            )
        }
    }
}
