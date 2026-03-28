package com.atuy.yweditor.yokai

data class Stat5(
    val hp: Int,
    val power: Int,
    val spirit: Int,
    val defense: Int,
    val speed: Int,
) {
    fun update(index: Int, value: Int): Stat5 {
        return when (index) {
            0 -> copy(hp = value)
            1 -> copy(power = value)
            2 -> copy(spirit = value)
            3 -> copy(defense = value)
            4 -> copy(speed = value)
            else -> this
        }
    }

    fun values(): List<Int> = listOf(hp, power, spirit, defense, speed)
}

data class YokaiEntry(
    val slot: Int,
    val id: Long,
    val name: String,
    val level: Int,
    val iva: Stat5,
    val ivb1: Stat5,
    val ivb2: Stat5,
    val cb: Stat5,
)

data class MainSection(
    val name: String,
    val headerPos: Int,
    val offset: Int,
    val size: Int,
    val seed: Int,
    val decryptedData: ByteArray,
)

data class MainBinDecoded(
    val rawData: ByteArray,
    val sections: Map<String, MainSection>,
)

enum class StatGroup {
    IVA,
    IVB1,
    IVB2,
    CB,
}

