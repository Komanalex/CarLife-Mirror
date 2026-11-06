package com.carlifemirror.app.link

/**
 * Возможности транспорта CarLife.
 * USB AOA: [chan u32][len u32] + inner. Сейчас поддерживается только USB.
 */
class TransportCaps(
    val kind: String,
    val outerHeader: Boolean,
    val separateSockets: Boolean,
    val nativeDetach: Boolean,
) {
    companion object {
        val USB_AOA = TransportCaps("usb-aoa", outerHeader = true, separateSockets = false, nativeDetach = true)
        val WIRELESS_TCP = TransportCaps("tcp6", outerHeader = false, separateSockets = true, nativeDetach = false)
    }
}

/** Транспорт CarLife: обмен уже разобранными inner-сообщениями. */
interface CarTransport {
    val caps: TransportCaps
    val label: String

    fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    )

    fun send(chan: Int, inner: ByteArray): Boolean

    /** Неблокирующая отправка для аудио: при переполнении пакет отбрасывается. */
    fun sendDropIfFull(chan: Int, inner: ByteArray): Boolean = send(chan, inner)

    /** Размер куска тела при записи во внешний поток (USB). */
    fun setWriteChunkSize(bytes: Int) {}

    fun close()

    val rxBytes: Long
    /** Байты, реально записанные в транспорт (для телеметрии USB/Wi-Fi). */
    val txBytes: Long
    fun queueSize(chan: Int): Int
    val queuedVideoBytes: Long
    val queuedVideoFrames: Int
    val drainBytesPerSec: Double

    /** Сбрасывает окно измерения скорости USB после паузы. */
    fun resetDrainStats() {}
}

/** Оценка скорости записи в USB за последние 2 секунды. */
class DrainMeter(private val windowMs: Long = 2000) {
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var sum = 0L
    @Volatile var bytesPerSec: Double = 0.0
        private set

    @Synchronized
    fun onWrite(nowMs: Long, video: Boolean, bytes: Int) {
        if (!video) return
        events.addLast(nowMs to bytes)
        sum += bytes
        while (events.isNotEmpty() && nowMs - events.first().first > windowMs) {
            sum -= events.removeFirst().second
        }
        val seconds = windowMs / 1000.0
        bytesPerSec = if (seconds > 0) sum / seconds else 0.0
    }

    @Synchronized
    fun reset() {
        events.clear()
        sum = 0L
        bytesPerSec = 0.0
    }
}
