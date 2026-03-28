package com.atuy.yweditor.yokai

import java.io.IOException
import kotlin.text.Charsets

class MainBinCodec {

    private val filesInfo = listOf(
        "head.yw" to 0x40,
        "game0.yw" to 0x60,
        "game1.yw" to 0x80,
        "game2.yw" to 0xA0,
    )
    private val defaultPayloadBase = 0xC0

    private val crcTable: IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if ((c and 1) != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    private val primes: IntArray = generatePrimes(256)

    fun decode(mainBin: ByteArray): MainBinDecoded {
        if (mainBin.size < 0xC0) {
            throw IOException("main.bin が短すぎます")
        }

        val sections = mutableMapOf<String, MainSection>()
        for ((name, headerPos) in filesInfo) {
            val offset = readIntLe(mainBin, headerPos + 4)
            val size = readIntLe(mainBin, headerPos + 8)
            if (size <= 8) continue

            val payloadStart = defaultPayloadBase + offset
            val payloadEnd = payloadStart + size
            if (payloadStart < 0 || payloadEnd > mainBin.size) {
                throw IOException("$name の範囲が不正です")
            }

            val encryptedSize = size - 8
            val seed = readIntLe(mainBin, payloadStart + encryptedSize + 4)
            val encrypted = mainBin.copyOfRange(payloadStart, payloadStart + encryptedSize)
            val decrypted = cipherData(encrypted, seed)

            sections[name] = MainSection(
                name = name,
                headerPos = headerPos,
                offset = offset,
                size = size,
                seed = seed,
                decryptedData = decrypted,
            )
        }

        // 一部フォーマットでは game3.yw ヘッダが 0xC0、payload の基点が 0xE0 になるため、
        // ファイル名CRCが一致する場合のみ game3 を追加で解析する。
        tryDecodeGame3(mainBin)?.let { sections[it.name] = it }

        return MainBinDecoded(rawData = mainBin.copyOf(), sections = sections)
    }

    fun replaceSection(
        decoded: MainBinDecoded,
        targetName: String,
        newDecryptedData: ByteArray,
    ): ByteArray {
        val section = decoded.sections[targetName] ?: throw IOException("$targetName が存在しません")
        val encrypted = cipherData(newDecryptedData, section.seed)
        val expectedEncryptedSize = section.size - 8
        if (encrypted.size != expectedEncryptedSize) {
            throw IOException("$targetName のサイズが一致しません")
        }

        val out = decoded.rawData.copyOf()
        val payloadStart = if (section.headerPos == 0xC0) 0xE0 + section.offset else defaultPayloadBase + section.offset
        encrypted.copyInto(out, payloadStart)

        val crc = calcCrc32(encrypted)
        writeIntLe(out, payloadStart + encrypted.size, crc)
        writeIntLe(out, payloadStart + encrypted.size + 4, section.seed)

        val totalCrc = calcCrc32(out.copyOfRange(4, out.size))
        writeIntLe(out, 0, totalCrc)
        return out
    }

    private fun calcCrc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = crcTable[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    private fun generatePrimes(count: Int): IntArray {
        val list = ArrayList<Int>(count)
        var num = 3
        while (list.size < count) {
            var isPrime = true
            var i = 2
            while (i * i <= num) {
                if (num % i == 0) {
                    isPrime = false
                    break
                }
                i++
            }
            if (isPrime) list.add(num)
            num += 2
        }
        return list.toIntArray()
    }

    private fun makeSbox(seed: Int): IntArray {
        val box = IntArray(256) { it }
        if (seed == 0) return box

        var s0 = ((seed xor (seed ushr 30)) * 0x6C078965 + 1)
        var s1 = ((s0 xor (s0 ushr 30)) * 0x6C078965 + 2)
        var s2 = ((s1 xor (s1 ushr 30)) * 0x6C078965 + 3)
        var s3 = 0x03DF95B3

        repeat(4096) {
            val t = s0 xor (s0 shl 11)
            s0 = s1
            s1 = s2
            s2 = s3
            s3 = s3 xor (s3 ushr 19) xor t xor (t ushr 8)

            val idx1 = (s3 ushr 8) and 0xFF
            val idx2 = s3 and 0xFF
            if (idx1 != idx2) {
                val val1 = box[idx1]
                val val2 = box[idx2]
                // Python版と同じく、idxではなく「値をインデックス」にした swap。
                val tmp = box[val1]
                box[val1] = box[val2]
                box[val2] = tmp
            }
        }

        return box
    }

    private fun cipherData(data: ByteArray, seed: Int): ByteArray {
        val sbox = makeSbox(seed)
        val out = data.copyOf()
        var multiplier = 0

        for (i in out.indices) {
            val blockIdx = (i ushr 8) and 0xFF
            val byteIdx = i and 0xFF

            if (byteIdx == 0) {
                multiplier = primes[sbox[blockIdx]]
            }

            val keyIdx = (multiplier * (byteIdx + 1)) and 0xFF
            out[i] = (out[i].toInt() xor sbox[keyIdx]).toByte()
        }
        return out
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun tryDecodeGame3(mainBin: ByteArray): MainSection? {
        val headerPos = 0xC0
        val payloadBase = 0xE0
        if (mainBin.size < payloadBase) return null

        val expectedHash = calcCrc32("game3.yw".toByteArray(Charsets.UTF_8))
        val actualHash = readIntLe(mainBin, headerPos)
        if (actualHash != expectedHash) return null

        val offset = readIntLe(mainBin, headerPos + 4)
        val size = readIntLe(mainBin, headerPos + 8)
        if (size <= 8) return null

        val payloadStart = payloadBase + offset
        val payloadEnd = payloadStart + size
        if (payloadStart < payloadBase || payloadEnd > mainBin.size) {
            throw IOException("game3.yw の範囲が不正です")
        }

        val encryptedSize = size - 8
        val seed = readIntLe(mainBin, payloadStart + encryptedSize + 4)
        val encrypted = mainBin.copyOfRange(payloadStart, payloadStart + encryptedSize)
        val decrypted = cipherData(encrypted, seed)

        return MainSection(
            name = "game3.yw",
            headerPos = headerPos,
            offset = offset,
            size = size,
            seed = seed,
            decryptedData = decrypted,
        )
    }

    private fun writeIntLe(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

