package com.carlifemirror.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import com.carlifemirror.app.audio.MirrorAudioCapture
import com.carlifemirror.app.carlife.CarLifeMirrorSession
import com.carlifemirror.app.link.CarTransport
import com.carlifemirror.app.link.StreamTransport
import com.carlifemirror.app.link.wireless.WirelessConnector
import com.carlifemirror.app.system.BrightnessController
import com.carlifemirror.app.system.RotationController
import com.carlifemirror.app.touch.MirrorAccessibilityService
import com.carlifemirror.app.util.Logx
import com.carlifemirror.app.video.ScreenEncoder

/**
 * Foreground-сервис: держит MediaProjection, USB-аксессуар и сеанс CarLife.
 * Работает только пока пользователь явно запустил трансляцию.
 */
class MirrorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private lateinit var settings: Settings
    private lateinit var brightness: BrightnessController
    private var stopReturnToMain = true
    private var lastBrightnessPhase: Phase? = null
    private var projection: MediaProjection? = null
    private var session: CarLifeMirrorSession? = null
    private var sessionKind = ""
    private var sessionToken = 0
    /** MediaProjection на Android 14+ одноразовый: после первого VirtualDisplay нужен новый захват. */
    private var sessionEverStarted = false
    private var wireless: WirelessConnector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var closing = false
    private var pendingStopReason = ""
    private val permissionAction = "com.carlifemirror.app.USB_PERMISSION"

    /** Не копируем один и тот же журнал дважды при stopEverything + onDestroy. */
    private var logExportedForSession = false

    @Volatile private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable {
        if (closing || session != null || projection == null) return@Runnable
        Logx.i("Автопереподключение: попытка $reconnectAttempts")
        tryConnect()
    }

    private var usbRecoveryAttempts = 0
    private var usbRecoveryReason = ""
    private var usbDetachCheck: Runnable? = null
    private val usbRecovery = object : Runnable {
        override fun run() {
            if (closing || session != null || projection == null) return
            if (settings.connectionMode != ConnectMode.USB) return
            val attached = usb.accessoryList?.isNotEmpty() == true
            if (!attached) {
                Logx.w("USB: аксессуар не найден после обрыва, переподключение невозможно")
                scheduleReconnect(usbRecoveryReason)
                return
            }
            if (usbRecoveryAttempts >= USB_RECOVERY_ATTEMPTS) {
                Logx.w("USB: $USB_RECOVERY_ATTEMPTS попыток восстановления исчерпано")
                scheduleReconnect(usbRecoveryReason)
                return
            }
            usbRecoveryAttempts++
            Logx.i("USB: восстановление $usbRecoveryAttempts/$USB_RECOVERY_ATTEMPTS")
            tryConnect()
            main.postDelayed(this, USB_RECOVERY_DELAY_MS)
        }
    }

    private fun startUsbRecovery(reason: String) {
        if (closing || projection == null || session != null) return
        if (settings.connectionMode != ConnectMode.USB) return
        if (usbRecoveryAttempts == 0) usbRecoveryReason = reason
        main.removeCallbacks(usbRecovery)
        main.postDelayed(usbRecovery, USB_RECOVERY_FIRST_DELAY_MS)
    }

    private val stateListener: (MirrorSnapshot) -> Unit = { snap ->
        if (snap.phase == Phase.STREAMING) {
            reconnectAttempts = 0
            usbRecoveryAttempts = 0
            usbRecoveryReason = ""
            main.removeCallbacks(usbRecovery)
        }
        if (!closing) {
            updateNotification(snap)
            updateBrightnessForPhase(snap.phase)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Logx.i("USB-аксессуар подключён")
                    reconnectAttempts = 0
                    usbRecoveryAttempts = 0
                    usbRecoveryReason = ""
                    usbDetachCheck?.let { main.removeCallbacks(it) }
                    usbDetachCheck = null
                    main.removeCallbacks(usbRecovery)
                    main.removeCallbacks(reconnectRunnable)
                    MirrorState.update { it.copy(usbAttached = true) }
                    if (settings.connectionMode == ConnectMode.USB && projection != null) {
                        tryConnect()
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Logx.i("USB-аксессуар отключён")
                    main.removeCallbacks(reconnectRunnable)
                    main.removeCallbacks(usbRecovery)
                    reconnectAttempts = 0
                    MirrorState.update { it.copy(usbAttached = false) }
                    // Даём системе 800 мс на повторное перечисление/дребезг,
                    // затем проверяем accessoryList перед полным остановом.
                    val check = Runnable {
                        usbDetachCheck = null
                        if (closing) return@Runnable
                        if (usb.accessoryList?.isNotEmpty() == true) {
                            Logx.i("USB: аксессуар уже снова в списке, продолжаю")
                            return@Runnable
                        }
                        if (sessionKind == "usb-aoa") {
                            stopEverything("USB отключён", fromProjection = false)
                        }
                    }
                    usbDetachCheck?.let { main.removeCallbacks(it) }
                    usbDetachCheck = check
                    main.postDelayed(check, USB_DETACH_GRACE_MS)
                }
                permissionAction -> {
                    val acc = intent.usbAccessory()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Logx.i("Разрешение USB получено")
                        tryConnect()
                    } else {
                        Logx.w("Разрешение USB отклонено")
                        MirrorState.update {
                            it.copy(phase = Phase.ERROR, title = "Нет доступа к USB", detail = "Разрешите доступ к аксессуару CarLife.", error = true)
                        }
                    }
                    if (acc != null) Logx.i("USB: ${describe(acc)}")
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(UsbManager::class.java)
        settings = Settings(this)
        brightness = BrightnessController(this, settings)
        BrightnessController.attach(brightness)
        createChannel()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        MirrorState.addListener(stateListener)
        MirrorState.update {
            it.copy(
                accessibilityEnabled = MirrorAccessibilityService.instance != null ||
                    MirrorAccessibilityService.isEnabled(this),
                usbAttached = usb.accessoryList?.isNotEmpty() == true,
                touchEnabled = settings.touchEnabled,
                connection = settings.connectionMode,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Сюда можно попасть и после смерти процесса: уведомление
                // ещё висит, а MediaProjection уже нет. Нельзя вызывать
                // startForeground(mediaProjection) в таком состоянии — это
                // SecurityException и краш.
                val closeApp = intent.getBooleanExtra(EXTRA_CLOSE_APP, false)
                cancelNotification()
                if (projection == null && session == null) {
                    Logx.i("Стоп: активной трансляции нет, только убираю уведомление")
                    closing = true
                    try { RotationController.restore(this) } catch (_: Exception) {}
                    try { brightness.endSession() } catch (_: Exception) {}
                    if (!closeApp) {
                        SplitApps.bringMainTaskToFront(this)
                        launchMainActivity()
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopEverything(
                    "Остановлено пользователем",
                    fromProjection = false,
                    returnToMain = !closeApp,
                )
                return START_NOT_STICKY
            }

            ACTION_REFRESH_BRIGHTNESS -> {
                refreshBrightness()
                if (projection == null && session == null) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_AUDIO -> {
                session?.refreshAudio()
                if (projection == null && session == null) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_OPEN_MAIN -> {
                // Не запускаем Activity из уведомления: при активном split
                // система открыла бы её в первой панели. Сначала поднимаем
                // уже существующую задачу MainActivity.
                settings.autoSplitSuppressedForSession = true
                Logx.i("Шторка: возвращаю существующий главный экран через задачу")
                val moved = SplitApps.bringMainTaskToFront(this)
                if (!moved) {
                    // Задачи MainActivity уже нет: если split активен, сначала
                    // убираем его окна, иначе новая Activity попадёт в панель.
                    val splitPackages = SplitApps.packages(settings, packageName)
                    if (settings.splitSessionActive && splitPackages.isNotEmpty()) {
                        Logx.i("Шторка: задачи MainActivity нет, закрываю split перед запуском")
                        SplitApps.close(this, splitPackages) {
                            SplitApps.finishSplitHostTask(this)
                            SplitApps.bringMainTaskToFront(this)
                            launchMainActivity(openFromNotification = true)
                            if (projection == null && session == null) stopSelf()
                        }
                        return START_NOT_STICKY
                    }
                    launchMainActivity(openFromNotification = true)
                }
                if (projection == null && session == null) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                Logx.rotateForNewSession(this)
                Logx.i("Старт: новая сессия трансляции")
                startForegroundCompat(buildNotification(MirrorState.value))
                reconnectAttempts = 0
                main.removeCallbacks(reconnectRunnable)
                val replaceProjection = intent.getBooleanExtra(EXTRA_REPLACE_PROJECTION, false)
                if (replaceProjection && projection != null) {
                    Logx.i("Старт: заменяю MediaProjection новым захватом")
                    val pendingWireless = wireless
                    wireless = null
                    try { pendingWireless?.stop("новый захват") } catch (_: Exception) {}
                    closeSession("Новый захват экрана")
                    releaseProjection()
                    sessionEverStarted = false
                    try { brightness.endSession() } catch (_: Exception) {}
                    lastBrightnessPhase = null
                }
                if (projection == null) {
                    settings.autoSplitSuppressedForSession = false
                    settings.autoHomeDoneForSession = false
                    settings.lastSplitApp = ""
                    settings.lastSplitFirstApp = ""
                    settings.splitSessionActive = false
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val data = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    }
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    val p = if (data != null) mpm.getMediaProjection(resultCode, data) else null
                    if (p == null) {
                        MirrorState.update {
                            it.copy(phase = Phase.ERROR, title = "Нет захвата экрана", detail = "Разрешение MediaProjection не получено.", error = true)
                        }
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    projection = p
                    sessionEverStarted = false
                    p.registerCallback(projectionCallback, main)
                    acquireWakeLock()
                    if (settings.systemLandscape) {
                        RotationController.enterLandscape(this)
                    }
                    val wirelessMode = settings.connectionMode == ConnectMode.WIRELESS
                    MirrorState.update {
                        it.copy(
                            projectionReady = true,
                            phase = if (wirelessMode) Phase.WAITING_WIFI else Phase.WAITING_USB,
                            title = "Ожидание магнитолы",
                            detail = if (wirelessMode) {
                                "Захват экрана готов. Включите точку доступа телефона и подключите к ней магнитолу, затем выберите беспроводной CarLife на ГУ."
                            } else {
                                "Захват экрана готов. Подключите USB-кабель к CarLife-магнитоле."
                            },
                            connection = settings.connectionMode,
                            error = false,
                        )
                    }
                    Logx.i("MediaProjection получен")
                }
                MirrorState.update {
                    it.copy(
                        usbAttached = usb.accessoryList?.isNotEmpty() == true,
                        connection = settings.connectionMode,
                    )
                }
                tryConnect()
            }

            else -> {
                if (projection == null && session == null) {
                    cancelNotification()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val component = rootIntent?.component
        val mainTaskAlive = hasMainTask()
        val splitTaskAlive = hasSplitHostTask()
        Logx.i(
            "Задача удалена: ${component?.flattenToShortString() ?: "unknown"} " +
                "(mainAlive=$mainTaskAlive, splitAlive=$splitTaskAlive)"
        )
        if (!mainTaskAlive && !splitTaskAlive) {
            if (!closing && (projection != null || session != null)) {
                stopEverything(
                    "Приложение закрыто",
                    fromProjection = false,
                    returnToMain = false,
                )
            }
        } else {
            // У приложения две задачи: MainActivity и split-host. Удаление
            // одной из них (например, когда пользователь ушёл из split и
            // открыл другое приложение) не должно останавливать трансляцию.
            // Повторно проверяем чуть позже, чтобы не поймать промежуточное
            // состояние ActivityManager.
            main.removeCallbacks(taskCleanup)
            main.postDelayed(taskCleanup, TASK_CLEANUP_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
    }

    private val taskCleanup = Runnable {
        if (closing) return@Runnable
        if (hasMainTask() || hasSplitHostTask()) return@Runnable
        if (projection != null || session != null) {
            Logx.i("Задача удалена: задач приложения не осталось, останавливаю трансляцию")
            stopEverything(
                "Приложение закрыто",
                fromProjection = false,
                returnToMain = false,
            )
        }
    }

    override fun onDestroy() {
        Logx.i("Сервис остановлен")
        Logx.i("Сервис: onDestroy начало")
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(usbRecovery)
        usbDetachCheck?.let { main.removeCallbacks(it) }
        usbDetachCheck = null
        main.removeCallbacks(taskCleanup)
        try { brightness.endSession() } catch (_: Exception) {}
        BrightnessController.detach(brightness)
        cancelNotification()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        MirrorState.removeListener(stateListener)
        val pendingWireless = wireless
        wireless = null
        try { pendingWireless?.stop("Сервис остановлен") } catch (_: Exception) {}
        closeSession("Сервис остановлен")
        releaseProjection()
        releaseWifiLock()
        releaseWakeLock()
        try {
            RotationController.restore(this)
        } catch (e: Exception) {
            Logx.e("Не удалось восстановить системный поворот", e)
        }
        rememberSplitState()
        autoSaveLog()
        Logx.i("Сервис: onDestroy конец")
        super.onDestroy()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            Logx.i(
                "MediaProjection остановлен системой: interactive=${power?.isInteractive}, " +
                    "keyguard=${keyguard?.isKeyguardLocked}, " +
                    "deviceLocked=${keyguard?.isDeviceLocked}",
            )
            stopEverything("Захват экрана остановлен", fromProjection = true)
        }
    }

    private fun updateBrightnessForPhase(phase: Phase) {
        val previous = lastBrightnessPhase
        lastBrightnessPhase = phase
        if (phase == Phase.STREAMING && previous != Phase.STREAMING) {
            if (settings.dimScreen) brightness.startSession()
        } else if (phase != Phase.STREAMING && previous == Phase.STREAMING) {
            brightness.endSession()
        }
    }

    /** Перечитывает настройку после её изменения или выдачи разрешений. */
    private fun refreshBrightness() {
        val phase = MirrorState.value.phase
        if (phase == Phase.STREAMING && settings.dimScreen) {
            brightness.startSession()
        } else {
            brightness.endSession()
        }
        lastBrightnessPhase = phase
    }

    private fun tryConnect() {
        if (closing || session != null || projection == null) return
        MirrorState.update { it.copy(connection = settings.connectionMode) }
        if (settings.connectionMode == ConnectMode.WIRELESS) {
            startWireless()
            return
        }
        connectUsb()
    }

    private fun startWireless() {
        if (closing || session != null || wireless != null || projection == null) return
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        val permissionMissing =
            checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (permissionMissing) {
            Logx.w("Wi-Fi: нет разрешения на устройства поблизости/локальную сеть")
            MirrorState.update {
                it.copy(
                    phase = Phase.ERROR,
                    title = "Нет разрешения для Wi-Fi",
                    detail = "Разрешите доступ к устройствам поблизости в настройках приложения.",
                    error = true,
                )
            }
            return
        }
        if (MirrorState.value.phase != Phase.CONNECTING) {
            MirrorState.update {
                it.copy(
                    phase = Phase.WAITING_WIFI,
                    title = "Ожидание магнитолы",
                    detail = "Wi-Fi: готовлю каналы. Включите точку доступа телефона и подключите к ней магнитолу.",
                    error = false,
                )
            }
        }
        acquireWifiLock()
        Logx.i("Wi-Fi: запускаю ожидание магнитолы")
        val connector = WirelessConnector(
            this,
            onStatus = { text ->
                main.post {
                    if (!closing && session == null) {
                        val connecting = text.startsWith("Wi-Fi: подключено")
                        MirrorState.update {
                            it.copy(
                                phase = if (connecting) Phase.CONNECTING else Phase.WAITING_WIFI,
                                title = if (connecting) "Подключение" else "Ожидание магнитолы",
                                detail = text,
                                linkState = text,
                                error = false,
                            )
                        }
                    }
                }
            },
            onReady = { transport ->
                main.post {
                    if (closing || projection == null || session != null) {
                        try { transport.close() } catch (_: Exception) {}
                    } else {
                        wireless = null
                        startSession(transport)
                    }
                }
            },
            onError = { message ->
                main.post {
                    wireless = null
                    if (!closing && session == null) {
                        Logx.w("Wi-Fi: $message")
                        scheduleReconnect(message)
                    }
                }
            },
        )
        wireless = connector
        connector.start()
    }

    private fun startSession(transport: CarTransport) {
        sessionEverStarted = true
        sessionKind = transport.caps.kind
        logExportedForSession = false
        main.removeCallbacks(reconnectRunnable)
        val isWireless = transport.caps.kind == "tcp6"
        MirrorState.update {
            it.copy(
                phase = Phase.CONNECTING,
                title = "Подключение",
                detail = if (isWireless) {
                    "Wi-Fi: каналы открыты, жду рукопожатие CarLife."
                } else {
                    "Аксессуар CarLife открыт, жду рукопожатие."
                },
                connection = settings.connectionMode,
                linkState = transport.label,
                error = false,
            )
        }
        val token = ++sessionToken
        val s = CarLifeMirrorSession(
            appContext = applicationContext,
            transport = transport,
            settings = settings,
            createEncoder = { w, h, fps, bitrate, listener ->
                val p = projection ?: throw IllegalStateException("MediaProjection уже остановлен")
                ScreenEncoder(this, p, w, h, fps, bitrate, listener)
            },
            createAudio = { onPcm ->
                val p = projection
                if (p == null) null else MirrorAudioCapture(this, p, onPcm)
            },
            onClosed = { reason ->
                main.post {
                    if (token == sessionToken) onSessionClosed(reason)
                    else Logx.i("Сервис: закрыт старый сеанс, игнорирую: $reason")
                }
            },
        )
        session = s
        Logx.i("Транспорт сессии: ${transport.label}")
        s.start()
    }

    private fun connectUsb() {
        if (closing || session != null || projection == null) return
        val list = usb.accessoryList
        if (list.isNullOrEmpty()) {
            MirrorState.update {
                it.copy(
                    phase = Phase.WAITING_USB,
                    title = "Ожидание магнитолы",
                    detail = "USB-аксессуар CarLife не найден. Подключите кабель.",
                    usbAttached = false,
                    error = false,
                )
            }
            return
        }
        val acc = list.firstOrNull {
            it.manufacturer.equals("Baidu", true) || it.model.contains("CarLife", true)
        } ?: list.first()
        if (!usb.hasPermission(acc)) {
            Logx.i("Запрос разрешения USB для ${describe(acc)}")
            val pi = PendingIntent.getBroadcast(
                this,
                0,
                Intent(permissionAction).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(acc, pi)
            MirrorState.update {
                it.copy(
                    phase = Phase.CONNECTING,
                    title = "Доступ к USB",
                    detail = "Подтвердите доступ к аксессуару CarLife.",
                    usbAttached = true,
                )
            }
            return
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            Logx.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            MirrorState.update {
                it.copy(phase = Phase.ERROR, title = "USB недоступен", detail = "Не удалось открыть аксессуар CarLife.", error = true)
            }
            return
        }
        Logx.i("USB открыт: ${describe(acc)}")
        MirrorState.update {
            it.copy(
                usbAttached = true,
                phase = Phase.CONNECTING,
                title = "Подключение",
                detail = "Аксессуар CarLife открыт, жду рукопожатие.",
                error = false,
            )
        }
        val transport = try {
            StreamTransport.fromPfd(pfd, describe(acc))
        } catch (e: Exception) {
            Logx.e("Не удалось открыть потоки USB", e)
            try { pfd.close() } catch (_: Exception) {}
            MirrorState.update {
                it.copy(
                    phase = Phase.ERROR,
                    title = "USB недоступен",
                    detail = "Не удалось открыть потоки аксессуара CarLife.",
                    error = true,
                )
            }
            return
        }
        startSession(transport)
    }

    private fun onSessionClosed(reason: String) {
        Logx.i(
            "Сервис: onSessionClosed начало: $reason " +
                "(USB accessoryList=${usb.accessoryList?.size ?: -1})",
        )
        autoSaveLog()
        session = null
        sessionKind = ""
        if (closing) {
            Logx.i("Сервис: onSessionClosed конец (closing)")
            return
        }
        if (projection == null) {
            MirrorState.update { it.copy(phase = Phase.IDLE, title = "Остановлено", detail = reason, error = false) }
        } else {
            val wirelessMode = settings.connectionMode == ConnectMode.WIRELESS
            MirrorState.update {
                it.copy(
                    phase = if (wirelessMode) Phase.WAITING_WIFI else Phase.WAITING_USB,
                    title = "Ожидание магнитолы",
                    detail = if (wirelessMode) {
                        "Сеанс завершён: $reason. Wi-Fi снова ждёт магнитолу."
                    } else {
                        "Сеанс завершён: $reason. Можно подключить кабель снова."
                    },
                    error = true,
                )
            }
            if (shouldRecoverUsb(reason)) {
                startUsbRecovery(reason)
            } else {
                scheduleReconnect(reason)
            }
        }
        Logx.i("Сервис: onSessionClosed конец")
    }

    private fun shouldRecoverUsb(reason: String): Boolean {
        if (closing || projection == null) return false
        if (settings.connectionMode != ConnectMode.USB) return false
        if (usb.accessoryList?.isNotEmpty() != true) return false
        if (reason.contains("завершила сеанс", ignoreCase = true) ||
            reason.contains("отклонила", ignoreCase = true) ||
            reason.contains("пауза", ignoreCase = true) ||
            reason.contains("Остановлено пользователем", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun scheduleReconnect(reason: String) {
        if (projection == null || closing) return
        if (sessionEverStarted && Build.VERSION.SDK_INT >= 34) {
            // Android 14+ запрещает повторно создавать VirtualDisplay на том
            // же MediaProjection. Честно возвращаемся на главный экран: там
            // пользователь одним нажатием выдаст новый захват.
            Logx.i("Автопереподключение: нужен новый захват экрана (Android 14+)")
            stopEverything(
                "$reason. Нажмите «Начать трансляцию», чтобы выдать новый захват экрана.",
                fromProjection = false,
            )
            return
        }
        if (reason.startsWith("Магнитола завершила сеанс") ||
            reason.startsWith("Магнитола отклонила авторизацию") ||
            reason.contains("(пауза)")
        ) {
            Logx.i("Автопереподключение не нужно: $reason")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Logx.w("Автопереподключение: $MAX_RECONNECT_ATTEMPTS попыток исчерпано")
            stopEverything("Не удалось переподключиться: $reason", fromProjection = false)
            return
        }
        reconnectAttempts++
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        val seconds = RECONNECT_DELAY_MS / 1000
        MirrorState.update {
            it.copy(
                phase = if (settings.connectionMode == ConnectMode.WIRELESS) {
                    Phase.WAITING_WIFI
                } else {
                    Phase.WAITING_USB
                },
                title = "Переподключение",
                detail = "Сеанс завершён: $reason. Попытка $reconnectAttempts через $seconds с.",
                error = true,
            )
        }
        Logx.i("Автопереподключение: попытка $reconnectAttempts через $seconds с")
    }

    private fun closeSession(reason: String) {
        val s = session
        session = null
        sessionKind = ""
        sessionToken++
        try { s?.close(reason) } catch (e: Exception) { Logx.e("Закрытие сеанса", e) }
    }

    /**
     * Журнал уже пишется напрямую в Downloads/CarLifeMirror. Метод остаётся
     * для единой точки завершения сеанса.
     */
    private fun autoSaveLog() {
        if (logExportedForSession) return
        logExportedForSession = true
        Logx.i("Сервис: автосохранение журнала")
        try {
            Logx.exportToDownloads(this)
        } catch (e: Exception) {
            Logx.e("Автосохранение журнала", e)
        }
    }

    private fun stopEverything(
        reason: String,
        fromProjection: Boolean,
        returnToMain: Boolean = true,
    ) {
        if (closing) return
        closing = true
        stopReturnToMain = returnToMain
        pendingStopReason = reason
        try { brightness.endSession() } catch (_: Exception) {}
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(usbRecovery)
        usbDetachCheck?.let { main.removeCallbacks(it) }
        usbDetachCheck = null
        main.removeCallbacks(taskCleanup)
        reconnectAttempts = 0
        usbRecoveryAttempts = 0

        val splitPackages = SplitApps.packages(settings, packageName)
        val hadSplit = settings.splitSessionActive ||
            settings.lastSplitFirstApp.isNotBlank() ||
            settings.lastSplitApp.isNotBlank()
        val mainVisible = isMainActivityVisible()
        val mainInSplit = MirrorState.value.mainInMultiWindow

        // Уведомление должно исчезнуть сразу, а не после попытки закрыть split.
        cancelNotification()
        val pendingWireless = wireless
        wireless = null
        try { pendingWireless?.stop(reason) } catch (_: Exception) {}
        closeSession(reason)
        if (!fromProjection) releaseProjection()
        releaseWifiLock()
        releaseWakeLock()
        settings.autoSplitSuppressedForSession = true

        // Сохраняем окна до stopSelf/onDestroy: onDestroy может не успеть
        // выполниться, если система завершит процесс сразу после остановки.
        rememberSplitState()

        if (hadSplit && splitPackages.isNotEmpty()) {
            Logx.i("Остановка: split остаётся в фоне, закрываю его приложения")
            SplitApps.close(this, splitPackages, sendHome = !mainVisible || mainInSplit) {
                finishStopAfterSplitClose()
            }
        } else {
            finishStopAfterSplitClose()
        }
    }

    /**
     * Вторая половина остановки выполняется после попытки закрыть приложения
     * split. Только теперь возвращаем портрет и поднимаем существующий главный
     * экран: если сделать это раньше, split успевает перестроиться в вертикаль.
     */
    private fun finishStopAfterSplitClose() {
        MirrorState.update {
            it.copy(
                phase = Phase.IDLE,
                title = "Остановлено",
                detail = pendingStopReason,
                projectionReady = false,
                connection = settings.connectionMode,
                linkState = "",
                error = false,
            )
        }
        try {
            RotationController.restore(this)
        } catch (e: Exception) {
            Logx.e("Не удалось восстановить системный поворот", e)
        }

        // Убираем задачу-хозяин split, чтобы она не осталась пустой парой.
        SplitApps.finishSplitHostTask(this)
        if (stopReturnToMain) {
            // Сначала поднимаем задачу без запуска Activity (после Home это
            // разрешено), затем CLEAR_TOP + SINGLE_TOP доводит MainActivity
            // до верха. Второй копии приложения не появляется.
            SplitApps.bringMainTaskToFront(this)
            launchMainActivity()
        } else {
            Logx.i("Остановка: приложение закрыто, главный экран не открываю")
        }
        finishForegroundStop()
    }

    private fun cancelNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    private fun finishForegroundStop() {
        cancelNotification()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun hasMainTask(): Boolean {
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return false
            am.appTasks.any { task ->
                val info = task.taskInfo
                info.baseActivity?.className == MainActivity::class.java.name ||
                    (info.baseActivity == null &&
                        info.topActivity?.className == MainActivity::class.java.name)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasSplitHostTask(): Boolean {
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return false
            am.appTasks.any { task ->
                task.taskInfo.baseActivity?.className == SplitHostActivity::class.java.name
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isMainActivityVisible(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 32) return false
            val am = getSystemService(ActivityManager::class.java) ?: return false
            am.appTasks.any { task ->
                val info = task.taskInfo
                info.isVisible && info.topActivity?.className == MainActivity::class.java.name
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Возвращает уже существующую задачу MainActivity. CLEAR_TOP + SINGLE_TOP
     * не дают создать вторую копию приложения; если задачи ещё нет, система
     * создаст ровно один главный экран.
     */
    private fun launchMainActivity(openFromNotification: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SUPPRESS_AUTO_START, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        if (openFromNotification) {
            intent.putExtra(MainActivity.EXTRA_OPEN_FROM_NOTIFICATION, true)
        }
        try {
            startActivity(intent)
            Logx.i("Сервис: возвращаю существующий главный экран")
        } catch (e: Exception) {
            Logx.w("Сервис: не удалось открыть главный экран: ${e.message}")
        }
    }

    private fun releaseProjection() {
        Logx.i("Сервис: releaseProjection начало")
        val p = projection
        projection = null
        try { p?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { p?.stop() } catch (_: Exception) {}
        Logx.i("Сервис: releaseProjection конец")
    }

    /**
     * «Сохранять последние приложения»: при остановке запоминаем второе окно,
     * которое реально открывалось, а не просто выбранное в настройках позже.
     */
    private fun rememberSplitState() {
        val lastFirst = settings.lastSplitFirstApp
        val lastSecond = settings.lastSplitApp
        val hadLiveSplit = settings.splitSessionActive ||
            lastFirst.isNotBlank() || lastSecond.isNotBlank()
        if (!hadLiveSplit) return
        if (settings.rememberSplitApps) {
            if (lastSecond.isNotBlank() && lastFirst != lastSecond) {
                settings.rememberedSplitFirstApp = lastFirst
                settings.rememberedSplitSecondApp = lastSecond
                settings.hasRememberedSplit = true
                Logx.i("Разделение экрана: запомнил окна first=$lastFirst second=$lastSecond")
            } else if (lastFirst.isNotBlank() && lastFirst == lastSecond) {
                settings.rememberedSplitFirstApp = ""
                settings.rememberedSplitSecondApp = ""
                settings.hasRememberedSplit = false
                Logx.w("Разделение экрана: окна совпали, пару не запоминаю")
            }
        } else {
            settings.rememberedSplitFirstApp = ""
            settings.rememberedSplitSecondApp = ""
            settings.hasRememberedSplit = false
            Logx.i("Разделение экрана: запоминание выключено, окна не сохраняются")
        }
        settings.splitSessionActive = false
        settings.lastSplitApp = ""
        settings.lastSplitFirstApp = ""
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = getSystemService(WifiManager::class.java) ?: return
            val lock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "CarLifeMirror:wifi",
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            Logx.i("Wi-Fi: удерживаю радио в высокопроизводительном режиме")
        } catch (e: Exception) {
            Logx.w("Wi-Fi: не удалось удержать радио: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun acquireWakeLock() {
        // Захват экрана живёт, только пока дисплей включён. Будим экран и не
        // даём системе погасить его по таймауту; затемнение «Гасить экран» —
        // отдельная настройка яркости, а не выключение дисплея.
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CarLifeMirror:screen",
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(snap: MirrorSnapshot): Notification {
        val open = PendingIntent.getService(
            this, 1,
            Intent(this, MirrorService::class.java).setAction(ACTION_OPEN_MAIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(snap.title)
            .setSubText(if (snap.video.isNotBlank()) snap.video else null)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_mirror),
                    getString(R.string.notification_stop),
                    stop,
                ).build()
            )
            .build()
    }

    private fun updateNotification(snap: MirrorSnapshot) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(snap))
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // На старых прошивках типа connectedDevice может не быть.
            // Медиапроекция важнее: без неё сервис вообще не запустится.
            Logx.w("Сервис: connectedDevice недоступен (${e.message}), остаюсь на mediaProjection")
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    private fun Intent.usbAccessory(): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private fun describe(a: UsbAccessory): String =
        listOf(a.manufacturer, a.model, a.version).filter { !it.isNullOrBlank() }.joinToString(" / ")

    companion object {
        const val ACTION_START = "com.carlifemirror.app.START"
        const val ACTION_STOP = "com.carlifemirror.app.STOP"
        const val ACTION_OPEN_MAIN = "com.carlifemirror.app.OPEN_MAIN"
        const val ACTION_REFRESH_AUDIO = "com.carlifemirror.app.REFRESH_AUDIO"
        const val ACTION_REFRESH_BRIGHTNESS = "com.carlifemirror.app.REFRESH_BRIGHTNESS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_REPLACE_PROJECTION = "replace_projection"
        const val EXTRA_CLOSE_APP = "close_app"
        const val CHANNEL = "mirror"
        const val NOTIF_ID = 42
        private const val RECONNECT_DELAY_MS = 20_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val USB_RECOVERY_ATTEMPTS = 2
        private const val USB_RECOVERY_FIRST_DELAY_MS = 800L
        private const val USB_RECOVERY_DELAY_MS = 2_500L
        private const val USB_DETACH_GRACE_MS = 800L
        private const val TASK_CLEANUP_DELAY_MS = 600L
    }
}
