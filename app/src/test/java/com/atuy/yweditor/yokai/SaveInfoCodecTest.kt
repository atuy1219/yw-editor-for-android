package com.atuy.yweditor.yokai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveInfoCodecTest {

    @Test
    fun parse_readsPlayTimeAndPlayerName() {
        val data = ByteArray(0x80)
        data[0x24] = 0x48
        data[0x25] = 0x20
        data[0x26] = 0x01
        data[0x27] = 0x00
        val nameBytes = "ケータ".toByteArray(Charsets.UTF_8)
        nameBytes.copyInto(data, destinationOffset = 0x30)
        data[0x30 + nameBytes.size] = 0

        val parsed = SaveInfoCodec.parse(data)

        assertEquals(20, parsed.playHours)
        assertEquals(30, parsed.playMinutes)
        assertEquals("ケータ", parsed.playerName)
    }

    @Test
    fun apply_writesPlayTimeAsLittleEndianSeconds() {
        val base = ByteArray(0x80)

        val patched = SaveInfoCodec.apply(
            base,
            SaveInfo(playHours = 1, playMinutes = 2, playerName = "A"),
        )

        assertEquals(0x88, patched[0x24].toInt() and 0xFF)
        assertEquals(0x0E, patched[0x25].toInt() and 0xFF)
        assertEquals(0x00, patched[0x26].toInt() and 0xFF)
        assertEquals(0x00, patched[0x27].toInt() and 0xFF)
    }

    @Test
    fun apply_padsPlayerNameRegionWithZeros() {
        val base = ByteArray(0x80) { 0x7F.toByte() }

        val patched = SaveInfoCodec.apply(
            base,
            SaveInfo(playHours = 0, playMinutes = 0, playerName = "abc"),
        )

        assertEquals('a'.code, patched[0x30].toInt() and 0xFF)
        assertEquals('b'.code, patched[0x31].toInt() and 0xFF)
        assertEquals('c'.code, patched[0x32].toInt() and 0xFF)
        assertEquals(0, patched[0x33].toInt() and 0xFF)

        val expectedTail = ByteArray(24 - 4) { 0 }
        val actualTail = patched.copyOfRange(0x34, 0x30 + 24)
        assertArrayEquals(expectedTail, actualTail)
    }

    @Test
    fun truncatePlayerName_limitsUtf8To23Bytes() {
        val input = "あいうえおかきくけ" // 27 bytes in UTF-8

        val truncated = SaveInfoCodec.truncatePlayerName(input)

        assertTrue(SaveInfoCodec.isPlayerNameWithinLimit(truncated))
        assertEquals(21, truncated.toByteArray(Charsets.UTF_8).size)
    }
}

