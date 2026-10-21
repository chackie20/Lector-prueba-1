package es.lectorcontador

/**
 * Un registro OBIS leído del contador, p. ej. code="1.8.0", value="001234.567", unit="kWh".
 * [extras] son los demás paréntesis de la línea, p. ej. la fecha de una máxima demanda:
 * `1.6.1(02.5*kW)(2610011500)` → value="02.5", unit="kW", extras=["2610011500"].
 */
data class ObisValue(
    val code: String,
    val value: String,
    val unit: String?,
    val extras: List<String> = emptyList(),
)

/** Número máximo de periodos tarifarios (P1..P6, como en las tarifas 3.0TD y 6.1TD). */
const val PERIODS = 6

/** Lectura tal y como se guarda en el histórico. [periodsKwh] siempre tiene [PERIODS] elementos. */
data class StoredReading(
    val epochMs: Long,
    val totalKwh: Double?,
    val periodsKwh: List<Double?>,
    val powerKw: Double?,
    val voltageV: Double?,
    val currentA: Double?,
    val exportKwh: Double? = null,
    val exportPeriodsKwh: List<Double?> = List(PERIODS) { null },
    val exportPowerKw: Double? = null,
)

/** Resultado completo de una lectura del puerto óptico. */
data class MeterReading(
    val timestamp: Long,
    val identification: String,
    val values: List<ObisValue>,
    val raw: String,
) {
    private fun find(code: String): ObisValue? = values.firstOrNull { it.code == code }

    /** Valor numérico normalizado: W→kW y Wh→kWh. */
    private fun number(code: String): Double? {
        val v = find(code) ?: return null
        val n = v.value.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v.unit == "Wh" || v.unit == "W" || v.unit == "varh" || v.unit == "var") n / 1000.0 else n
    }

    private fun numberAny(vararg codes: String): Double? = codes.firstNotNullOfOrNull { number(it) }

    /**
     * Energía activa importada por periodo. Además de los OBIS habituales 1.8.x,
     * admite la nomenclatura usada por varios ZIV: 1.18.x.
     */
    val periodsKwh: List<Double?> get() = (1..PERIODS).map { numberAny("1.8.$it", "1.18.$it") }

    /** Energía activa importada total. */
    val totalKwh: Double?
        get() = numberAny("1.8.0", "1.18.0")
            ?: periodsKwh.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** Energía activa exportada (fotovoltaica / vertido). ZIV puede usar 1.28.x. */
    val exportPeriodsKwh: List<Double?> get() = (1..PERIODS).map { numberAny("2.8.$it", "1.28.$it") }
    val exportKwh: Double?
        get() = numberAny("2.8.0", "1.28.0")
            ?: exportPeriodsKwh.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    val exportPowerKw: Double? get() = numberAny("2.7.0", "0.24.0")

    /** Registros que corresponden al modo elegido (consumo, FV o todos). */
    fun valuesFor(mode: ReadMode): List<ObisValue> =
        values.filter { mode.includes(ObisInfo.groupOf(it.code)) }

    val powerKw: Double? get() = numberAny("1.7.0", "0.17.0")
    val voltageV: Double? get() = numberAny("32.7.0", "0.327.0")
    val currentA: Double? get() = numberAny("31.7.0", "0.317.0")

    val serial: String? get() = find("0.0.0")?.value ?: find("96.1.1")?.value ?: find("0.0.1")?.value

    fun toStored() = StoredReading(
        timestamp, totalKwh, periodsKwh, powerKw, voltageV, currentA,
        exportKwh, exportPeriodsKwh, exportPowerKw,
    )
}
