package es.lectorcontador

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagnosticScreen(s: UiState, vm: MeterViewModel) {
    val ctx = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnóstico", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Prueba varias velocidades y formatos en el puerto óptico y muestra los bytes que llegan, " +
                "para saber qué protocolo habla el contador. Dura unos 40 segundos. " +
                "Coloca la sonda sobre el puerto óptico antes de empezar. " +
                "El primer botón comprueba IEC 62056-21 (texto). El botón ZIV hace además una lectura DLMS del consumo total.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runDiagnostic, enabled = !s.diagBusy && !s.busy && !s.live) {
                Text("Diagnóstico IEC 62056")
            }
            OutlinedButton(onClick = vm::runDiagnosticZiv, enabled = !s.diagBusy && !s.busy && !s.live) {
                Text("ZIV + consumo total")
            }
            OutlinedButton(
                enabled = s.diagLines.isNotEmpty() && !s.diagBusy,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Informe de diagnóstico del contador")
                        putExtra(Intent.EXTRA_TEXT, s.diagLines.joinToString("\n"))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Compartir informe"))
                },
            ) { Text("Compartir informe") }
        }
        Text(
            "Contadores con protocolo IEC 870-5-102 (p. ej. Circutor CIRWATT, contadores de empresa): " +
                "estas pruebas solo envían la petición de estado del enlace, que es de solo lectura.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::runDiagnostic102,
                enabled = !s.diagBusy && !s.busy && !s.live,
            ) { Text("Diagnóstico 870-5-102") }
            OutlinedButton(
                onClick = vm::scanLinkAddress,
                enabled = !s.diagBusy && !s.busy && !s.live,
            ) { Text("Buscar dirección de enlace") }
        }
        if (s.diagBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (s.live) {
            Text("Desactiva el modo en vivo para ejecutar el diagnóstico.", style = MaterialTheme.typography.bodySmall)
        }
        s.diagLines.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
