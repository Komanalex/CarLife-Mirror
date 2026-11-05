package com.carlifemirror.app.video

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Источник нагрева телефона: PowerManager в приложении, заглушка в тестах. */
interface ThermalSource {
    fun status(): Int
    fun headroom(): Float

    object NONE : ThermalSource {
        override fun status() = 0
        override fun headroom() = Float.NaN
    }
}

/** Реальные данные PowerManager. Headroom доступен с API 30. */
class PhoneThermal(context: Context) : ThermalSource {
    private val pm = context.getSystemService(PowerManager::class.java)

    override fun status(): Int = try {
        pm?.currentThermalStatus ?: 0
    } catch (_: Exception) {
        0
    }

    override fun headroom(): Float = if (Build.VERSION.SDK_INT < 30) {
        Float.NaN
    } else {
        try {
            pm?.getThermalHeadroom(HEADROOM_FORECAST_S) ?: Float.NaN
        } catch (_: Exception) {
            Float.NaN
        }
    }

    private companion object {
        const val HEADROOM_FORECAST_S = 10
    }
}

/**
 * Ступень разгрузки видео по нагреву. Значения повторяют Headswap 2.6.0:
 * WARM — 20 FPS и 75% битрейта, HOT — 15 FPS и 50%.
 */
enum class ThermalLevel(val fpsCap: Int?, val bitrateFraction: Double) {
    NORMAL(null, 1.0),
    WARM(20, 0.75),
    HOT(15, 0.5),
}

/**
 * Термотроттлинг с гистерезисом. Вниз переходим сразу, вверх — по одной
 * ступени и только после [relaxMs] стабильно более холодного состояния.
 */
class ThermalThrottle(private val relaxMs: Long = RELAX_MS) {
    @Volatile var level = ThermalLevel.NORMAL
        private set

    @Volatile var maxLevel = ThermalLevel.NORMAL
        private set
    @Volatile var throttledMs = 0L
        private set

    private var lowerSinceMs = NEVER
    private var lastMs = NEVER

    fun levelFor(status: Int, headroom: Float): ThermalLevel = when {
        status >= STATUS_SEVERE -> ThermalLevel.HOT
        status == STATUS_MODERATE -> ThermalLevel.WARM
        !headroom.isNaN() && headroom >= WARM_HEADROOM -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }

    /** Возвращает новую ступень, если она изменилась, иначе null. */
    fun update(nowMs: Long, status: Int, headroom: Float): ThermalLevel? {
        if (lastMs != NEVER && level != ThermalLevel.NORMAL) {
            throttledMs += maxOf(nowMs - lastMs, 0L)
        }
        lastMs = nowMs
        val want = levelFor(status, headroom)
        if (want > level) {
            level = want
            lowerSinceMs = NEVER
            if (want > maxLevel) maxLevel = want
            return want
        }
        if (want == level) {
            lowerSinceMs = NEVER
            return null
        }
        if (lowerSinceMs == NEVER) lowerSinceMs = nowMs
        if (nowMs - lowerSinceMs < relaxMs) return null

        level = ThermalLevel.entries[level.ordinal - 1]
        lowerSinceMs = if (want < level) nowMs else NEVER
        return level
    }

    fun capFps(fps: Int): Int = level.fpsCap?.let { minOf(fps, it) } ?: fps

    fun capBitrate(ceilingBps: Int): Int =
        maxOf(MIN_BITRATE_BPS, (ceilingBps * level.bitrateFraction).toInt())

    companion object {
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val WARM_HEADROOM = 0.85f
        const val RELAX_MS = 60_000L
        const val MIN_BITRATE_BPS = 800_000
        private const val NEVER = Long.MIN_VALUE
    }
}
