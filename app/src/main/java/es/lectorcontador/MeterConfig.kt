package es.lectorcontador

import android.content.Context

/** Protocolo que habla el puerto óptico del contador. */
enum class Protocol(val label: String) {
    IEC62056("IEC 62056-21"),
    IEC870("IEC 870-5-102"),
    DLMS_ZIV("DLMS/COSEM · ZIV 5CTB"),
}

/**
 * Ajustes de comunicación. Los de IEC 870-5-102 los fija quien gestiona la medida:
 * dirección de enlace, dirección del punto de medida y clave de acceso (32 bits).
 */
data class MeterConfig(
    val protocol: Protocol = Protocol.IEC62056,
    val linkAddress: Int = 1,
    val pmAddress: Int = 1,
    val key: Long = 0L,
    val baud: Int = 9600,
    val evenParity: Boolean = true,
    /** Registro de totales: 11 = curva de carga (último periodo cerrado), 21 = resúmenes diarios. */
    val register: Int = 11,
    /** Parámetros DLMS/COSEM para el puerto óptico ZIV. */
    val dlmsClientAddress: Int = 16,
    val dlmsServerAddress: Int = 1,
    val dlmsPassword: String = "",
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun load(): MeterConfig = MeterConfig(
        protocol = runCatching { Protocol.valueOf(prefs.getString("protocolo", "") ?: "") }
            .getOrDefault(Protocol.IEC62056),
        linkAddress = prefs.getInt("dir_enlace", 1),
        pmAddress = prefs.getInt("dir_pm", 1),
        key = prefs.getLong("clave", 0L),
        baud = prefs.getInt("baudios", 9600),
        evenParity = prefs.getBoolean("paridad_par", true),
        register = prefs.getInt("registro", 11),
        dlmsClientAddress = prefs.getInt("dlms_cliente", 16),
        dlmsServerAddress = prefs.getInt("dlms_servidor", 1),
        dlmsPassword = prefs.getString("dlms_clave", "") ?: "",
    )

    fun save(c: MeterConfig) {
        prefs.edit()
            .putString("protocolo", c.protocol.name)
            .putInt("dir_enlace", c.linkAddress)
            .putInt("dir_pm", c.pmAddress)
            .putLong("clave", c.key)
            .putInt("baudios", c.baud)
            .putBoolean("paridad_par", c.evenParity)
            .putInt("registro", c.register)
            .putInt("dlms_cliente", c.dlmsClientAddress)
            .putInt("dlms_servidor", c.dlmsServerAddress)
            .putString("dlms_clave", c.dlmsPassword)
            .apply()
    }
}
