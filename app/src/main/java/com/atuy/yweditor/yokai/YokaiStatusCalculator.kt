package com.atuy.yweditor.yokai

object YokaiStatusCalculator {

    fun calculate(entry: YokaiEntry): Stat5? {
        val base = entry.baseStats ?: return null
        val grow = entry.growPattern ?: return null

        return Stat5(
            hp = calcStatus(
                growPattern = grow.hp,
                bs = base.hp,
                ivA = entry.iva.hp,
                ivB1 = entry.ivb1.hp,
                ivB2 = entry.ivb2.hp,
                cb = entry.cb.hp,
                level = entry.level,
                isHp = true,
            ),
            power = calcStatus(grow.power, base.power, entry.iva.power, entry.ivb1.power, entry.ivb2.power, entry.cb.power, entry.level, false),
            spirit = calcStatus(grow.spirit, base.spirit, entry.iva.spirit, entry.ivb1.spirit, entry.ivb2.spirit, entry.cb.spirit, entry.level, false),
            defense = calcStatus(grow.defense, base.defense, entry.iva.defense, entry.ivb1.defense, entry.ivb2.defense, entry.cb.defense, entry.level, false),
            speed = calcStatus(grow.speed, base.speed, entry.iva.speed, entry.ivb1.speed, entry.ivb2.speed, entry.cb.speed, entry.level, false),
        )
    }

    private fun calcStatus(
        growPattern: Int,
        bs: Int,
        ivA: Int,
        ivB1: Int,
        ivB2: Int,
        cb: Int,
        level: Int,
        isHp: Boolean,
    ): Int {
        val lv = level.toFloat()
        var f23 = ((lv * 0.005050505f) + 1.0f) * cb
        var f11 = (bs + ivA + ivB1).toFloat()

        f23 += if (isHp) f11 else bs * 0.1f

        f11 += ivB2
        f11 *= if (isHp) 0.1f else 0.05f

        f23 += when (growPattern) {
            0 -> f11 * lv
            1 -> f11 * growthEarly(lv, cubicScale = 0.5f, accel = 0.25f)
            2 -> f11 * growthEarly(lv, cubicScale = 0.1f, accel = 0.45f)
            3 -> f11 * growthLate(lv, cubicScale = 0.1f, accel = 0.45f)
            4 -> f11 * growthLate(lv, cubicScale = 0.5f, accel = 0.25f)
            5 -> return bs
            else -> return 0
        }

        return kotlin.math.floor(f23.toDouble()).toInt()
    }

    private fun growthEarly(level: Float, cubicScale: Float, accel: Float): Float {
        val x = 98.0f - (level - 1.0f)
        return 99.0f - (x * x * x * cubicScale / 9604.0f + x * x * accel * 0.010204081f + x * accel)
    }

    private fun growthLate(level: Float, cubicScale: Float, accel: Float): Float {
        val x = level - 1.0f
        return 1.0f + (x * x * x * cubicScale / 9604.0f + x * x * accel * 0.010204081f + x * accel)
    }
}

