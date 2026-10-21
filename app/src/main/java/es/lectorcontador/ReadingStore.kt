package es.lectorcontador

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Histórico de lecturas en un CSV interno de la app (una línea por lectura). */
class ReadingStore(context: Context) {
    private val file = File(context.filesDir, "lecturas.csv")
    private val obisFile = File(context.filesDir, "obis_completo.tsv")
    private val exportDir = File(context.cacheDir, "exports")

    @Synchronized
    fun load(): List<StoredReading> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(ReadingCsv::parse).sortedBy { it.epochMs }
    }

    @Synchronized
    fun append(r: StoredReading) {
        file.appendText(ReadingCsv.format(r) + "\n")
    }

    @Synchronized
    fun appendObis(epochMs: Long, values: List<ObisValue>) {
        if (values.isEmpty()) return
        obisFile.appendText(values.joinToString("") { ObisLog.format(epochMs, it) + "\n" })
    }

    @Synchronized
    fun clear() {
        file.delete()
        obisFile.delete()
    }

    /** Archivos a compartir: resumen de lecturas y detalle OBIS (solo los que tengan datos). */
    @Synchronized
    fun exportAll(): List<File> = listOfNotNull(export(), exportObis())

    /**
     * CSV con todos los códigos OBIS guardados (uno por fila): fecha, grupo, código, descripción,
     * valor, unidad y datos adicionales (fechas de máximos, etc.). Null si no hay datos.
     */
    @Synchronized
    fun exportObis(): File? {
        if (!obisFile.exists()) return null
        val rows = obisFile.readLines().mapNotNull(ObisLog::parse)
        if (rows.isEmpty()) return null
        exportDir.mkdirs()
        val zone = ZoneId.systemDefault()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone).format(Instant.now())
        val out = File(exportDir, "obis_completo_$stamp.csv")
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

        fun field(s: String) = s.replace(';', ',').replace('\n', ' ').replace('\r', ' ')
        // Valores numéricos con coma decimal, para Excel en español.
        fun value(s: String) = if (s.toDoubleOrNull() != null) s.replace('.', ',') else field(s)

        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("fecha_hora;grupo;codigo;descripcion;valor;unidad;datos_adicionales\n")
            for ((t, v) in rows) {
                w.write(
                    listOf(
                        dateFmt.format(Instant.ofEpochMilli(t)),
                        ObisInfo.groupOf(v.code).label,
                        field(v.code),
                        field(ObisInfo.label(v.code).orEmpty()),
                        value(v.value),
                        field(v.unit.orEmpty()),
                        field(v.extras.joinToString(" | ")),
                    ).joinToString(";")
                )
                w.write("\n")
            }
        }
        return out
    }

    /**
     * Genera un CSV listo para Excel en español: separador ';' y coma decimal.
     * Devuelve null si no hay lecturas.
     */
    @Synchronized
    fun export(): File? {
        val data = load()
        if (data.isEmpty()) return null
        exportDir.mkdirs()
        val zone = ZoneId.systemDefault()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone).format(Instant.now())
        val out = File(exportDir, "lecturas_contador_$stamp.csv")
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

        fun num(v: Double?, dec: Int) =
            if (v == null) "" else String.format(Locale.US, "%.${dec}f", v).replace('.', ',')

        out.bufferedWriter(Charsets.UTF_8).use { w ->
            val periodHeaders = (1..PERIODS).joinToString(";") { "p${it}_kwh" }
            val exportHeaders = (1..PERIODS).joinToString(";") { "fv_p${it}_kwh" }
            w.write(
                "fecha_hora;energia_total_kwh;$periodHeaders;potencia_kw;tension_v;intensidad_a;" +
                    "fv_energia_exportada_kwh;$exportHeaders;fv_potencia_exportada_kw\n"
            )
            for (r in data) {
                val periods = (0 until PERIODS).map { num(r.periodsKwh.getOrNull(it), 3) }
                val exported = (0 until PERIODS).map { num(r.exportPeriodsKwh.getOrNull(it), 3) }
                w.write(
                    (
                        listOf(dateFmt.format(Instant.ofEpochMilli(r.epochMs)), num(r.totalKwh, 3)) +
                            periods +
                            listOf(num(r.powerKw, 3), num(r.voltageV, 1), num(r.currentA, 2), num(r.exportKwh, 3)) +
                            exported +
                            listOf(num(r.exportPowerKw, 3))
                        ).joinToString(";")
                )
                w.write("\n")
            }
        }
        return out
    }
}
