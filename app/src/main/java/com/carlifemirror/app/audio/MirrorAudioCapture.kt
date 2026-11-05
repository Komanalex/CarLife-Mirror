package com.carlifemirror.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import com.carlifemirror.app.util.Logx

/**
 * Захват звука приложений через уже выданный MediaProjection.
 *
 * Это не микрофон: AudioPlaybackCaptureConfiguration отдаёт PCM того, что
 * приложения играют с usages MEDIA/GAME/UNKNOWN, а также подсказки навигатора
 * и ассистента (NAVIGATION_GUIDANCE/ASSISTANT/SONIFICATION). Поток уходит в
 * CarLife MEDIA (48 кГц, stereo, PCM16) — так же, как в референсном headswap.
 *
 * Голос навигатора раньше не попадал в захват: Google Maps и Яндекс.Навигатор
 * используют USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, а не USAGE_MEDIA.
 *
 * Требуется runtime-разрешение RECORD_AUDIO. Свой UID исключён, чтобы не
 * поймать собственный звук приложения.
 */
class MirrorAudioCapture(
    context: Context,
    private val projection: MediaProjection,
    private val onPcm: (ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var failure: String? = null
        private set

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        failure = null
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failure = "нет разрешения RECORD_AUDIO"
            Logx.w("Звук: $failure")
            return false
        }
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            failure = "AudioRecord.getMinBufferSize=$min"
            Logx.w("Звук: $failure")
            return false
        }
        val r = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                // Голос навигатора и ассистента: Google Maps/Яндекс говорят
                // с USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, звуковые сигналы —
                // с USAGE_ASSISTANCE_SONIFICATION.
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .excludeUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, BUFFER_BYTES))
                .build()
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            Logx.w("Звук: не удалось создать AudioRecord: $failure")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            failure = "AudioRecord state=${r.state}"
            Logx.w("Звук: $failure")
            try { r.release() } catch (_: Exception) {}
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            Logx.w("Звук: startRecording: $failure")
            try { r.release() } catch (_: Exception) {}
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            failure = "recordingState=${r.recordingState}"
            Logx.w("Звук: $failure")
            try { r.release() } catch (_: Exception) {}
            return false
        }
        record = r
        running = true
        thread = Thread({ loop(r) }, "carlife-audio").also {
            it.isDaemon = true
            it.start()
        }
        Logx.i("Звук: захват запущен ${SAMPLE_RATE} Гц, stereo, PCM16, buffer=${maxOf(min, BUFFER_BYTES)} Б")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running && record == null) return
        running = false
        val r = record
        record = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        thread = null
        Logx.i("Звук: захват остановлен")
    }

    private fun loop(r: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (running && record === r) {
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (!running || record !== r) break
                if (n <= 0) {
                    if (n < 0) {
                        Logx.w("Звук: AudioRecord.read=$n, останавливаю захват")
                        break
                    }
                    continue
                }
                val data = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                onPcm(data)
            }
        } catch (e: Exception) {
            if (running) {
                failure = e.message ?: e.javaClass.simpleName
                Logx.w("Звук: ошибка чтения: $failure")
            }
        } finally {
            if (record === r) {
                running = false
                record = null
                try { r.release() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITS = 16

        /**
         * 25,6 мс PCM, как у CarProjection. Меньше пакет — меньше буфер
         * AudioTrack на ГУ и меньше накопление задержки звука.
         */
        const val CHUNK_BYTES = 2_560
        private const val BUFFER_BYTES = 16_384
    }
}
