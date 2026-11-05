package com.carlifemirror.app.link.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.carlifemirror.app.util.Logx
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ветка A беспроводного CarLife: магнитола сама подключается к телефону по
 * Bluetooth RFCOMM (SDP-имя `carlife`), сообщает реквизиты Wi-Fi Direct,
 * телефон входит в её P2P-группу и отдаёт свой IP. После этого ГУ открывает
 * шесть TCP-каналов на Tcp6Listener. Для обычной точки доступа Bluetooth
 * не обязателен: там работает [HotspotBeacon].
 */
class CarlifeBtServer(
    private val ctx: Context,
    private val onStatus: (String) -> Unit = {},
    private val onP2pBusy: () -> Unit = {},
    private val onWifi: (Boolean) -> Unit = {},
    private val onP2pLost: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val servers = ArrayList<BluetoothServerSocket>()
    private val lock = Any()
    private val txLock = Any()
    private var accepts = 0
    @Volatile private var active: BluetoothSocket? = null
    @Volatile private var p2pIp: String? = null
    @Volatile private var ipRequested = false
    @Volatile private var started = false
    private val p2p = P2pJoin(
        ctx,
        onStatus = onStatus,
        onBusy = onP2pBusy,
        onWifi = onWifi,
        onJoined = { ip -> onP2pJoined(ip) },
        onLost = { p2pIp = null; onP2pLost() },
    )

    fun ownsPeer(address: InetAddress): Boolean = p2p.owns(address)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running.get()) return
            running.set(true)
        }
        p2pIp = null
        ipRequested = false
        accepts = 0
        started = false
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return fail("Bluetooth недоступен")
            !hasPermission() -> return fail("нет разрешения Bluetooth")
            !adapter.isEnabled -> return fail("Bluetooth выключен")
        }
        var opened = 0
        for (uuid in UUID_TAGS) {
            for (secure in listOf(true, false)) {
                val socket = try {
                    if (secure) {
                        adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid.uuid)
                    } else {
                        adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, uuid.uuid)
                    }
                } catch (e: Exception) {
                    Logx.w(
                        "Bluetooth: не удалось слушать ${uuid.name} " +
                            "(${if (secure) "secure" else "insecure"}): ${e.message}"
                    )
                    continue
                }
                synchronized(lock) { servers += socket }
                opened++
                val tag = "${uuid.name}-${if (secure) "s" else "i"}"
                Logx.i("Bluetooth: слушаю carlife ($tag)")
                Thread({ acceptLoop(tag, socket) }, "bt-$tag").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        if (opened == 0) {
            fail("не удалось открыть RFCOMM")
            return
        }
        started = true
        onStatus("Bluetooth: жду магнитолу")
    }

    fun stop(why: String) {
        val list = synchronized(lock) {
            if (!running.getAndSet(false)) return
            servers.toList().also { servers.clear() }
        }
        for (server in list) {
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
        try {
            active?.close()
        } catch (_: IOException) {
        }
        active = null
        p2p.stop(why)
        if (started) Logx.i("Bluetooth: остановлен ($why), подключений=$accepts")
        started = false
    }

    private fun fail(why: String) {
        running.set(false)
        started = false
        Logx.w("Bluetooth: $why")
        onStatus("Bluetooth: $why")
    }

    private fun acceptLoop(tag: String, server: BluetoothServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            accepts++
            onStatus("Bluetooth: магнитола подключилась")
            talk(tag, socket)
        }
    }

    @SuppressLint("MissingPermission")
    private fun talk(tag: String, socket: BluetoothSocket) {
        val device = socket.remoteDevice
        val name = try {
            device?.name
        } catch (_: SecurityException) {
            null
        }
        Logx.i("Bluetooth: подключение $tag от ${name ?: "неизвестного устройства"}")
        val start = SystemClock.elapsedRealtime()
        val parser = BtFrameParser()
        var why = "?"
        var targetAsked = false
        active?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        active = socket
        ipRequested = false
        try {
            Thread.sleep(300)
            send(socket, BtMsg.MD_WIRELESS_INFO_REQUEST)
            val buffer = ByteArray(1024)
            val input = socket.inputStream
            while (running.get()) {
                val n = input.read(buffer)
                if (n < 0) {
                    why = "EOF"
                    break
                }
                for (frame in parser.feed(buffer, n)) {
                    Logx.i(
                        "Bluetooth: RX ${BtMsg.name(frame.type)} " +
                            "(${frame.body.size} Б)"
                    )
                    when (frame.type) {
                        BtMsg.HU_WIRELESS_INFO -> {
                            if (!targetAsked) {
                                targetAsked = true
                                send(socket, BtMsg.MD_TARGET_INFO_REQUEST)
                            }
                        }
                        BtMsg.HU_TARGET_INFO -> {
                            val target = P2pTarget.parse(frame.body)
                            if (target == null) {
                                Logx.w("Bluetooth: TARGET_INFO не разобран")
                            } else {
                                onStatus("Bluetooth: магнитола прислала реквизиты Wi-Fi Direct")
                                p2p.start(target)
                            }
                        }
                        BtMsg.HU_IP_REQUEST -> {
                            ipRequested = true
                            onStatus(
                                if (p2pIp == null) {
                                    "Bluetooth: магнитола просит IP, жду Wi-Fi Direct"
                                } else {
                                    "Bluetooth: отдаю магнитоле IP телефона"
                                }
                            )
                            sendIpIfReady()
                        }
                        BtMsg.HU_STATUS -> Unit
                        else -> Unit
                    }
                }
            }
            if (why == "?") why = "остановлено"
        } catch (e: Exception) {
            why = e.message ?: e.javaClass.simpleName
        } finally {
            if (active === socket) active = null
            try {
                socket.close()
            } catch (_: IOException) {
            }
            Logx.i(
                "Bluetooth: соединение закрыто ($why), " +
                    "${SystemClock.elapsedRealtime() - start} мс, пропущено=${parser.skipped}"
            )
        }
    }

    private fun send(socket: BluetoothSocket, type: Int, body: ByteArray? = null) {
        synchronized(txLock) {
            socket.outputStream.write(BtMsg.frame(type, body))
            socket.outputStream.flush()
        }
        Logx.i("Bluetooth: TX ${BtMsg.name(type)} (${body?.size ?: 0} Б)")
    }

    private fun onP2pJoined(ip: Inet4Address) {
        p2pIp = ip.hostAddress
        onStatus("Wi-Fi Direct: телефон в группе ГУ (${ip.hostAddress})")
        sendIpIfReady()
    }

    /** MD_WIFI_IP отправляется только после IP_REQUEST и появления IPv4. */
    private fun sendIpIfReady() {
        val ip = p2pIp ?: return
        val socket = active ?: return
        if (!ipRequested) return
        try {
            send(socket, BtMsg.MD_WIFI_IP, BtMsg.wifiIp(ip))
            ipRequested = false
            onStatus("Bluetooth: отдал магнитоле IP $ip, жду TCP")
        } catch (e: IOException) {
            Logx.w("Bluetooth: не удалось отправить IP: ${e.message}")
        }
    }

    companion object {
        const val SERVICE_NAME = "carlife"
        val UUIDS = listOf(
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "carlife" to UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )
        private val UUID_TAGS = UUIDS.map { UuidTag(it.first, it.second) }

        private data class UuidTag(val name: String, val uuid: UUID)
    }
}
