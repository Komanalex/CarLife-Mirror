package com.carlifemirror.app.link

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.carlifemirror.app.carlife.Ch
import com.carlifemirror.app.carlife.FrameParser
import com.carlifemirror.app.carlife.Hex
import com.carlifemirror.app.carlife.InnerAssembler
import com.carlifemirror.app.carlife.ProtocolDesync
import com.carlifemirror.app.carlife.Wire
import com.carlifemirror.app.util.Logx
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Потоковый транспорт CarLife через USB AOA: [chan u32][len u32] + inner.
 * Очередь ограничена, чтобы медленный USB не съел память. Видео при переполнении
 * отбрасывается, управляющие сообщения ждут.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    override val caps: TransportCaps,
    override val label: String,
    private val closeFd: () -> Unit = {},
) : CarTransport {

    private class Out(val chan: Int, val inner: ByteArray)

    private val queue = LinkedBlockingQueue<Out>(QUEUE_LIMIT)
    private val pending = AtomicIntegerArray(8)
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val drain = DrainMeter()
    private val parser = FrameParser()
    private val assembler = InnerAssembler()
    private val closed = AtomicBoolean(false)
    private var reader: Thread? = null
    private var writer: Thread? = null
    @Volatile private var rx = 0L
    private val tx = AtomicLong()
    @Volatile private var writeChunk = Wire.CHUNK

    override val rxBytes: Long get() = rx
    override val txBytes: Long get() = tx.get()

    override fun setWriteChunkSize(bytes: Int) {
        writeChunk = bytes.coerceIn(1024, Wire.CHUNK)
    }
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val drainBytesPerSec: Double get() = drain.bytesPerSec
    override fun queueSize(chan: Int): Int =
        if (chan in 0 until pending.length()) pending.get(chan) else queue.size

    override fun resetDrainStats() {
        drain.reset()
    }

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        writer = Thread({ writeLoop(onWritten, onClosed) }, "cl-writer").also { it.start() }
        reader = Thread({ readLoop(sink, onClosed) }, "cl-reader").also { it.start() }
    }

    override fun send(chan: Int, inner: ByteArray): Boolean {
        if (closed.get()) return false
        val video = chan == Ch.VIDEO
        if (video) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        val item = Out(chan, inner)
        val ok = queue.offer(item)
        if (!ok && video) {
            videoBytes.addAndGet(-inner.size.toLong())
            videoFrames.decrementAndGet()
            return false
        }
        if (!ok) {
            try {
                queue.put(item)
            } catch (_: InterruptedException) {
                return false
            }
        }
        if (chan in 0 until pending.length()) pending.incrementAndGet(chan)
        return true
    }

    override fun sendDropIfFull(chan: Int, inner: ByteArray): Boolean {
        if (closed.get()) return false
        if (!queue.offer(Out(chan, inner))) return false
        if (chan in 0 until pending.length()) pending.incrementAndGet(chan)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { closeFd() } catch (_: Exception) {}
        writer?.interrupt()
        reader?.interrupt()
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    private fun readLoop(sink: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        val buf = ByteArray(Wire.CHUNK)
        var resyncs = 0
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    if (!closed.get()) onClosed("USB: поток закрыт")
                    return
                }
                rx += n
                try {
                    parser.feed(buf, 0, n) { chan, inner -> assembler.feed(chan, inner, sink) }
                    resyncs = 0
                } catch (e: ProtocolDesync) {
                    val skipped = try {
                        parser.resync()
                    } catch (_: Exception) {
                        0
                    }
                    if (skipped > 0 && resyncs < 10) {
                        resyncs++
                        Logx.w(
                            "Протокол CarLife: рассинхрон, пропущено $skipped Б, " +
                                "восстанавливаю поток; начало=${Hex.compact(buf, minOf(n, 48))}",
                        )
                        continue
                    }
                    if (!closed.get()) {
                        Logx.e(
                            "Нарушение протокола CarLife: ${e.message}; " +
                                "сырые байты=${Hex.compact(buf, minOf(n, 96))}",
                        )
                        onClosed("Нарушение протокола CarLife: ${e.message}")
                    }
                    return
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                Logx.e("Ошибка чтения USB", e)
                onClosed("Ошибка чтения USB: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun writeLoop(onWritten: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        try {
            while (!closed.get()) {
                val o = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (o.chan in 0 until pending.length()) pending.decrementAndGet(o.chan)
                val video = o.chan == Ch.VIDEO
                if (video) {
                    videoBytes.addAndGet(-o.inner.size.toLong())
                    videoFrames.decrementAndGet()
                }
                val t0 = System.nanoTime()
                for (part in Wire.writeSequence(o.chan, o.inner, writeChunk)) {
                    output.write(part)
                    tx.addAndGet(part.size.toLong())
                }
                output.flush()
                drain.onWrite(SystemClock.elapsedRealtime(), video, o.inner.size)
                onWritten(o.chan, o.inner)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) {
                Logx.e("Ошибка записи USB", e)
                onClosed("Ошибка записи USB: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val QUEUE_LIMIT = 240

        fun fromPfd(pfd: ParcelFileDescriptor, label: String) = StreamTransport(
            FileInputStream(pfd.fileDescriptor),
            FileOutputStream(pfd.fileDescriptor),
            TransportCaps.USB_AOA,
            label,
            closeFd = { pfd.close() },
        )
    }
}
