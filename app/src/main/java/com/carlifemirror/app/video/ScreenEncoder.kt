package com.carlifemirror.app.video

import android.content.Context
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.carlifemirror.app.carlife.AnnexB
import com.carlifemirror.app.util.Logx
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Захват экрана -> H.264 (Surface) -> Annex-B.
 * MediaProjection создаёт VirtualDisplay на SurfaceTexture, GL вписывает картинку
 * в кадр магнитолы, а MediaCodec кодирует результат.
 *
 * Режим кодировщика повторяет проверенный Headswap 2.6.0 / CarLife:
 *  - Baseline, без B-кадров, VBR 8 Мбит/с для 2240x976;
 *  - KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100 мс, чтобы на статичном экране
 *    магнитола продолжала получать кадры;
 *  - три уровня совместимости, если кодек не принимает дополнительные ключи;
 *  - CSD и все выходные буферы нормализуются в 4-байтные Annex-B старт-коды.
 */
class ScreenEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    val videoW: Int,
    val videoH: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(message: String, t: Throwable?)
    }

    private val baseBitrate = bitrate
    @Volatile private var requestedFps = fps.coerceIn(1, 60)
    @Volatile private var effectiveFps = requestedFps
    @Volatile private var currentBitrate = bitrate
    @Volatile private var bitrateTarget = bitrate
    /** Ограничение битрейта со стороны транспорта (Wi-Fi); USB не ограничивает. */
    @Volatile private var networkBitrateCap = Int.MAX_VALUE
    @Volatile private var thermalLevel = ThermalLevel.NORMAL
    private val thermalSource: ThermalSource = PhoneThermal(context)
    private val thermalThrottle = ThermalThrottle()
    private var thermalExec: ScheduledExecutorService? = null
    private var lastHeadroomAt = 0L
    private var headroom = Float.NaN
    private var lastBitrateStepAt = 0L

    @Volatile private var codec: MediaCodec? = null
    private var codecName = "—"
    private var inputSurface: Surface? = null
    @Volatile private var gl: GlLetterbox? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var csd: ByteArray? = null
    @Volatile private var frames = 0L
    @Volatile private var dropped = 0L

    val screenW: Int
    val screenH: Int
    private val captureW: Int
    private val captureH: Int
    private val densityDpi: Int

    init {
        val wm = context.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels.coerceAtLeast(2)
        screenH = dm.heightPixels.coerceAtLeast(2)
        densityDpi = dm.densityDpi
        // Захватываем ровно тот прямоугольник, который GL впишет в кадр:
        // при 720p/1080p VirtualDisplay меньше экрана, при 2K/своём размере —
        // родное разрешение. Пропорции экрана сохраняются, поэтому картинка
        // не растягивается, а лишнее место занимают чёрные поля.
        val srcAspect = screenW.toFloat() / screenH
        val dstAspect = videoW.toFloat() / videoH
        val fitW = if (srcAspect > dstAspect) videoW else (videoH * srcAspect).toInt()
        val fitH = if (srcAspect > dstAspect) (videoW / srcAspect).toInt() else videoH
        captureW = minOf(screenW, fitW.coerceAtLeast(2)) and 1.inv()
        captureH = minOf(screenH, fitH.coerceAtLeast(2)) and 1.inv()
    }

    fun start() {
        if (running) return
        refreshThermal(force = true)
        val c = MediaCodec.createEncoderByType(MIME)
        var surface: Surface? = null
        var usedLevel = -1
        try {
            var configured = false
            var lastError: Exception? = null
            for (level in 0..2) {
                try {
                    if (level > 0) c.reset()
                    c.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                    usedLevel = level
                    break
                } catch (e: Exception) {
                    lastError = e
                    Logx.w("Кодек не принял режим $level (${encoderMode(level)}): ${e.message}")
                }
            }
            if (!configured) {
                throw lastError ?: IllegalStateException("Ни один режим кодировщика не подошёл")
            }
            surface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            try { surface?.release() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = c.name
        inputSurface = surface
        val g = GlLetterbox(surface!!, videoW, videoH, screenW, screenH, captureW, captureH, effectiveFps) {
            Logx.i(it)
        }
        try {
            val capture = g.start()
            gl = g
            val display = projection.createVirtualDisplay(
                "CarLifeMirror",
                captureW,
                captureH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                capture,
                null,
                null,
            ) ?: throw IllegalStateException("VirtualDisplay не создан")
            virtualDisplay = display
        } catch (e: Exception) {
            try { g.stop() } catch (_: Exception) {}
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            gl = null
            codec = null
            inputSurface = null
            throw e
        }
        running = true
        drainThread = Thread({ drainLoop(c) }, "mirror-enc-drain").also { it.start() }
        thermalExec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mirror-thermal").also { it.isDaemon = true }
        }.also { exec ->
            exec.scheduleWithFixedDelay({ refreshThermal() }, 1, 1, TimeUnit.SECONDS)
        }
        Logx.i(
            "Видео: ${videoW}x$videoH @$effectiveFps/${requestedFps}, " +
                "${currentBitrate / 1000} кбит/с, захват ${captureW}x$captureH, кодек $codecName"
        )
        Logx.i("Кодек: режим $usedLevel (${encoderMode(usedLevel)}), повтор кадра ${REPEAT_AFTER_US / 1000} мс")
        if (usedLevel > 0) Logx.w("Кодек: включён совместимый режим $usedLevel")
    }

    /**
     * Уровни совместимости: 0 — полный набор, 1 — без realtime/low-latency,
     * 2 — только базовые ключи. Повтор кадра остаётся во всех режимах.
     */
    private fun format(level: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, videoW, videoH)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
        if (level <= 1) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // VBR выбран в Headswap 2.6.0: на переходах AA CBR давал заметные артефакты.
            f.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
        }
        if (level == 0) {
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= 30) {
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        return f
    }

    private fun encoderMode(level: Int): String = when (level) {
        0 -> "baseline+VBR+noB+repeat+realtime"
        1 -> "baseline+VBR+repeat"
        else -> "base+repeat"
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Logx.w("Не удалось запросить ключевой кадр: ${e.message}")
        }
    }

    fun setTargetFps(value: Int) {
        requestedFps = value.coerceIn(1, 60)
        applyEffectiveFps()
    }

    /** MediaProjection остановлен — оставляем кодировщик живым и рисуем чёрный. */
    fun showBlack() {
        gl?.showBlack()
    }

    /**
     * Ограничение битрейта для нестабильного Wi-Fi. В отличие от термо-
     * ограничения, его можно поднимать обратно по мере разгрузки канала.
     */
    @Synchronized
    fun setNetworkBitrateCap(capBps: Int) {
        val cap = capBps.coerceIn(MIN_NETWORK_BITRATE, baseBitrate)
        if (cap == networkBitrateCap) return
        networkBitrateCap = cap
        val thermalTarget = if (thermalLevel == ThermalLevel.NORMAL) {
            baseBitrate
        } else {
            thermalThrottle.capBitrate(baseBitrate)
        }
        bitrateTarget = minOf(thermalTarget, networkBitrateCap)
        if (!running) {
            currentBitrate = minOf(currentBitrate, bitrateTarget)
            return
        }
        if (currentBitrate > bitrateTarget) {
            currentBitrate = bitrateTarget
            applyBitrate(currentBitrate)
        }
        lastBitrateStepAt = 0L
        Logx.i(
            "Сеть: предел битрейта ${networkBitrateCap / 1000} кбит/с, " +
                "текущий ${currentBitrate / 1000} кбит/с"
        )
    }

    fun setPaused(value: Boolean) {
        gl?.setPaused(value)
    }

    private fun applyEffectiveFps() {
        val capped = thermalThrottle.capFps(requestedFps)
        if (capped != effectiveFps) {
            effectiveFps = capped
            gl?.setTargetFps(capped)
        }
    }

    private fun refreshThermal(force: Boolean = false) {
        if (!running && !force) return
        val now = SystemClock.elapsedRealtime()
        if (force || lastHeadroomAt == 0L || now - lastHeadroomAt >= HEADROOM_POLL_MS) {
            lastHeadroomAt = now
            headroom = thermalSource.headroom()
        }
        val status = thermalSource.status()
        val changed = thermalThrottle.update(now, status, headroom)
        if (force) {
            applyThermalLevel(thermalThrottle.level, "старт", true, now)
            return
        }
        if (changed != null) {
            applyThermalLevel(
                changed,
                "статус ${thermalStatusName(status)}, headroom ${headroomText()}",
                false,
                now,
            )
            return
        }
        rampBitrateIfNeeded(now)
    }

    private fun applyThermalLevel(level: ThermalLevel, why: String, force: Boolean, now: Long) {
        val oldFps = effectiveFps
        val oldBps = currentBitrate
        thermalLevel = level
        applyEffectiveFps()
        bitrateTarget = minOf(
            if (level == ThermalLevel.NORMAL) baseBitrate else thermalThrottle.capBitrate(baseBitrate),
            networkBitrateCap,
        )
        // Вниз — сразу. Вверх — только через rampBitrateIfNeeded(), чтобы не
        // вернуть 8 Мбит/с мгновенно после остывания.
        if (level != ThermalLevel.NORMAL || force) {
            currentBitrate = bitrateTarget
            applyBitrate(currentBitrate)
        }
        if (force || oldFps != effectiveFps || oldBps != currentBitrate) {
            Logx.i(
                "Термо: ${level.name} ($why), fps $oldFps->$effectiveFps, " +
                    "битрейт ${oldBps / 1000}->${currentBitrate / 1000} кбит/с"
            )
        }
        lastBitrateStepAt = now
    }

    private fun rampBitrateIfNeeded(now: Long) {
        if (thermalLevel != ThermalLevel.NORMAL) return
        if (currentBitrate >= bitrateTarget) return
        if (now - lastBitrateStepAt < BITRATE_STEP_MS) return
        val next = minOf(bitrateTarget, maxOf(currentBitrate + 1, (currentBitrate * 1.1).toInt()))
        currentBitrate = next
        applyBitrate(next)
        lastBitrateStepAt = now
        Logx.i("Термо: битрейт ${next / 1000} кбит/с (возврат к ${bitrateTarget / 1000})")
    }

    private fun applyBitrate(bps: Int) {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
        } catch (e: Exception) {
            Logx.w("Не удалось изменить битрейт: ${e.message}")
        }
    }

    private fun headroomText(): String =
        if (headroom.isNaN()) "н/д" else String.format(Locale.US, "%.2f", headroom)

    private fun thermalStatusName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "$status"
    }

    fun mapTouch(x: Int, y: Int): PointF? = gl?.mapTouch(x, y)

    val frameCount: Long get() = frames
    val droppedCount: Long get() = dropped
    val currentFps: Int get() = effectiveFps
    val currentBitrateBps: Int get() = currentBitrate
    val thermalLevelName: String get() = thermalLevel.name
    val thermalMaxLevelName: String get() = thermalThrottle.maxLevel.name
    val throttledMs: Long get() = thermalThrottle.throttledMs

    fun stop() {
        if (!running && codec == null) return
        running = false
        try { thermalExec?.shutdownNow() } catch (_: Exception) {}
        thermalExec = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { gl?.stop() } catch (_: Exception) {}
        gl = null
        if (drainThread !== Thread.currentThread()) {
            try { drainThread?.join(1200) } catch (_: InterruptedException) {}
        }
        drainThread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        Logx.i(
            "Видео остановлено: кадров $frames, отброшено $dropped, " +
                "кодек $codecName, термо ${thermalLevel.name}"
        )
    }

    private fun emitConfig(data: ByteArray) {
        val normalized = AnnexB.normalize(data)
        if (csd?.contentEquals(normalized) == true) return
        csd = normalized
        listener.onConfig(normalized)
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                when (val idx = c.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val a = f.getByteBuffer("csd-0")?.let { b ->
                            ByteArray(b.remaining()).also { b.get(it) }
                        }
                        val b = f.getByteBuffer("csd-1")?.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                        val joined = when {
                            a == null && b == null -> null
                            a == null -> b
                            b == null -> a
                            else -> a + b
                        }
                        if (joined != null) emitConfig(joined)
                    }
                    else -> if (idx >= 0) {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                emitConfig(data)
                            } else {
                                val normalized = AnnexB.normalize(data)
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                    AnnexB.nalTypes(normalized).contains(5)
                                val out = if (key) {
                                    val cfg = csd
                                    if (cfg != null && !AnnexB.hasSpsAndPps(normalized)) cfg + normalized
                                    else normalized
                                } else {
                                    normalized
                                }
                                frames++
                                listener.onFrame(out, key)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                dropped++
                listener.onError("Ошибка кодирования видео", e)
            }
        }
    }

    companion object {
        const val MIME = "video/avc"

        /** Как KEY_REPEAT_PREVIOUS_FRAME_AFTER у Baidu/Headswap: 100 мс. */
        const val REPEAT_AFTER_US = 100_000L

        private const val HEADROOM_POLL_MS = 10_000L
        private const val BITRATE_STEP_MS = 10_000L
        private const val MIN_NETWORK_BITRATE = 2_000_000
    }
}
