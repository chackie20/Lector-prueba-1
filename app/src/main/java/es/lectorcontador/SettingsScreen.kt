package es.lectorcontador

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(s: UiState, vm: MeterViewModel) {
    val c = s.config
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)

        Text("Protocolo del contador", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Protocol.entries.forEach { p ->
                FilterChip(
                    selected = c.protocol == p,
                    onClick = { vm.updateConfig { it.copy(protocol = p) } },
                    label = { Text(p.label) },
                )
            }
        }

        if (c.protocol == Protocol.IEC62056) {
            Text(
                "Texto ASCII a 300 baudios (7E1) que cambia de velocidad. Es el habitual en contadores de " +
                    "los años 2010 en adelante con puerto óptico estándar.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (c.protocol == Protocol.DLMS_ZIV) {
            Text(
                "Perfil para ZIV 5CTB-3AB-M: entra por el puerto óptico en modo E y continúa con DLMS/COSEM sobre HDLC. " +
                    "La lectura es solo de datos; no modifica la configuración del contador.",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField("Dirección cliente DLMS", c.dlmsClientAddress.toString(), 65535) { v ->
                vm.updateConfig { it.copy(dlmsClientAddress = v.toInt()) }
            }
            NumberField("Dirección servidor DLMS", c.dlmsServerAddress.toString(), 65535) { v ->
                vm.updateConfig { it.copy(dlmsServerAddress = v.toInt()) }
            }
            var password by remember(c.dlmsPassword) { mutableStateOf(c.dlmsPassword) }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; vm.updateConfig { c0 -> c0.copy(dlmsPassword = it) } },
                label = { Text("Clave DLMS (si el contador la exige)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Valores iniciales recomendados para este perfil: cliente 16 y servidor 1. Si la distribuidora ha cambiado el acceso, usa los parámetros que te haya facilitado.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Protocolo binario del Reglamento de puntos de medida (contadores de empresa, p. ej. " +
                    "Circutor CIRWATT). Hace falta la dirección de enlace, la dirección del punto de medida " +
                    "y la clave de acceso, que conoce quien gestiona la medida. Si no sabes la dirección " +
                    "de enlace, usa «Buscar dirección de enlace» en Diagnóstico.",
                style = MaterialTheme.typography.bodySmall,
            )

            NumberField("Dirección de enlace (0–65535)", c.linkAddress.toString(), 65535) { v ->
                vm.updateConfig { it.copy(linkAddress = v.toInt()) }
            }
            NumberField("Dirección del punto de medida (1–65535)", c.pmAddress.toString(), 65535) { v ->
                vm.updateConfig { it.copy(pmAddress = v.toInt()) }
            }
            NumberField("Clave de acceso (0–4294967295)", c.key.toString(), 4294967295L) { v ->
                vm.updateConfig { it.copy(key = v) }
            }
            Text(
                "Cada lectura envía la clave una sola vez. Con una clave incorrecta, algunos contadores " +
                    "bloquean el acceso tras varios fallos: no insistas si falla.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Velocidad", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1200, 2400, 4800, 9600, 19200).forEach { b ->
                    FilterChip(
                        selected = c.baud == b,
                        onClick = { vm.updateConfig { it.copy(baud = b) } },
                        label = { Text("$b") },
                    )
                }
            }

            Text("Formato de los caracteres", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = c.evenParity,
                    onClick = { vm.updateConfig { it.copy(evenParity = true) } },
                    label = { Text("8E1 (paridad par)") },
                )
                FilterChip(
                    selected = !c.evenParity,
                    onClick = { vm.updateConfig { it.copy(evenParity = false) } },
                    label = { Text("8N1 (sin paridad)") },
                )
            }

            Text("Registro de totales", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = c.register == 11,
                    onClick = { vm.updateConfig { it.copy(register = 11) } },
                    label = { Text("Curva de carga") },
                )
                FilterChip(
                    selected = c.register == 21,
                    onClick = { vm.updateConfig { it.copy(register = 21) } },
                    label = { Text("Resúmenes diarios") },
                )
            }
            Text(
                "Curva de carga da los totales acumulados al cierre del último periodo de integración " +
                    "(el dato más reciente). Resúmenes diarios da los de medianoche.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "En este protocolo el contador entrega energía activa importada y exportada y reactiva por " +
                    "cuadrantes, no el desglose por periodos tarifarios.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, initial: String, max: Long, onValid: (Long) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val digits = new.filter { it.isDigit() }.take(10)
            text = digits
            digits.toLongOrNull()?.takeIf { it <= max }?.let(onValid)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
