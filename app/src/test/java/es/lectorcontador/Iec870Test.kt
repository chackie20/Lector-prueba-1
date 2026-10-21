package es.lectorcontador

import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iec870Test {

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun control_bytes() {
        assertEquals(0x49, Ft12.masterControl(Ft12.FN_LINK_STATUS, fcb = false, fcv = false))
        assertEquals(0x40, Ft12.masterControl(Ft12.FN_RESET_LINK, fcb = false, fcv = false))
        assertEquals(0x73, Ft12.masterControl(Ft12.FN_USER_DATA, fcb = true, fcv = true))
        assertEquals(0x53, Ft12.masterControl(Ft12.FN_USER_DATA, fcb = false, fcv = true))
        assertEquals(0x7B, Ft12.masterControl(Ft12.FN_REQUEST_CLASS2, fcb = true, fcv = true))
        assertEquals(0x5B, Ft12.masterControl(Ft12.FN_REQUEST_CLASS2, fcb = false, fcv = true))
    }

    @Test fun fixed_frames() {
        assertArrayEquals(hex("10 49 01 00 4A 16"), Ft12.fixed(0x49, 1))
        assertArrayEquals(hex("10 40 01 00 41 16"), Ft12.fixed(0x40, 1))
        assertArrayEquals(hex("10 7B 01 00 7C 16"), Ft12.fixed(0x7B, 1))
        // dirección de 2 octetos: 0x0102 -> 02 01
        assertArrayEquals(hex("10 49 02 01 4C 16"), Ft12.fixed(0x49, 0x0102))
    }

    @Test fun authentication_frame() {
        val asdu = Asdu102.authenticate(pm = 1, key = 7)
        assertArrayEquals(hex("B7 01 06 01 00 00 07 00 00 00"), asdu)
        val frame = Ft12.variable(0x73, 1, asdu)
        // L = 3 + 10 = 13 (0x0D), CS = 0x3A
        assertArrayEquals(hex("68 0D 0D 68 73 01 00 B7 01 06 01 00 00 07 00 00 00 3A 16"), frame)
    }

    @Test fun key_is_little_endian_32_bits() {
        assertArrayEquals(hex("78 56 34 12"), Asdu102.le32(0x12345678L))
        assertArrayEquals(hex("FF FF FF FF"), Asdu102.le32(4294967295L))
    }

    @Test fun finish_session_asdu() {
        assertArrayEquals(hex("BB 01 06 01 00 00"), Asdu102.finishSession(1))
    }

    @Test fun time_tag_encoding_matches_reference() {
        // 2018-07-01 01:00 (domingo, verano) y 2018-08-01 00:00 (miércoles, verano)
        assertArrayEquals(hex("00 81 E1 07 12"), Asdu102.timeA(LocalDateTime.of(2018, 7, 1, 1, 0), summer = true))
        assertArrayEquals(hex("00 80 61 08 12"), Asdu102.timeA(LocalDateTime.of(2018, 8, 1, 0, 0), summer = true))
    }

    @Test fun time_tag_roundtrip_and_summer_detection() {
        val t = LocalDateTime.of(2026, 10, 19, 11, 30)
        val tag = Asdu102.parseTimeA(Asdu102.timeA(t), 0)!!
        assertEquals(t, tag.time)
        assertTrue(tag.summer)        // 19 de octubre de 2026: aún horario de verano
        assertTrue(!tag.invalid)
        val winter = LocalDateTime.of(2026, 12, 1, 8, 5)
        assertTrue(!Asdu102.parseTimeA(Asdu102.timeA(winter), 0)!!.summer)
        assertNull(Asdu102.parseTimeA(hex("00 81 E1"), 0))                 // incompleta
        assertNull(Asdu102.parseTimeA(hex("00 81 E1 0D 12"), 0))           // mes 13
    }

    @Test fun read_totals_request_matches_reference_bytes() {
        val asdu = Asdu102.readTotals(
            pm = 1, register = 21, first = 1, last = 8,
            start = LocalDateTime.of(2018, 7, 1, 1, 0), end = LocalDateTime.of(2018, 8, 1, 0, 0),
        )
        assertArrayEquals(
            hex("7A 01 06 01 00 15 01 08 00 81 E1 07 12 00 80 61 08 12"),
            asdu,
        )
        // L = 3 + 18 = 21 (0x15), como en la especificación de referencia
        assertEquals(0x15, Ft12.variable(0x73, 1, asdu)[1].toInt())
    }

    @Test fun extract_frames_with_garbage_echo_and_partial() {
        val ack = Ft12.fixed(0x00, 1)
        val data = Ft12.variable(0x08, 1, hex("0B 01 05 01 00 15"))
        val echo = Ft12.fixed(0x49, 1) // eco de nuestra propia petición (PRM = 1)
        val buf = hex("00 FF 12") + echo + ack + data + data.copyOf(5)
        val ex = Ft12.extract(buf)
        assertEquals(3, ex.frames.size)
        // el eco lleva PRM = 1 (no es del contador); la confirmación y los datos sí
        assertEquals(listOf(false, true, true), ex.frames.map { it.fromSlave })
        assertTrue(ex.frames[1] is Ft12Frame.Fixed)
        assertEquals(0, ex.frames[1].function)
        assertEquals(data.copyOf(5).toList(), ex.rest.toList())
        // al llegar el resto, la trama se completa
        val ex2 = Ft12.extract(ex.rest + data.copyOfRange(5, data.size))
        assertEquals(1, ex2.frames.size)
        assertEquals(0, ex2.rest.size)
    }

    @Test fun extract_rejects_bad_checksum_and_resyncs() {
        val good = Ft12.fixed(0x00, 7)
        val bad = good.copyOf().also { it[4] = (it[4] + 1).toByte() }
        val ex = Ft12.extract(bad + good)
        assertEquals(1, ex.frames.size)
        assertEquals(7, ex.frames[0].address)
    }

    @Test fun parse_integrated_totals_reference_frame() {
        // Totales de referencia: 8 objetos de 6 octetos + etiqueta de tiempo (objetos 7 y 8 no válidos)
        val body = hex(
            "01 04 00 00 00 00  02 00 00 00 00 00  03 1B 00 00 00 00  04 00 00 00 00 00  " +
                "05 00 00 00 00 00  06 00 00 00 00 00  07 00 00 00 00 80  08 00 00 00 00 80  " +
                "00 81 E1 07 12",
        )
        val asdu = hex("0B 08 05 01 00 15") + body
        val msg = Asdu102.parse(asdu)!!
        assertEquals(11, msg.ti)
        assertEquals(5, msg.cause)
        assertEquals(1, msg.pm)
        assertEquals(21, msg.register)
        val block = Asdu102.parseTotals(msg)!!
        assertEquals(8, block.totals.size)
        assertEquals(IntegratedTotal(1, 4, 0), block.totals[0])
        assertEquals(27L, block.totals[2].value)
        assertTrue(block.totals[6].invalid)
        assertEquals(LocalDateTime.of(2018, 7, 1, 1, 0), block.tag!!.time)

        val reading = Totals102.toReading(1, block, 0L)
        assertEquals(4.0, reading.totalKwh!!, 1e-9)
        assertEquals(0.0, reading.exportKwh!!, 1e-9)
        assertEquals(27.0, reading.values.first { it.code == "5.8.0" }.value.toDouble(), 1e-9)
        assertTrue(reading.values.none { it.code == "102.7" })   // los inválidos se omiten
    }

    @Test fun totals_are_signed_32_bit_little_endian() {
        val body = hex("01 FF FF FF FF 00  00 81 E1 07 12")
        val msg = Asdu102.parse(hex("08 01 05 01 00 0B") + body)!!
        assertEquals(-1L, Asdu102.parseTotals(msg)!!.totals[0].value)
        val big = hex("01 00 00 00 01 00  00 81 E1 07 12")   // 0x01000000
        val m2 = Asdu102.parse(hex("08 01 05 01 00 0B") + big)!!
        assertEquals(16777216L, Asdu102.parseTotals(m2)!!.totals[0].value)
    }

    @Test fun totals_parse_rejects_other_types_and_short_bodies() {
        assertNull(Asdu102.parseTotals(Asdu102.parse(hex("B7 01 07 01 00 00"))!!))
        assertNull(Asdu102.parseTotals(Asdu102.parse(hex("08 02 05 01 00 0B 01 00 00 00 00 00"))!!)) // faltan datos
        assertNotNull(Asdu102.parse(hex("B7 01 07 01 00 00")))
        assertNull(Asdu102.parse(hex("B7 01")))
    }

    @Test fun negative_confirmation_flag() {
        val m = Asdu102.parse(hex("B7 01 47 01 00 00"))!!   // causa 7 con bit P/N = 1
        assertEquals(7, m.cause)
        assertTrue(m.negative)
    }
}
