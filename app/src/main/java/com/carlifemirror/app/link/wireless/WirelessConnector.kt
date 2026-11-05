package com.carlifemirror.app.link.wireless

import android.content.Context
import com.carlifemirror.app.link.CarTransport
import com.carlifemirror.app.util.Logx
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Поднимает беспроводной CarLife сразу двумя путями:
 * - точка доступа/Wi-Fi-сеть: UDP-маяк 7999 и шесть TCP-портов;
 * - Bluetooth RFCOMM + Wi-Fi Direct, если магнитола сама инициирует P2P.
 *
 * Оба пути ведут в один [Tcp6Listener]. Когда ГУ открыла все каналы,
 * возвращается [MultiSocketTransport], и дальше работает обычная сессия
 * CarLife: видео, звук, касания, кнопки руля и split-экран.
 */
class WirelessConnector(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onReady: (CarTransport) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var listener: Tcp6Listener? = null
    @Volatile private var beacon: HotspotBeacon? = null
    @Volatile private var bluetooth: CarlifeBtServer? = null
    @Volatile private var transport: CarTransport? = null
    @Volatile private var wifiState = "Wi-Fi: готовлю каналы"
    @Volatile private var bluetoothState = ""

    fun start() {
        if (!running.compareAndSet(false, true)) return
        emit("Wi-Fi: готовлю каналы")
        thread = Thread({ run() }, "wireless-link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop(reason: String) {
        if (!running.getAndSet(false)) {
            try { transport?.close() } catch (_: Exception) {}
            return
        }
        Logx.i("Wi-Fi: остановка ($reason)")
        val pending = listener
        listener = null
        try { pending?.abort() } catch (_: Exception) {}
        try { beacon?.stop(reason) } catch (_: Exception) {}
        beacon = null
        try { bluetooth?.stop(reason) } catch (_: Exception) {}
        bluetooth = null
        if (!ready.get()) {
            try { transport?.close() } catch (_: Exception) {}
        }
        transport = null
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val tcpListener: Tcp6Listener
        try {
            tcpListener = Tcp6Listener(
                InetAddress.getByName("0.0.0.0"),
                peerOk = { address -> isLocalPeer(address) },
                extraClose = {
                    beacon?.stop("сессия закрыта")
                    bluetooth?.stop("сессия закрыта")
                },
            ).open()
        } catch (e: Exception) {
            if (running.get()) {
                Logx.e("Wi-Fi: не удалось открыть TCP-каналы", e)
                fail("Не удалось открыть Wi-Fi-каналы: ${e.message ?: e.javaClass.simpleName}")
            }
            return
        }
        if (!running.get()) {
            tcpListener.abort()
            return
        }
        listener = tcpListener

        val hotspot = HotspotBeacon(
            onIfaces = { interfaces ->
                wifiState = if (interfaces.isEmpty()) {
                    "Wi-Fi: жду точку доступа или Wi-Fi Direct"
                } else {
                    "Wi-Fi: жду магнитолу (${interfaces.joinToString(", ") { it.name }})"
                }
                emitCombined()
            },
            onProblem = { text ->
                wifiState = text
                emitCombined()
            },
        )
        beacon = hotspot
        hotspot.start()

        val bt = CarlifeBtServer(
            appContext,
            onStatus = { text ->
                bluetoothState = text
                emitCombined()
            },
            onP2pBusy = {
                bluetoothState = "Wi-Fi Direct занят: выключите точку доступа телефона"
                emitCombined()
            },
            onWifi = { enabled ->
                if (!enabled) {
                    bluetoothState = "Wi-Fi Direct: включите Wi-Fi на телефоне"
                    emitCombined()
                }
            },
            onP2pLost = {
                bluetoothState = "Wi-Fi Direct: группа распалась, жду магнитолу"
                emitCombined()
            },
        )
        bluetooth = bt
        bt.start()
        emitCombined()

        try {
            val linked = tcpListener.await(WAIT_MS, "wifi")
            if (!running.get()) {
                linked.close()
                return
            }
            ready.set(true)
            transport = linked
            hotspot.stop("6/6")
            beacon = null
            Logx.i("Wi-Fi: каналы готовы, запускаю CarLife")
            onStatus("Wi-Fi: подключено, запускаю CarLife")
            onReady(linked)
        } catch (e: Exception) {
            // abort() при штатной остановке — не ошибка.
            if (running.get()) {
                Logx.w("Wi-Fi: не дождались магнитолы: ${e.message}")
                fail("Wi-Fi: магнитола не подключилась (${e.message ?: e.javaClass.simpleName})")
            }
        } finally {
            if (!ready.get()) {
                hotspot.stop("нет соединения")
                if (beacon === hotspot) beacon = null
                bt.stop("нет соединения")
                if (bluetooth === bt) bluetooth = null
                val current = listener
                if (current === tcpListener) listener = null
                tcpListener.abort()
            }
        }
    }

    private fun isLocalPeer(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            (bluetooth?.ownsPeer(address) == true)

    private fun emit(status: String) {
        try {
            onStatus(status)
        } catch (_: Exception) {
        }
    }

    private fun emitCombined() {
        val text = listOf(wifiState, bluetoothState)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        emit(text)
    }

    private fun fail(message: String) {
        running.set(false)
        emit(message)
        try {
            onError(message)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Тридцать минут: пользователь успевает включить точку доступа и открыть CarLife. */
        private const val WAIT_MS = 30 * 60 * 1000L
    }
}
