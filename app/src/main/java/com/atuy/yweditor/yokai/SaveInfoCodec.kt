package com.atuy.yweditor.yokai

data class SaveInfo(
    val playHours: Int,
    val playMinutes: Int,
    val money: Int,
    val playerName: String,
    val saveYear: Int,
    val saveMonth: Int,
    val saveDay: Int,
    val saveHour: Int,
    val saveMinute: Int,
)

data class SaveInfoWriteResult(
    val gameData: ByteArray,
    val headData: ByteArray,
)

object SaveInfoCodec {
    const val PLAY_TIME_GAME_OFFSET = 0x0024
    const val MONEY_OFFSET = 0x9224
    const val MONEY_MAX = 999_999
    const val PLAYER_NAME_OFFSET = 0x0030
    const val PLAYER_NAME_MAX_BYTES = 24
    private const val PLAY_TIME_SUMMARY_RELATIVE_OFFSET = 0x68
    private const val SAVE_DATE_RELATIVE_OFFSET = 0xC0
    private const val PLAYER_NAME_PAYLOAD_MAX_BYTES = PLAYER_NAME_MAX_BYTES - 1

    fun slotBaseOffset(sectionName: String): Int {
        return when (sectionName) {
            "game1.yw" -> 0x3100
            "game2.yw" -> 0x3200
            "game3.yw" -> 0x3300
            "game0.yw" -> 0x3400
            else -> error("未対応スロット: $sectionName")
        }
    }

    fun parse(gameData: ByteArray, headData: ByteArray, sectionName: String): SaveInfo {
        val base = slotBaseOffset(sectionName)
        val seconds = readUInt32Le(gameData, PLAY_TIME_GAME_OFFSET)
        val hours = (seconds / 3600L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val minutes = ((seconds % 3600L) / 60L).toInt()
        val money = readUInt32Le(gameData, MONEY_OFFSET).coerceIn(0L, MONEY_MAX.toLong()).toInt()
        val playerName = readPlayerName(gameData)

        val year = readUInt16Le(headData, base + SAVE_DATE_RELATIVE_OFFSET)
        val month = readUInt8(headData, base + SAVE_DATE_RELATIVE_OFFSET + 0x02)
        val day = readUInt8(headData, base + SAVE_DATE_RELATIVE_OFFSET + 0x03)
        val saveHour = readUInt8(headData, base + SAVE_DATE_RELATIVE_OFFSET + 0x04)
        val saveMinute = readUInt8(headData, base + SAVE_DATE_RELATIVE_OFFSET + 0x05)

        return SaveInfo(
            playHours = hours,
            playMinutes = minutes,
            money = money,
            playerName = playerName,
            saveYear = year,
            saveMonth = month,
            saveDay = day,
            saveHour = saveHour,
            saveMinute = saveMinute,
        )
    }

    fun apply(
        baseGameData: ByteArray,
        baseHeadData: ByteArray,
        sectionName: String,
        info: SaveInfo,
    ): SaveInfoWriteResult {
        val gameOut = baseGameData.copyOf()
        val headOut = baseHeadData.copyOf()
        val base = slotBaseOffset(sectionName)

        val hours = info.playHours.coerceAtLeast(0)
        val minutes = info.playMinutes.coerceIn(0, 59)
        val seconds = (hours.toLong() * 3600L + minutes.toLong() * 60L).coerceIn(0L, 0xFFFFFFFFL)
        val money = info.money.coerceIn(0, MONEY_MAX)

        writeUInt32Le(gameOut, PLAY_TIME_GAME_OFFSET, seconds)
        writeUInt32Le(gameOut, MONEY_OFFSET, money.toLong())
        writePlayerName(gameOut, truncatePlayerName(info.playerName))
        writeUInt32Le(headOut, base + PLAY_TIME_SUMMARY_RELATIVE_OFFSET, seconds)

        writeUInt16Le(headOut, base + SAVE_DATE_RELATIVE_OFFSET, info.saveYear.coerceIn(0, 0xFFFF))
        writeUInt8(headOut, base + SAVE_DATE_RELATIVE_OFFSET + 0x02, info.saveMonth.coerceIn(1, 12))
        writeUInt8(headOut, base + SAVE_DATE_RELATIVE_OFFSET + 0x03, info.saveDay.coerceIn(1, 31))
        writeUInt8(headOut, base + SAVE_DATE_RELATIVE_OFFSET + 0x04, info.saveHour.coerceIn(0, 23))
        writeUInt8(headOut, base + SAVE_DATE_RELATIVE_OFFSET + 0x05, info.saveMinute.coerceIn(0, 59))
        // base+0xC6 (seconds) is intentionally preserved.

        return SaveInfoWriteResult(gameData = gameOut, headData = headOut)
    }

    fun isPlayerNameWithinLimit(name: String): Boolean {
        return name.toByteArray(Charsets.UTF_8).size <= PLAYER_NAME_PAYLOAD_MAX_BYTES
    }

    fun truncatePlayerName(name: String): String {
        if (isPlayerNameWithinLimit(name)) return name

        val builder = StringBuilder()
        var currentBytes = 0
        for (ch in name) {
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (currentBytes + charBytes > PLAYER_NAME_PAYLOAD_MAX_BYTES) break
            builder.append(ch)
            currentBytes += charBytes
        }
        return builder.toString()
    }

    private fun readUInt8(data: ByteArray, offset: Int): Int {
        if (offset !in data.indices) return 0
        return data[offset].toInt() and 0xFF
    }

    private fun readUInt16Le(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32Le(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return ((data[offset].toLong() and 0xFFL)) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readPlayerName(data: ByteArray): String {
        if (PLAYER_NAME_OFFSET >= data.size) return ""

        val limitExclusive = minOf(data.size, PLAYER_NAME_OFFSET + PLAYER_NAME_MAX_BYTES)
        var end = PLAYER_NAME_OFFSET
        while (end < limitExclusive && data[end].toInt() != 0) {
            end++
        }

        if (end <= PLAYER_NAME_OFFSET) return ""
        return data.copyOfRange(PLAYER_NAME_OFFSET, end).toString(Charsets.UTF_8)
    }

    private fun writeUInt8(data: ByteArray, offset: Int, value: Int) {
        if (offset !in data.indices) return
        data[offset] = (value and 0xFF).toByte()
    }

    private fun writeUInt16Le(data: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= data.size) return
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt32Le(data: ByteArray, offset: Int, value: Long) {
        if (offset + 3 >= data.size) return
        val v = value.toInt()
        data[offset] = (v and 0xFF).toByte()
        data[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writePlayerName(data: ByteArray, name: String) {
        val writable = (data.size - PLAYER_NAME_OFFSET).coerceAtLeast(0)
        if (writable <= 0) return

        val regionSize = minOf(PLAYER_NAME_MAX_BYTES, writable)
        for (index in 0 until regionSize) {
            data[PLAYER_NAME_OFFSET + index] = 0
        }

        val payload = name.toByteArray(Charsets.UTF_8)
        val copyLength = minOf(payload.size, regionSize - 1)
        if (copyLength > 0) {
            payload.copyInto(
                destination = data,
                destinationOffset = PLAYER_NAME_OFFSET,
                startIndex = 0,
                endIndex = copyLength,
            )
        }
    }
}

