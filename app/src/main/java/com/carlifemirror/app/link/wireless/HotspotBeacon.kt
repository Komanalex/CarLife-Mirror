package com.carlifemirror.app.link.wireless

import com.carlifemirror.app.util.Logx
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Байты ветки B: маяк телефона и ответ на опрос ГУ. */
object BeaconCodec {
    const val BEACON_PORT = 7999
    const val PROBE_PORT = 8999

    /** Так телефон сообщает ГУ, что он готов принимать TCP-каналы. */
    val BEACON: ByteArray = "CarlifeHost".toByteArray(Charsets.US_ASCII)

    /** Необязательный ответ на диагностический опрос некоторых ГУ. */
    val READY: ByteArray =
        """{"carlifehost":"carlife","status":"ready"}""".toByteArray(Charsets.US_ASCII)

    fun isProbe(data: ByteArray, len: Int): Boolean =
        String(data, 0, len, Charsets.ISO_8859_1).contains("carlifehost")
}

/**
 * Ветка B беспроводного CarLife: телефон раздаёт точку доступа или находится
 * в одной Wi-Fi-сети с ГУ. Раз в 2 секунды отправляется UDP-маяк «CarlifeHost»
 * в broadcast каждого локального интерфейса, раз в 12 секунд — unicast по
 * адресам подсети. ГУ, получив маяк, сама открывает шесть TCP-каналов.
 */
class HotspotBeacon(
    private val ifaces: () -> List<LanIface> = { LanIface.scan() },
    private val intervalMs: Long = 2_000L,
    private val sweepMs: Long = 12_000L,
    private val beaconPort: Int = BeaconCodec.BEACON_PORT,
    private val probePort: Int = BeaconCodec.PROBE_PORT,
    private val onIfaces: (List<LanIface>) -> Unit = {},
    private val onProblem: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var sender: Thread? = null
    private var responder: Thread? = null
    @Volatile private var probeSocket: DatagramSocket? = null
    @Volatile private var known: Set<String>? = null

    @Volatile var sent = 0L
        private set
    @Volatile var failed = 0L
        private set
    @Volatile var probes = 0L
        private set

    @Volatile var current: List<LanIface> = emptyList()
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender = Thread({ sendLoop() }, "wl-beacon").apply { isDaemon = true; start() }
        responder = Thread({ probeLoop() }, "wl-probe").apply { isDaemon = true; start() }
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        try { probeSocket?.close() } catch (_: Exception) {}
        sender?.interrupt()
        responder?.interrupt()
        Logx.i("Wi-Fi маяк остановлен ($why): отправлено=$sent, ошибок=$failed, опросов=$probes")
    }

    private fun refresh(): List<LanIface> {
        val list = try {
            ifaces()
        } catch (e: Exception) {
            Logx.w("Wi-Fi: не удалось прочитать сетевые интерфейсы: ${e.message}")
            current
        }
        val keys = list.map { it.toString() }.toSet()
        if (keys != known) {
            val old = known.orEmpty()
            for (key in keys - old) Logx.i("Wi-Fi: интерфейс доступен $key")
            for (key in old - keys) Logx.i("Wi-Fi: интерфейс пропал $key")
            known = keys
            current = list
            onIfaces(list)
        }
        current = list
        return list
    }

    private fun sendLoop() {
        val firstOk = HashSet<String>()
        val firstError = HashSet<String>()
        var lastSweep = Long.MIN_VALUE / 2
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (running.get()) {
                    val list = refresh()
                    val sweep = System.currentTimeMillis() - lastSweep >= sweepMs
                    if (sweep) lastSweep = System.currentTimeMillis()
                    for (iface in list) {
                        val targets = ArrayList<InetAddress>()
                        targets += iface.broadcast
                        if (sweep && LanIface.sweepName(iface.name)) targets += iface.hosts()
                        for (target in targets) {
                            if (!running.get()) return
                            try {
                                socket.send(
                                    DatagramPacket(
                                        BeaconCodec.BEACON,
                                        BeaconCodec.BEACON.size,
                                        target,
                                        beaconPort,
                                    )
                                )
                                sent++
                                if (sent == 1L) {
                                    onProblem("Wi-Fi: жду магнитолу (${iface.name})")
                                }
                                if (firstOk.add(iface.name)) {
                                    Logx.i("Wi-Fi: маяк пошёл через ${iface.name} на ${target.hostAddress}:$beaconPort")
                                }
                            } catch (e: Exception) {
                                failed++
                                if (firstError.add(iface.name)) {
                                    Logx.w("Wi-Fi: маяк не отправился через ${iface.name}: ${e.message}")
                                    val denied = e is SecurityException ||
                                        e.message?.contains("permit", ignoreCase = true) == true
                                    if (sent == 0L && denied) {
                                        onProblem("Wi-Fi: нет доступа к локальной сети — разрешите его в настройках приложения")
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(intervalMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running.get()) Logx.w("Wi-Fi: отправка маяка остановлена: ${e.message}")
        }
    }

    private fun probeLoop() {
        try {
            val socket = DatagramSocket(null)
            probeSocket = socket
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(probePort))
            socket.soTimeout = 1_000
            Logx.i("Wi-Fi: слушаю опрос ГУ на UDP $probePort")
            val buffer = ByteArray(1500)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val hit = BeaconCodec.isProbe(packet.data, packet.length)
                probes++
                if (hit) {
                    try {
                        socket.send(
                            DatagramPacket(
                                BeaconCodec.READY,
                                BeaconCodec.READY.size,
                                packet.socketAddress,
                            )
                        )
                    } catch (_: Exception) {}
                }
                if (probes <= 5 || probes % 50L == 0L) {
                    Logx.i("Wi-Fi: опрос с ${packet.address.hostAddress}, ответ=${if (hit) "да" else "нет"}")
                }
            }
            socket.close()
        } catch (e: Exception) {
            if (running.get()) Logx.w("Wi-Fi: UDP $probePort недоступен: ${e.message}")
        }
    }
}
