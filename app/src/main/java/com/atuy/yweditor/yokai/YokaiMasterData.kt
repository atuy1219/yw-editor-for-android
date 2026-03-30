package com.atuy.yweditor.yokai

import android.content.Context
import com.atuy.yweditor.R

data class YokaiMasterDetail(
    val baseStats: Stat5,
    val growPattern: Stat5,
    val yokaiClass: Int,
)

data class YokaiAttitude(
    val id: Int,
    val name: String,
)

data class YokaiMasterData(
    val nameById: Map<Long, String>,
    val detailById: Map<Long, YokaiMasterDetail>,
    val attitudes: List<YokaiAttitude>,
) {
    companion object {
        val EMPTY = YokaiMasterData(
            nameById = emptyMap(),
            detailById = emptyMap(),
            attitudes = emptyList(),
        )
    }
}

object YokaiMasterLoader {

    private val xmlItemRegex = Regex("""<item\s+id="(\d+)"\s+name="([^"]+)"""")
    private val jsEntryRegex = Regex(
        pattern = """\{"num":\s*(\d+),.*?"bs":\s*\[(\d+),\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\],\s*"growPat":\s*\[(\d+),\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\].*?"class":\s*(\d+)""",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val fixedAttitudes = listOf(
        YokaiAttitude(1, "短気"),
        YokaiAttitude(2, "れいせい"),
        YokaiAttitude(3, "しんちょう"),
        YokaiAttitude(4, "やさしい"),
        YokaiAttitude(5, "いやらしい"),
        YokaiAttitude(6, "協力的"),
        YokaiAttitude(7, "荒くれ"),
        YokaiAttitude(8, "ずのう的"),
        YokaiAttitude(9, "動じない"),
        YokaiAttitude(10, "情け深い"),
        YokaiAttitude(11, "非道"),
        YokaiAttitude(12, "けんしん的"),
    )

    fun load(context: Context): YokaiMasterData {
        return runCatching {
            val xmlText = context.resources.openRawResource(R.raw.yo_kai_ja)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val jsText = context.resources.openRawResource(R.raw.yo_kai)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val ordered = mutableListOf<Pair<Long, String>>()
            xmlItemRegex.findAll(xmlText).forEach { match ->
                val id = match.groupValues[1].toLongOrNull() ?: return@forEach
                val name = match.groupValues[2]
                ordered.add(id to name)
            }

            val nameById = ordered.toMap()
            val numberById = ordered.mapIndexed { index, pair ->
                pair.first to (index + 1)
            }.toMap()

            val detailByNumber = mutableMapOf<Int, YokaiMasterDetail>()
            jsEntryRegex.findAll(jsText).forEach { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return@forEach
                val hp = match.groupValues[2].toIntOrNull() ?: return@forEach
                val power = match.groupValues[3].toIntOrNull() ?: return@forEach
                val spirit = match.groupValues[4].toIntOrNull() ?: return@forEach
                val defense = match.groupValues[5].toIntOrNull() ?: return@forEach
                val speed = match.groupValues[6].toIntOrNull() ?: return@forEach
                val growHp = match.groupValues[7].toIntOrNull() ?: return@forEach
                val growPower = match.groupValues[8].toIntOrNull() ?: return@forEach
                val growSpirit = match.groupValues[9].toIntOrNull() ?: return@forEach
                val growDefense = match.groupValues[10].toIntOrNull() ?: return@forEach
                val growSpeed = match.groupValues[11].toIntOrNull() ?: return@forEach
                val yokaiClass = match.groupValues[12].toIntOrNull() ?: return@forEach

                detailByNumber[num] = YokaiMasterDetail(
                    baseStats = Stat5(
                        hp = hp,
                        power = power,
                        spirit = spirit,
                        defense = defense,
                        speed = speed,
                    ),
                    growPattern = Stat5(
                        hp = growHp,
                        power = growPower,
                        spirit = growSpirit,
                        defense = growDefense,
                        speed = growSpeed,
                    ),
                    yokaiClass = yokaiClass,
                )
            }

            val detailById = numberById.mapNotNull { (id, num) ->
                detailByNumber[num]?.let { id to it }
            }.toMap()

            YokaiMasterData(nameById = nameById, detailById = detailById, attitudes = fixedAttitudes)
        }.getOrElse {
            YokaiMasterData.EMPTY
        }
    }
}

