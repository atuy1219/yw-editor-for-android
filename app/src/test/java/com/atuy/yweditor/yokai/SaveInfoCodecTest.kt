package com.atuy.yweditor.yokai

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveInfoCodecTest {

    @Test
    fun slotBaseOffset_returnsExpectedAddress() {
        assertEquals(0x3100, SaveInfoCodec.slotBaseOffset("game1.yw"))
        assertEquals(0x3200, SaveInfoCodec.slotBaseOffset("game2.yw"))
        assertEquals(0x3300, SaveInfoCodec.slotBaseOffset("game3.yw"))
        assertEquals(0x3400, SaveInfoCodec.slotBaseOffset("game0.yw"))
    }

    @Test
    fun parse_readsPlayTimeAndSaveDateFromGameAndHead() {
        val game = ByteArray(0x9300)
        val head = ByteArray(0x4000)
        // 73800 sec = 20h 30m
        game[0x24] = 0x48
        game[0x25] = 0x20
        game[0x26] = 0x01
        game[0x27] = 0x00
        val playerName = "ケータ".toByteArray(Charsets.UTF_8)
        playerName.copyInto(game, destinationOffset = SaveInfoCodec.PLAYER_NAME_OFFSET)
        game[SaveInfoCodec.PLAYER_NAME_OFFSET + playerName.size] = 0
        // 543210 (0x000849EA)
        game[SaveInfoCodec.MONEY_OFFSET] = 0xEA.toByte()
        game[SaveInfoCodec.MONEY_OFFSET + 1] = 0x49
        game[SaveInfoCodec.MONEY_OFFSET + 2] = 0x08
        game[SaveInfoCodec.MONEY_OFFSET + 3] = 0x00

        val base = 0x3200
        head[base + 0xC0] = 0xE9.toByte()
        head[base + 0xC1] = 0x07
        head[base + 0xC2] = 0x03
        head[base + 0xC3] = 0x04
        head[base + 0xC4] = 0x15
        head[base + 0xC5] = 0x2A

        val parsed = SaveInfoCodec.parse(game, head, "game2.yw")

        assertEquals(20, parsed.playHours)
        assertEquals(30, parsed.playMinutes)
        assertEquals(543_210, parsed.money)
        assertEquals("ケータ", parsed.playerName)
        assertEquals(2025, parsed.saveYear)
        assertEquals(3, parsed.saveMonth)
        assertEquals(4, parsed.saveDay)
        assertEquals(21, parsed.saveHour)
        assertEquals(42, parsed.saveMinute)
    }

    @Test
    fun apply_writesPlayTimeToGameAndHead_andPreservesSeconds() {
        val game = ByteArray(0x9300)
        val head = ByteArray(0x4000)
        val base = 0x3400
        head[base + 0xC6] = 0x37

        val patched = SaveInfoCodec.apply(
            baseGameData = game,
            baseHeadData = head,
            sectionName = "game0.yw",
            info = SaveInfo(
                playHours = 1,
                playMinutes = 2,
                money = 999_999,
                playerName = "ジバニャン",
                saveYear = 2026,
                saveMonth = 12,
                saveDay = 31,
                saveHour = 23,
                saveMinute = 59,
            ),
        )

        // 3720 sec => 0x00000E88 (little-endian)
        assertEquals(0x88, patched.gameData[0x24].toInt() and 0xFF)
        assertEquals(0x0E, patched.gameData[0x25].toInt() and 0xFF)
        assertEquals(0x00, patched.gameData[0x26].toInt() and 0xFF)
        assertEquals(0x00, patched.gameData[0x27].toInt() and 0xFF)
        assertEquals(0x3F, patched.gameData[SaveInfoCodec.MONEY_OFFSET].toInt() and 0xFF)
        assertEquals(0x42, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 1].toInt() and 0xFF)
        assertEquals(0x0F, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 2].toInt() and 0xFF)
        assertEquals(0x00, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 3].toInt() and 0xFF)
        var nameEnd = SaveInfoCodec.PLAYER_NAME_OFFSET
        while (nameEnd < patched.gameData.size && patched.gameData[nameEnd].toInt() != 0) {
            nameEnd++
        }
        val actualName = patched.gameData
            .copyOfRange(SaveInfoCodec.PLAYER_NAME_OFFSET, nameEnd)
            .toString(Charsets.UTF_8)
        assertEquals("ジバニャン", actualName)

        assertEquals(0x88, patched.headData[base + 0x68].toInt() and 0xFF)
        assertEquals(0x0E, patched.headData[base + 0x69].toInt() and 0xFF)
        assertEquals(0x00, patched.headData[base + 0x6A].toInt() and 0xFF)
        assertEquals(0x00, patched.headData[base + 0x6B].toInt() and 0xFF)

        assertEquals(2026 and 0xFF, patched.headData[base + 0xC0].toInt() and 0xFF)
        assertEquals((2026 ushr 8) and 0xFF, patched.headData[base + 0xC1].toInt() and 0xFF)
        assertEquals(12, patched.headData[base + 0xC2].toInt() and 0xFF)
        assertEquals(31, patched.headData[base + 0xC3].toInt() and 0xFF)
        assertEquals(23, patched.headData[base + 0xC4].toInt() and 0xFF)
        assertEquals(59, patched.headData[base + 0xC5].toInt() and 0xFF)
        assertEquals(0x37, patched.headData[base + 0xC6].toInt() and 0xFF)
    }

    @Test
    fun apply_clampsMoneyToSafeMax() {
        val game = ByteArray(0x9300)
        val head = ByteArray(0x4000)

        val patched = SaveInfoCodec.apply(
            baseGameData = game,
            baseHeadData = head,
            sectionName = "game1.yw",
            info = SaveInfo(
                playHours = 0,
                playMinutes = 0,
                money = 9_999_999,
                playerName = "",
                saveYear = 2026,
                saveMonth = 1,
                saveDay = 1,
                saveHour = 0,
                saveMinute = 0,
            ),
        )

        assertEquals(0x3F, patched.gameData[SaveInfoCodec.MONEY_OFFSET].toInt() and 0xFF)
        assertEquals(0x42, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 1].toInt() and 0xFF)
        assertEquals(0x0F, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 2].toInt() and 0xFF)
        assertEquals(0x00, patched.gameData[SaveInfoCodec.MONEY_OFFSET + 3].toInt() and 0xFF)
    }
}

