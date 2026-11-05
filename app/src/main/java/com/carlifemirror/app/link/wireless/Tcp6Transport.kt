package com.carlifemirror.app.link.wireless

import android.os.SystemClock
import com.carlifemirror.app.carlife.Ch
import com.carlifemirror.app.carlife.InnerAssembler
import com.carlifemirror.app.carlife.Msg
import com.carlifemirror.app.carlife.Wire
import com.carlifemirror.app.link.CarTransport
import com.carlifemirror.app.link.DrainMeter
import com.carlifemirror.app.link.TransportCaps
import com.carlifemirror.app.util.Logx
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Порты беспроводного CarLife: ГУ подключается к телефону TCP-клиентом. */
object Tcp6Ports {
    val DEFAULT: Map<Int, Int> = linkedMapOf(
        Ch.CMD to 7240,
        Ch.VIDEO to 8240,
        Ch.MEDIA to 9240,
        Ch.TTS to 9241,
        Ch.VR to 9242,
        Ch.TOUCH to 9340,
    )

    /** Необязательный канал Data; без него связь работает. */
    const val DATA = 9440
}

class BadInnerHeader(message: String) : IOException(message)
class WirelessLinkException(message: String) : Exception(message)

/**
 * Сообщение из сокета одного канала без внешнего заголовка:
 * [длина u16/u32][резерв/время][тип u32] + тело.
 */
object InnerCodec {
    fun headerLength(chan: Int): Int = if (Ch.isCmdStyle(chan)) 8 else 12

    /** null — чистый EOF на границе сообщения; EOFException — обрыв внутри сообщения. */
    fun read(chan: Int, input: DataInputStream): ByteArray? {
        val hdrLen = headerLength(chan)
        val header = ByteArray(hdrLen)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        input.readFully(header, 1, hdrLen - 1)
        val cmdStyle = Ch.isCmdStyle(chan)
        val declared = if (cmdStyle) Wire.u16(header, 0) else Wire.u32(header, 0)
        val type = Wire.u32(header, if (cmdStyle) 4 else 8)
        val limit = if (cmdStyle) InnerAssembler.MAX_CMD_DECLARED else Wire.MAX_OUTER_LEN
        if (declared !in 0..limit) {
            throw BadInnerHeader(
                "длина $declared вне 0..$limit " +
                    "(тип ${Msg.hex(type)}, заголовок ${hex(header)})"
            )
        }
        // VW CNS3 иногда присылает VIDEO_HEARTBEAT в CMD-сокет с длиной 0.
        val cmdHeartbeat = chan == Ch.CMD && type == Msg.VIDEO_HEARTBEAT && declared == 0
        if (!cmdHeartbeat && type ushr 16 != chan) {
            throw BadInnerHeader(
                "тип ${Msg.hex(type)} не канала ${Ch.name(chan)}, " +
                    "длина $declared, заголовок ${hex(header)}"
            )
        }
        val inner = ByteArray(hdrLen + declared)
        System.arraycopy(header, 0, inner, 0, hdrLen)
        input.readFully(inner, hdrLen, declared)
        if (cmdHeartbeat) skipHeartbeatTail(input)
        return inner
    }

    /**
     * Если CMD-heartbeat пришёл 12-байтным видео-заголовком, после первых
     * восьми байт остаются четыре байта метки. Пропускаем их только когда
     * они уже есть в буфере, чтобы не блокировать чтение.
     */
    private fun skipHeartbeatTail(input: DataInputStream) {
        if (!input.markSupported() || input.available() < 12) return
        val probe = ByteArray(12)
        input.mark(12)
        input.readFully(probe)
        input.reset()
        fun isCmdType(type: Int) = type ushr 16 == Ch.CMD || type == Msg.VIDEO_HEARTBEAT
        if (!isCmdType(Wire.u32(probe, 4)) && isCmdType(Wire.u32(probe, 8))) {
            input.skipBytes(4)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
}

/**
 * Слушает шесть TCP-портов на 0.0.0.0. Первое подключение на каждый канал
 * побеждает; все каналы должны прийти с одного адреса. Лишние подключения
 * закрываются. Слушатель живёт до закрытия транспорта.
 */
class Tcp6Listener(
    private val bind: InetAddress,
    private val ports: Map<Int, Int> = Tcp6Ports.DEFAULT,
    private val dataPort: Int? = Tcp6Ports.DATA,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val peerOk: (InetAddress) -> Boolean = {
        it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress
    },
    /** Дополнительная очистка при закрытии транспорта: Bluetooth-сервер, маяк. */
    private val extraClose: () -> Unit = {},
) {
    private val lock = Object()
    private val servers = LinkedHashMap<Int, ServerSocket>()
    private val accepted = LinkedHashMap<Int, Socket>()
    private var dataSocket: Socket? = null
    private var peer: InetAddress? = null
    private var closed = false

    fun open(): Tcp6Listener {
        val all = LinkedHashMap(ports)
        if (dataPort != null) all[dataChannel] = dataPort
        try {
            for ((chan, port) in all) {
                val server = ServerSocket()
                server.reuseAddress = true
                try {
                    server.bind(InetSocketAddress(bind, port), 4)
                } catch (e: IOException) {
                    server.close()
                    Logx.w("Wi-Fi: порт ${channelName(chan)} $port не открылся: ${e.message}")
                    if (chan == dataChannel) continue
                    throw WirelessLinkException(
                        "порт ${channelName(chan)} $port занят: ${e.message}"
                    )
                }
                synchronized(lock) { servers[chan] = server }
                Logx.i(
                    "Wi-Fi: слушаю ${channelName(chan)} на " +
                        "${bind.hostAddress}:${server.localPort}"
                )
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        for ((chan, server) in synchronized(lock) { servers.toMap() }) {
            Thread({ acceptLoop(chan, server) }, "tcp6-${channelName(chan)}").apply {
                isDaemon = true
                start()
            }
        }
        return this
    }

    val peerAddress: InetAddress? get() = synchronized(lock) { peer }

    fun boundPort(chan: Int): Int = synchronized(lock) { servers[chan]?.localPort ?: -1 }

    fun await(timeoutMs: Long, label: String): MultiSocketTransport {
        val started = clock()
        synchronized(lock) {
            while (!closed && accepted.size < ports.size) {
                val left = timeoutMs - (clock() - started)
                if (left <= 0) break
                lock.wait(minOf(left, 250L))
            }
            if (closed) {
                dropAccepted()
                throw WirelessLinkException("слушатель закрыт до подключения всех каналов")
            }
            if (accepted.size < ports.size) {
                val missing = ports.filterKeys { it !in accepted }
                    .map { (chan, port) -> "${Ch.name(chan)}($port)" }
                val ready = accepted.size
                dropAccepted()
                closeLocked()
                throw WirelessLinkException(
                    "за ${timeoutMs / 1000} с подключилось $ready/${ports.size}, " +
                        "нет ${missing.joinToString(", ")}"
                )
            }
            Logx.i(
                "Wi-Fi: все ${ports.size}/${ports.size} каналов подключены " +
                    "(${label}, ГУ ${peer?.hostAddress})"
            )
            return MultiSocketTransport(
                LinkedHashMap(accepted),
                ports,
                "$label, ГУ ${peer?.hostAddress}",
                {
                    close()
                    try { extraClose() } catch (_: Exception) {}
                },
                clock,
            )
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        for (server in servers.values) quiet { server.close() }
        dataSocket?.let { quiet { it.close() } }
        lock.notifyAll()
    }

    private fun dropAccepted() {
        for (socket in accepted.values) quiet { socket.close() }
        accepted.clear()
    }

    fun abort() {
        synchronized(lock) {
            dropAccepted()
            closeLocked()
        }
    }

    private fun acceptLoop(chan: Int, server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            onAccept(chan, socket)
        }
    }

    private fun onAccept(chan: Int, socket: Socket) {
        val address = socket.inetAddress
        synchronized(lock) {
            val reject = when {
                closed -> "закрыт"
                chan == dataChannel && dataSocket != null -> "повтор"
                chan != dataChannel && accepted.containsKey(chan) -> "повтор"
                peer != null && address != peer -> "чужой адрес"
                peer == null && !peerOk(address) -> "не локальная сеть"
                else -> null
            }
            if (reject != null) {
                Logx.w(
                    "Wi-Fi: отклонено подключение ${channelName(chan)} " +
                        "от ${address.hostAddress} ($reject)"
                )
                quiet { socket.close() }
                return
            }
            if (peer == null) peer = address
            setOptions(socket)
            if (chan == Ch.VIDEO) {
                try {
                    socket.sendBufferSize = VIDEO_SNDBUF
                } catch (_: Exception) {
                }
            }
            if (chan == dataChannel) {
                dataSocket = socket
                Thread({ drainData(socket) }, "tcp6-data").apply {
                    isDaemon = true
                    start()
                }
                return
            }
            accepted[chan] = socket
            Logx.i(
                "Wi-Fi: ${channelName(chan)} подключён от ${address.hostAddress} " +
                    "(${accepted.size}/${ports.size})"
            )
            if (accepted.size == ports.size) lock.notifyAll()
        }
    }

    private fun drainData(socket: Socket) {
        val buffer = ByteArray(4096)
        var total = 0L
        try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
        Logx.i("Wi-Fi: канал Data закрыт, принято $total Б")
    }

    private fun channelName(chan: Int) =
        if (chan == dataChannel) "DATA" else Ch.name(chan)

    companion object {
        private const val dataChannel = 0
        private const val VIDEO_SNDBUF = 64 * 1024

        fun setOptions(socket: Socket) {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            try {
                socket.setSoLinger(true, 0)
            } catch (_: Exception) {
            }
        }

        internal inline fun quiet(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Шесть TCP-сокетов в формате Baidu: каждый канал в своём сокете, внешнего
 * заголовка нет. Читатель и писатель работают на канал. Очереди ограничены,
 * видео при переполнении отбрасывается и сессия запрашивает новый IDR.
 */
class MultiSocketTransport internal constructor(
    private val sockets: Map<Int, Socket>,
    private val ports: Map<Int, Int>,
    override val label: String,
    private val closeListener: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : CarTransport {

    override val caps = TransportCaps.WIRELESS_TCP

    private val queues = sockets.keys.associateWith {
        LinkedBlockingQueue<ByteArray>(QUEUE_LIMIT)
    }
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val drain = DrainMeter()
    private val closed = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()
    private val dispatchLock = Any()
    private val txLock = Any()
    private val rx = AtomicLong()
    private val startedAt = clock()
    @Volatile private var onClosedCallback: ((String) -> Unit)? = null
    @Volatile private var heartbeats = 0L
    @Volatile private var commandHeartbeats = 0L

    override val rxBytes: Long get() = rx.get()
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val drainBytesPerSec: Double get() = drain.bytesPerSec

    override fun queueSize(chan: Int): Int = queues[chan]?.size ?: 0

    override fun resetDrainStats() {
        drain.reset()
    }

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        onClosedCallback = onClosed
        for ((chan, socket) in sockets) {
            val name = Ch.name(chan)
            threads += Thread({ writeLoop(chan, socket, onWritten) }, "cl-w-$name")
            threads += Thread({ readLoop(chan, socket, sink) }, "cl-r-$name")
        }
        for (thread in threads) thread.start()
        Logx.i("Wi-Fi: транспорт запущен, сокетов=${sockets.size}")
    }

    override fun send(chan: Int, inner: ByteArray): Boolean {
        if (closed.get()) return false
        val queue = queues[chan] ?: return false
        if (chan == Ch.VIDEO) {
            if (!queue.offer(inner)) return false
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
            return true
        }
        return try {
            queue.offer(inner, 500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }

    override fun sendDropIfFull(chan: Int, inner: ByteArray): Boolean {
        if (closed.get()) return false
        val queue = queues[chan] ?: return false
        if (!queue.offer(inner)) return false
        if (chan == Ch.VIDEO) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Logx.i(
            "Wi-Fi: закрытие транспорта, " +
                "aliveMs=${clock() - startedAt}, rx=${rx.get()}, hb=$heartbeats/$commandHeartbeats"
        )
        closeAll()
    }

    private fun fail(chan: Int, why: String) {
        if (!closed.compareAndSet(false, true)) return
        val reason = "Wi-Fi ${Ch.name(chan)}(${ports[chan]}): $why"
        Logx.w("Wi-Fi: канал закрыт: $reason")
        closeAll()
        onClosedCallback?.invoke(reason)
    }

    private fun closeAll() {
        try {
            closeListener()
        } catch (_: Exception) {
        }
        for (socket in sockets.values) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        val current = Thread.currentThread()
        for (thread in threads) {
            if (thread !== current) thread.interrupt()
        }
    }

    private fun readLoop(chan: Int, socket: Socket, sink: (Int, ByteArray) -> Unit) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), READ_BUFFER))
            while (!closed.get()) {
                val inner = InnerCodec.read(chan, input)
                if (inner == null) {
                    fail(chan, "магнитола закрыла сокет")
                    return
                }
                rx.addAndGet(inner.size.toLong())
                val typeOffset = InnerCodec.headerLength(chan) - 4
                if ((chan == Ch.VIDEO || chan == Ch.CMD) &&
                    Wire.u32(inner, typeOffset) == Msg.VIDEO_HEARTBEAT
                ) {
                    if (chan == Ch.CMD) commandHeartbeats++ else heartbeats++
                    continue
                }
                synchronized(dispatchLock) { sink(chan, inner) }
            }
        } catch (e: BadInnerHeader) {
            fail(chan, "плохой заголовок: ${e.message}")
        } catch (e: EOFException) {
            fail(chan, "обрыв посреди сообщения")
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка чтения: ${e.message}")
        }
    }

    private fun writeLoop(chan: Int, socket: Socket, onWritten: (Int, ByteArray) -> Unit) {
        val queue = queues.getValue(chan)
        val video = chan == Ch.VIDEO
        try {
            val output = socket.getOutputStream()
            while (!closed.get()) {
                val inner = queue.take()
                if (video) {
                    videoBytes.addAndGet(-inner.size.toLong())
                    videoFrames.decrementAndGet()
                }
                output.write(inner)
                output.flush()
                if (video) {
                    drain.onWrite(clock(), true, inner.size)
                }
                synchronized(txLock) { onWritten(chan, inner) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка записи: ${e.message}")
        }
    }

    companion object {
        private const val READ_BUFFER = 64 * 1024
        private const val QUEUE_LIMIT = 240
    }
}
