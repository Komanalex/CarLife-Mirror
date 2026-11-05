package com.carlifemirror.app.link.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.carlifemirror.app.util.Logx
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketException

/**
 * Вход телефона в Wi-Fi Direct-группу магнитолы. Реквизиты группы приходят
 * по Bluetooth в TARGET_INFO. Если группа задана ssid/паролем, входим
 * напрямую, иначе ищем ГУ среди P2P-пиров и подключаемся PBC.
 */
class P2pJoin(
    private val ctx: Context,
    private val onStatus: (String) -> Unit = {},
    private val onBusy: () -> Unit = {},
    private val onWifi: (on: Boolean) -> Unit = {},
    private val onJoined: (local: Inet4Address) -> Unit,
    private val onLost: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var target: P2pTarget? = null
    private var receiver: BroadcastReceiver? = null
    private var generation = 0
    private var waitingWifi = false
    private var connecting = false
    private var peerMode = false
    private var connectAt = 0L
    private var discoverRuns = 0
    private var busyReported = false
    private var discoverFails = 0
    private var formed = false
    private var joinedIp: Inet4Address? = null

    @Volatile
    private var iface: LanIface? = null

    /** Адрес из P2P-подсети, куда вошёл телефон; по нему пускаем TCP от ГУ. */
    fun owns(address: InetAddress): Boolean = iface?.contains(address) == true

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun start(t: P2pTarget) = main.post { startMain(t) }

    fun stop(why: String) = main.post { stopMain(why) }

    @SuppressLint("MissingPermission")
    private fun startMain(t: P2pTarget) {
        if (target != null && joinedIp != null && p2pIface() == null) {
            Logx.i("Wi-Fi Direct: группа уже распалась, вхожу заново")
            lostMain()
        }
        when {
            joinedIp != null -> {
                Logx.i("Wi-Fi Direct: уже в группе, новый TARGET_INFO пропускаю")
                return
            }
            target != null -> {
                Logx.i("Wi-Fi Direct: новые реквизиты группы, начинаю заново")
                stopMain("новый TARGET_INFO")
            }
        }
        val m = manager ?: return fail("нет Wi-Fi Direct")
        if (!hasPermission()) return fail("нет разрешения «Устройства поблизости»")
        generation++
        target = t
        val ch = try {
            m.initialize(ctx, Looper.getMainLooper()) {
                Logx.w("Wi-Fi Direct: канал менеджера потерян")
            }
        } catch (e: Exception) {
            return fail("не удалось открыть Wi-Fi Direct: ${e.message}")
        }
        channel = ch
        registerReceiver()
        Logx.i(
            "Wi-Fi Direct: вхожу в группу ГУ " +
                (if (t.canJoinBySsid) "по ssid/паролю" else "по имени пира")
        )
        onStatus("Wi-Fi Direct: вхожу в группу магнитолы")
        if (!wifiEnabled()) {
            waitingWifi = true
            Logx.i("Wi-Fi Direct: Wi-Fi выключен, жду включения")
            onWifi(false)
            return
        }
        onWifi(true)
        begin(m, ch, t)
    }

    private fun wifiEnabled(): Boolean = try {
        ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled != false
    } catch (_: SecurityException) {
        true
    }

    private fun begin(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
        if (t.canJoinBySsid) joinBySsid(m, ch, t) else discoverPeers(m, ch)
        main.postDelayed(timeout, JOIN_TIMEOUT_MS)
        main.postDelayed(retry, DISCOVER_RETRY_MS)
    }

    private fun onP2pState(enabled: Boolean) {
        val m = manager ?: return
        val ch = channel ?: return
        val t = target ?: return
        if (!enabled || !waitingWifi) return
        waitingWifi = false
        Logx.i("Wi-Fi Direct: Wi-Fi включён, вхожу в группу")
        onWifi(true)
        begin(m, ch, t)
    }

    private val retry = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val m = manager ?: return
            val ch = channel ?: return
            if (target == null || joinedIp != null) return
            if (peerMode) {
                if (connecting && !formed &&
                    System.currentTimeMillis() - connectAt > CONNECT_STALE_MS
                ) {
                    Logx.i("Wi-Fi Direct: приглашение зависло, ищу магнитолу снова")
                    m.cancelConnect(ch, null)
                    connecting = false
                }
                if (!connecting) {
                    when {
                        discoverFails >= REINIT_AFTER_FAILS -> {
                            Logx.i("Wi-Fi Direct: пересоздаю канал после ошибок поиска")
                            ch.close()
                            val fresh = m.initialize(ctx, Looper.getMainLooper()) {
                                Logx.w("Wi-Fi Direct: новый канал потерян")
                            }
                            channel = fresh
                            discoverFails = 0
                            discoverPeers(m, fresh)
                        }
                        discoverFails > 0 -> m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = again()
                            override fun onFailure(reason: Int) = again()
                            private val g = generation
                            private fun again() {
                                if (g == generation && channel === ch && target != null &&
                                    joinedIp == null && !connecting
                                ) {
                                    discoverPeers(m, ch)
                                }
                            }
                        })
                        else -> discoverPeers(m, ch)
                    }
                }
            }
            main.postDelayed(this, DISCOVER_RETRY_MS)
        }
    }

    private val timeout = Runnable {
        if (target != null && joinedIp == null) {
            Logx.w("Wi-Fi Direct: за ${JOIN_TIMEOUT_MS / 1000} с в группу не вошли")
            onStatus("Wi-Fi Direct: магнитола не пустила в группу")
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinBySsid(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(t.ssid!!)
                .setPassphrase(t.passphrase!!)
                .apply {
                    if (t.freqMhz != null) {
                        setGroupOperatingFrequency(t.freqMhz)
                    } else {
                        setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    }
                }
                .build()
        } catch (e: RuntimeException) {
            Logx.w("Wi-Fi Direct: не удалось собрать конфигурацию по ssid: ${e.message}")
            return discoverPeers(m, ch)
        }
        val g = generation
        connecting = true
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g == generation) Logx.i("Wi-Fi Direct: приглашение по ssid отправлено")
            }

            override fun onFailure(reason: Int) {
                if (g != generation) return
                Logx.w("Wi-Fi Direct: вход по ssid не удался (${reasonName(reason)})")
                connecting = false
                if (target != null) discoverPeers(m, ch)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (target?.deviceName == null) return fail("в TARGET_INFO нет имени Wi-Fi Direct")
        peerMode = true
        val n = ++discoverRuns
        val g = generation
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g != generation) return
                discoverFails = 0
                if (n == 1) Logx.i("Wi-Fi Direct: поиск магнитолы запущен")
            }

            override fun onFailure(reason: Int) {
                if (g != generation) return
                discoverFails++
                val hotspot = hotspotUp()
                Logx.w(
                    "Wi-Fi Direct: поиск не удался (${reasonName(reason)}, " +
                        "хотспот=${if (hotspot) "да" else "нет"})"
                )
                if (reason == WifiP2pManager.BUSY && hotspot && !busyReported && target != null) {
                    busyReported = true
                    onBusy()
                }
            }
        })
        if (n > 1) onPeers()
    }

    @SuppressLint("MissingPermission")
    private fun onPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        val name = target?.deviceName ?: return
        if (!peerMode || connecting || joinedIp != null) return
        val g = generation
        m.requestPeers(ch) { list ->
            if (g != generation) return@requestPeers
            val device: WifiP2pDevice = list.deviceList.firstOrNull { it.deviceName == name }
                ?: return@requestPeers
            if (connecting || target == null) return@requestPeers
            connecting = true
            connectAt = System.currentTimeMillis()
            Logx.i("Wi-Fi Direct: магнитола найдена, подключаюсь")
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            m.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (g == generation) Logx.i("Wi-Fi Direct: приглашение отправлено")
                }

                override fun onFailure(reason: Int) {
                    if (g != generation) return
                    Logx.w("Wi-Fi Direct: приглашение не удалось (${reasonName(reason)})")
                    connecting = false
                }
            })
        }
    }

    private fun onConnectionChanged() {
        val m = manager ?: return
        val ch = channel ?: return
        val g = generation
        m.requestConnectionInfo(ch) { info ->
            if (g != generation || target == null) return@requestConnectionInfo
            if (info == null || !info.groupFormed) {
                formed = false
                if (joinedIp != null) {
                    Logx.i("Wi-Fi Direct: группа магнитолы распалась")
                    lostMain()
                }
                return@requestConnectionInfo
            }
            formed = true
            if (info.isGroupOwner) {
                Logx.w("Wi-Fi Direct: телефон оказался владельцем группы, жду адрес")
            }
            awaitIp(g, 0)
        }
    }

    private fun awaitIp(g: Int, attempt: Int) {
        if (g != generation || target == null || joinedIp != null) return
        val lan = p2pIface()
        if (lan == null) {
            if (attempt < IP_ATTEMPTS) {
                main.postDelayed({ awaitIp(g, attempt + 1) }, IP_RETRY_MS)
            } else {
                Logx.w("Wi-Fi Direct: группа есть, но IPv4 не появился")
            }
            return
        }
        iface = lan
        joinedIp = lan.ip
        main.removeCallbacks(timeout)
        Logx.i("Wi-Fi Direct: телефон в группе ГУ, адрес ${lan.ip.hostAddress}")
        onStatus("Wi-Fi Direct: телефон в группе магнитолы")
        onJoined(lan.ip)
    }

    /** Точка доступа телефона поднята: swlan* у Samsung, ap* у остальных. */
    private fun hotspotUp(): Boolean =
        NetworkInterfaces.list().any { ni ->
            (ni.name.startsWith("swlan") || ni.name.startsWith("ap")) && ni.isUp &&
                ni.inetAddresses.toList().any { it is Inet4Address }
        }

    private fun p2pIface(): LanIface? {
        for (ni in NetworkInterfaces.list()) {
            if (!ni.name.startsWith("p2p") || !ni.isUp) continue
            for (ia in ni.interfaceAddresses) {
                val address = ia.address as? Inet4Address ?: continue
                val broadcast = ia.broadcast as? Inet4Address ?: address
                return LanIface(
                    ni.name,
                    address,
                    ia.networkPrefixLength.toInt(),
                    broadcast,
                )
            }
        }
        return null
    }

    private fun registerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                        onP2pState(
                            intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        )
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun lostMain() {
        stopMain("группа распалась")
        onLost()
    }

    private fun stopMain(why: String) {
        generation++
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
        receiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            try {
                if (peerMode) m.stopPeerDiscovery(ch, null)
                if (connecting || joinedIp != null) m.removeGroup(ch, null)
                ch.close()
            } catch (e: Exception) {
                Logx.w("Wi-Fi Direct: остановка без доступа к P2P: ${e.message}")
            }
        }
        if (target != null) Logx.i("Wi-Fi Direct: остановлен ($why)")
        channel = null
        target = null
        connecting = false
        peerMode = false
        waitingWifi = false
        discoverRuns = 0
        busyReported = false
        discoverFails = 0
        formed = false
        joinedIp = null
        iface = null
    }

    private fun fail(why: String) {
        Logx.w("Wi-Fi Direct: $why")
        onStatus("Wi-Fi Direct: $why")
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ошибка"
        WifiP2pManager.P2P_UNSUPPORTED -> "не поддерживается"
        WifiP2pManager.BUSY -> "занято"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "нет запросов"
        else -> reason.toString()
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000L
        const val DISCOVER_RETRY_MS = 15_000L
        const val CONNECT_STALE_MS = 20_000L
        const val REINIT_AFTER_FAILS = 2
        const val IP_RETRY_MS = 500L
        const val IP_ATTEMPTS = 30
    }
}

/** Небольшая обёртка, чтобы не тянуть в P2pJoin ещё один файл ради одной функции. */
private object NetworkInterfaces {
    fun list(): List<java.net.NetworkInterface> =
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            emptyList()
        }
}
