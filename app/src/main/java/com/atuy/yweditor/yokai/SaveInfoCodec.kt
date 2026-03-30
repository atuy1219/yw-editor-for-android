package com.atuy.yweditor.yokai

import java.nio.charset.StandardCharsets

data class SaveInfo(
    val playHours: Int,
    val playMinutes: Int,
    val playerName: String,
)

object SaveInfoCodec {
    const val PLAY_TIME_OFFSET = 0x0024
    const val PLAYER_NAME_OFFSET = 0x0030
    const val PLAYER_NAME_MAX_BYTES = 24

    // The save region reserves 24 bytes including the trailing null byte.
    private const val PLAYER_NAME_PAYLOAD_MAX_BYTES = PLAYER_NAME_MAX_BYTES - 1

    fun parse(data: ByteArray): SaveInfo {
        val seconds = readPlaySeconds(data)
        val hours = (seconds / 3600L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val minutes = ((seconds % 3600L) / 60L).toInt()

        val name = readPlayerName(data)
        return SaveInfo(playHours = hours, playMinutes = minutes, playerName = name)
    }

    fun apply(baseData: ByteArray, info: SaveInfo): ByteArray {
        val out = baseData.copyOf()

        val hours = info.playHours.coerceAtLeast(0)
        val minutes = info.playMinutes.coerceIn(0, 59)
        val seconds = hours.toLong() * 3600L + minutes.toLong() * 60L
        writeIntLe(out, PLAY_TIME_OFFSET, seconds.coerceIn(0L, 0xFFFFFFFFL).toInt())

        val safeName = truncatePlayerName(info.playerName)
        val payload = safeName.toByteArray(StandardCharsets.UTF_8)
        val writable = (out.size - PLAYER_NAME_OFFSET).coerceAtLeast(0)
        if (writable <= 0) return out

        val regionSize = minOf(PLAYER_NAME_MAX_BYTES, writable)
        for (index in 0 until regionSize) {
            out[PLAYER_NAME_OFFSET + index] = 0
        }
        val copyLength = minOf(payload.size, regionSize - 1)
        if (copyLength > 0) {
            payload.copyInto(
                destination = out,
                destinationOffset = PLAYER_NAME_OFFSET,
                startIndex = 0,
                endIndex = copyLength,
            )
        }

        return out
    }

    fun isPlayerNameWithinLimit(name: String): Boolean {
        return name.toByteArray(StandardCharsets.UTF_8).size <= PLAYER_NAME_PAYLOAD_MAX_BYTES
    }

    fun truncatePlayerName(name: String): String {
        if (isPlayerNameWithinLimit(name)) return name

        val builder = StringBuilder()
        var currentBytes = 0
        for (ch in name) {
            val charBytes = ch.toString().toByteArray(StandardCharsets.UTF_8).size
            if (currentBytes + charBytes > PLAYER_NAME_PAYLOAD_MAX_BYTES) break
            builder.append(ch)
            currentBytes += charBytes
        }
        return builder.toString()
    }

    private fun readPlaySeconds(data: ByteArray): Long {
        if (PLAY_TIME_OFFSET + 4 > data.size) return 0
        return ((data[PLAY_TIME_OFFSET].toLong() and 0xFFL)) or
            ((data[PLAY_TIME_OFFSET + 1].toLong() and 0xFFL) shl 8) or
            ((data[PLAY_TIME_OFFSET + 2].toLong() and 0xFFL) shl 16) or
            ((data[PLAY_TIME_OFFSET + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readPlayerName(data: ByteArray): String {
        if (PLAYER_NAME_OFFSET >= data.size) return ""

        val limitExclusive = minOf(data.size, PLAYER_NAME_OFFSET + PLAYER_NAME_MAX_BYTES)
        var end = PLAYER_NAME_OFFSET
        while (end < limitExclusive && data[end].toInt() != 0) {
            end++
        }

        if (end <= PLAYER_NAME_OFFSET) return ""
        return data.copyOfRange(PLAYER_NAME_OFFSET, end).toString(StandardCharsets.UTF_8)
    }

    private fun writeIntLe(data: ByteArray, offset: Int, value: Int) {
        if (offset + 4 > data.size) return
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

