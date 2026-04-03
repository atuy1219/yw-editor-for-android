package com.atuy.yweditor.yokai

import java.nio.charset.Charset

class YokaiParser(
    private val masterData: YokaiMasterData = YokaiMasterData.EMPTY,
) {

    private val yokaiStart = 0x1D40
    private val yokaiSize = 0x7C
    private val maxYokai = 240

    fun parse(game0Data: ByteArray): List<YokaiEntry> {
        val result = mutableListOf<YokaiEntry>()

        for (i in 0 until maxYokai) {
            val base = yokaiStart + i * yokaiSize
            if (base + yokaiSize > game0Data.size) break

            val yokaiId = readIntLe(game0Data, base + 0x04).toLong() and 0xFFFFFFFFL
            if (yokaiId == 0L || yokaiId == 0xFFFFFFFFL) continue

            val rawName = game0Data.copyOfRange(base + 0x08, base + 0x08 + 36)
            val end = rawName.indexOf(0).let { if (it < 0) rawName.size else it }
            val name = rawName.copyOfRange(0, end).toString(Charset.forName("UTF-8"))

            val level = game0Data[base + 0x74].toInt() and 0xFF
            val attackLevel = game0Data[base + 0x4E].toInt() and 0xFF
            val techniqueLevel = game0Data[base + 0x52].toInt() and 0xFF
            val soultimateLevel = game0Data[base + 0x56].toInt() and 0xFF
            val attitudeId = game0Data[base + 0x75].toInt() and 0xFF
            val majimeCorrection = game0Data[base + 0x76].toInt() and 0xFF
            val stateFlags = game0Data[base + 0x77].toInt() and 0xFF

            val iva = Stat5(
                hp = game0Data[base + 0x60].toInt() and 0xFF,
                power = game0Data[base + 0x61].toInt() and 0xFF,
                spirit = game0Data[base + 0x62].toInt() and 0xFF,
                defense = game0Data[base + 0x63].toInt() and 0xFF,
                speed = game0Data[base + 0x64].toInt() and 0xFF,
            )

            val ivb = intArrayOf(
                game0Data[base + 0x65].toInt() and 0xFF,
                game0Data[base + 0x66].toInt() and 0xFF,
                game0Data[base + 0x67].toInt() and 0xFF,
                game0Data[base + 0x68].toInt() and 0xFF,
                game0Data[base + 0x69].toInt() and 0xFF,
            )

            val ivb1 = Stat5(
                hp = ivb[0] and 0x0F,
                power = ivb[1] and 0x0F,
                spirit = ivb[2] and 0x0F,
                defense = ivb[3] and 0x0F,
                speed = ivb[4] and 0x0F,
            )
            val ivb2 = Stat5(
                hp = (ivb[0] ushr 4) and 0x0F,
                power = (ivb[1] ushr 4) and 0x0F,
                spirit = (ivb[2] ushr 4) and 0x0F,
                defense = (ivb[3] ushr 4) and 0x0F,
                speed = (ivb[4] ushr 4) and 0x0F,
            )

            val cb = Stat5(
                hp = game0Data[base + 0x6A].toInt() and 0xFF,
                power = game0Data[base + 0x6B].toInt() and 0xFF,
                spirit = game0Data[base + 0x6C].toInt() and 0xFF,
                defense = game0Data[base + 0x6D].toInt() and 0xFF,
                speed = game0Data[base + 0x6E].toInt() and 0xFF,
            )

            val masterName = masterData.nameById[yokaiId]
            val detail = masterData.detailById[yokaiId]

            result.add(
                YokaiEntry(
                    slot = i,
                    id = yokaiId,
                    name = when {
                        !masterName.isNullOrBlank() -> masterName
                        name.isBlank() -> "(名前なし)"
                        else -> name
                    },
                    level = level,
                    attackLevel = attackLevel,
                    techniqueLevel = techniqueLevel,
                    soultimateLevel = soultimateLevel,
                    attitudeId = attitudeId,
                    majimeCorrection = majimeCorrection,
                    stateFlags = stateFlags,
                    iva = iva,
                    ivb1 = ivb1,
                    ivb2 = ivb2,
                    cb = cb,
                    baseStats = detail?.baseStats,
                    growPattern = detail?.growPattern,
                    yokaiClass = detail?.yokaiClass,
                )
            )
        }

        return result
    }

    fun applyEntries(baseGame0Data: ByteArray, entries: List<YokaiEntry>): ByteArray {
        val out = baseGame0Data.copyOf()

        for (entry in entries) {
            val base = yokaiStart + entry.slot * yokaiSize
            if (base + yokaiSize > out.size) continue

            writeIntLe(out, base + 0x04, entry.id.toInt())
            out[base + 0x74] = clamp(entry.level, 0, 255).toByte()
            out[base + 0x4E] = clamp(entry.attackLevel, 0, 99).toByte()
            out[base + 0x52] = clamp(entry.techniqueLevel, 0, 99).toByte()
            out[base + 0x56] = clamp(entry.soultimateLevel, 0, 99).toByte()
            out[base + 0x75] = clamp(entry.attitudeId, 0, 255).toByte()
            out[base + 0x76] = clamp(entry.majimeCorrection, 0, 255).toByte()
            out[base + 0x77] = clamp(entry.stateFlags, 0, 255).toByte()

            writeStat5(out, base + 0x60, entry.iva, max = 255)
            writePackedNibbleStat5(out, base + 0x65, entry.ivb1, entry.ivb2)
            writeStat5(out, base + 0x6A, entry.cb, max = 255)
        }

        return out
    }

    private fun writeStat5(data: ByteArray, offset: Int, stat: Stat5, max: Int) {
        val values = stat.values()
        for (i in values.indices) {
            data[offset + i] = clamp(values[i], 0, max).toByte()
        }
    }

    private fun writePackedNibbleStat5(data: ByteArray, offset: Int, low: Stat5, high: Stat5) {
        val lowValues = low.values()
        val highValues = high.values()
        for (i in lowValues.indices) {
            val l = clamp(lowValues[i], 0, 15)
            val h = clamp(highValues[i], 0, 15)
            data[offset + i] = ((h shl 4) or l).toByte()
        }
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLe(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }
}

