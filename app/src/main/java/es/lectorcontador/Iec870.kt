package es.lectorcontador

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Protocolo IEC 870-5-102 (perfil español de Red Eléctrica, "Reglamento de puntos de medida").
 * Capa de enlace FT1.2 + los mensajes de aplicación necesarios para leer totales integrados.
 * Sin dependencias de Android, para poder probarlo con JUnit.
 *
 * Trama fija:     10 C A A CS 16
 * Trama variable: 68 L L 68 C A A <ASDU> CS 16        (L = 3 + longitud del ASDU)
 * CS = suma aritmética módulo 256 de C, A, A y el ASDU. Direcciones y enteros: menos significativo primero.
 */

sealed interface Ft12Frame {
    val control: Int
    val address: Int

    /** Las tramas del contador (esclavo) llevan PRM = 0 (bit 6 del campo de control). */
    val fromSlave: Boolean get() = (control and 0x40) == 0

    /** Código de función (4 bits bajos del campo de control). */
    val function: Int get() = control and 0x0F

    class Fixed(override val control: Int, override val address: Int) : Ft12Frame {
        override fun equals(other: Any?) = other is Fixed && other.control == control && other.address == address
        override fun hashCode() = control * 31 + address
        override fun toString() = "Fija(C=0x%02X, A=%d)".format(control, address)
    }

    class Variable(override val control: Int, override val address: Int, val asdu: ByteArray) : Ft12Frame {
        override fun equals(other: Any?) =
            other is Variable && other.control == control && other.address == address && other.asdu.contentEquals(asdu)

        override fun hashCode() = (control * 31 + address) * 31 + asdu.contentHashCode()
        override fun toString() = "Variable(C=0x%02X, A=%d, %d bytes)".format(control, address, asdu.size)
    }
}

object Ft12 {
    const val START_FIXED = 0x10
    const val START_VARIABLE = 0x68
    const val END = 0x16

    const val FN_RESET_LINK = 0        // reposición del enlace remoto
    const val FN_USER_DATA = 3         // datos de usuario con confirmación
    const val FN_LINK_STATUS = 9       // petición de estado del enlace
    const val FN_REQUEST_CLASS2 = 11   // petición de datos de clase 2

    /** Campo de control del maestro: PRM = 1, FCB, FCV y función. */
    fun masterControl(function: Int, fcb: Boolean, fcv: Boolean): Int =
        0x40 or (if (fcb) 0x20 else 0) or (if (fcv) 0x10 else 0) or (function and 0x0F)

    fun fixed(control: Int, address: Int): ByteArray {
        val a0 = address and 0xFF
        val a1 = (address shr 8) and 0xFF
        val cs = (control + a0 + a1) and 0xFF
        return byteArrayOf(
            START_FIXED.toByte(), control.toByte(), a0.toByte(), a1.toByte(), cs.toByte(), END.toByte(),
        )
    }

    fun variable(control: Int, address: Int, asdu: ByteArray): ByteArray {
        val l = 3 + asdu.size
        require(l <= 255) { "ASDU demasiado largo" }
        val a0 = address and 0xFF
        val a1 = (address shr 8) and 0xFF
        var cs = control + a0 + a1
        for (b in asdu) cs += b.toInt() and 0xFF
        return byteArrayOf(
            START_VARIABLE.toByte(), l.toByte(), l.toByte(), START_VARIABLE.toByte(),
            control.toByte(), a0.toByte(), a1.toByte(),
        ) + asdu + byteArrayOf((cs and 0xFF).toByte(), END.toByte())
    }

    class Extracted(val frames: List<Ft12Frame>, val rest: ByteArray)

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /**
     * Extrae todas las tramas válidas del búfer, descartando basura y tramas con checksum erróneo.
     * [Extracted.rest] contiene lo que queda sin procesar (una trama aún incompleta).
     */
    fun extract(buf: ByteArray): Extracted {
        val frames = mutableListOf<Ft12Frame>()
        var i = 0
        while (i < buf.size) {
            val b = u(buf, i)
            if (b == START_FIXED) {
                if (i + 6 > buf.size) break
                val c = u(buf, i + 1)
                val a0 = u(buf, i + 2)
                val a1 = u(buf, i + 3)
                if (u(buf, i + 5) == END && u(buf, i + 4) == ((c + a0 + a1) and 0xFF)) {
                    frames += Ft12Frame.Fixed(c, a0 or (a1 shl 8))
                    i += 6
                } else {
                    i++
                }
            } else if (b == START_VARIABLE) {
                if (i + 4 > buf.size) break
                val l = u(buf, i + 1)
                if (l < 3 || l != u(buf, i + 2) || u(buf, i + 3) != START_VARIABLE) {
                    i++
                    continue
                }
                val total = 6 + l
                if (i + total > buf.size) break
                var cs = 0
                for (k in i + 4 until i + 4 + l) cs += u(buf, k)
                if (u(buf, i + 4 + l) == (cs and 0xFF) && u(buf, i + 5 + l) == END) {
                    val c = u(buf, i + 4)
                    val a = u(buf, i + 5) or (u(buf, i + 6) shl 8)
                    frames += Ft12Frame.Variable(c, a, buf.copyOfRange(i + 7, i + 4 + l))
                    i += total
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return Extracted(frames, buf.copyOfRange(minOf(i, buf.size), buf.size))
    }
}

/** Cabecera común de un ASDU: tipo, calificador, causa, dirección del punto de medida y del registro. */
class AsduMsg(
    val ti: Int,
    val vsq: Int,
    val cause: Int,
    val negative: Boolean,
    val pm: Int,
    val register: Int,
    val body: ByteArray,
)

data class TimeTag(val time: LocalDateTime, val invalid: Boolean, val summer: Boolean)

data class IntegratedTotal(val address: Int, val value: Long, val quality: Int) {
    /** Bit 7 de la cualidad: valor no válido. */
    val invalid: Boolean get() = (quality and 0x80) != 0
}

class TotalsBlock(val totals: List<IntegratedTotal>, val tag: TimeTag?)

object Asdu102 {
    const val TI_M_IT_TG_2 = 8      // totales integrados (respuesta a C_CI_NT_2)
    const val TI_M_IT_TK_2 = 11     // totales integrados incrementales
    const val TI_C_CI_NT_2 = 122    // leer totales integrados absolutos
    const val TI_C_AC_NA_2 = 183    // iniciar sesión y enviar clave
    const val TI_C_FS_NA_2 = 187    // finalizar sesión

    const val COT_ACTIVATION = 6
    const val COT_CONFIRM = 7
    const val COT_END = 10

    /** Los contadores etiquetan la hora en horario local español, con un bit de verano (SU). */
    val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun header(ti: Int, cot: Int, pm: Int, register: Int): ByteArray = byteArrayOf(
        ti.toByte(), 1, cot.toByte(), (pm and 0xFF).toByte(), ((pm shr 8) and 0xFF).toByte(), register.toByte(),
    )

    fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    fun authenticate(pm: Int, key: Long): ByteArray =
        header(TI_C_AC_NA_2, COT_ACTIVATION, pm, 0) + le32(key)

    fun finishSession(pm: Int): ByteArray = header(TI_C_FS_NA_2, COT_ACTIVATION, pm, 0)

    fun readTotals(
        pm: Int, register: Int, first: Int, last: Int, start: LocalDateTime, end: LocalDateTime,
    ): ByteArray =
        header(TI_C_CI_NT_2, COT_ACTIVATION, pm, register) +
            byteArrayOf(first.toByte(), last.toByte()) + timeA(start) + timeA(end)

    fun isSummer(t: LocalDateTime): Boolean = MADRID.rules.isDaylightSavings(t.atZone(MADRID).toInstant())

    /** Etiqueta de tiempo de 5 octetos (tipo a): minuto, hora+SU, día+día de la semana, mes, año-2000. */
    fun timeA(t: LocalDateTime, summer: Boolean = isSummer(t)): ByteArray = byteArrayOf(
        t.minute.toByte(),
        (t.hour or (if (summer) 0x80 else 0)).toByte(),
        (t.dayOfMonth or (t.dayOfWeek.value shl 5)).toByte(),
        t.monthValue.toByte(),
        (t.year - 2000).toByte(),
    )

    fun parseTimeA(b: ByteArray, off: Int): TimeTag? {
        if (off < 0 || off + 5 > b.size) return null
        val minute = u(b, off) and 0x3F
        val invalid = (u(b, off) and 0x80) != 0
        val hour = u(b, off + 1) and 0x1F
        val summer = (u(b, off + 1) and 0x80) != 0
        val day = (u(b, off + 2) and 0x1F).coerceAtLeast(1)
        val month = (u(b, off + 3) and 0x0F).coerceAtLeast(1)
        val year = 2000 + (u(b, off + 4) and 0x7F)
        return runCatching { TimeTag(LocalDateTime.of(year, month, day, hour, minute), invalid, summer) }.getOrNull()
    }

    fun parse(asdu: ByteArray): AsduMsg? {
        if (asdu.size < 6) return null
        val cot = u(asdu, 2)
        return AsduMsg(
            ti = u(asdu, 0),
            vsq = u(asdu, 1),
            cause = cot and 0x3F,
            negative = (cot and 0x40) != 0,
            pm = u(asdu, 3) or (u(asdu, 4) shl 8),
            register = u(asdu, 5),
            body = asdu.copyOfRange(6, asdu.size),
        )
    }

    /**
     * Totales integrados: N objetos de 6 octetos (dirección, valor de 4 octetos con signo, cualidad)
     * seguidos de una etiqueta de tiempo de 5 octetos. Direcciones: 1 activa importada, 2 activa exportada,
     * 3..6 reactiva en cuadrantes Q1..Q4.
     */
    fun parseTotals(m: AsduMsg): TotalsBlock? {
        if (m.ti != TI_M_IT_TG_2 && m.ti != TI_M_IT_TK_2) return null
        val n = m.vsq and 0x7F
        if (n == 0 || m.body.size < n * 6) return null
        val totals = (0 until n).map { i ->
            val o = i * 6
            val raw = u(m.body, o + 1).toLong() or (u(m.body, o + 2).toLong() shl 8) or
                (u(m.body, o + 3).toLong() shl 16) or (u(m.body, o + 4).toLong() shl 24)
            val value = raw.toInt().toLong() // interpretado con signo (32 bits)
            IntegratedTotal(u(m.body, o), value, u(m.body, o + 5))
        }
        return TotalsBlock(totals, parseTimeA(m.body, n * 6))
    }

    fun causeText(cause: Int): String = when (cause) {
        13 -> "punto de medida o registro desconocido"
        14 -> "tipo de ASDU no disponible"
        15 -> "causa de transmisión desconocida"
        16 -> "dirección de objeto desconocida"
        17, 18 -> "periodo de integración no disponible"
        else -> "causa $cause"
    }
}

/** Convierte los totales de un contador 870-5-102 a una lectura con códigos OBIS equivalentes. */
object Totals102 {
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun codeFor(address: Int) = when (address) {
        1 -> "1.8.0"   // activa importada
        2 -> "2.8.0"   // activa exportada
        3 -> "5.8.0"   // reactiva Q1
        4 -> "6.8.0"   // reactiva Q2
        5 -> "7.8.0"   // reactiva Q3
        6 -> "8.8.0"   // reactiva Q4
        else -> "102.$address"
    }

    fun toReading(pm: Int, block: TotalsBlock, timestamp: Long): MeterReading {
        val tagText = block.tag?.let { FMT.format(it.time) + if (it.summer) " (verano)" else "" }
        val values = mutableListOf(ObisValue("0.0.0", "PM $pm", null))
        for (t in block.totals) {
            if (t.invalid) continue
            val unit = if (t.address <= 2) "kWh" else "kvarh"
            values += ObisValue(codeFor(t.address), t.value.toString(), unit, listOfNotNull(tagText))
        }
        return MeterReading(
            timestamp = timestamp,
            identification = "IEC 870-5-102 · punto de medida $pm",
            values = values,
            raw = "totales hasta ${tagText ?: "?"}: " +
                block.totals.joinToString { "#${it.address}=${it.value}${if (it.invalid) "(inválido)" else ""}" },
        )
    }
}
