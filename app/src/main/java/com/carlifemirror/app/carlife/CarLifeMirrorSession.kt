package com.carlifemirror.app.carlife

import android.content.Context
import android.graphics.PointF
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.carlifemirror.app.MirrorState
import com.carlifemirror.app.audio.MirrorAudioCapture
import com.carlifemirror.app.Phase
import com.carlifemirror.app.Settings
import com.carlifemirror.app.link.CarTransport
import com.carlifemirror.app.navi.NaviField6
import com.carlifemirror.app.navi.NaviText
import com.carlifemirror.app.navi.NaviTurn
import com.carlifemirror.app.navi.NaviTurnStore
import com.carlifemirror.app.touch.MirrorAccessibilityService
import com.carlifemirror.app.util.Logx
import com.carlifemirror.app.video.ScreenEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Сеанс CarLife со стороны телефона (MD). Умеет только видео и касания:
 * без аудио, метаданных и навигации. Этого достаточно, чтобы показать экран.
 *
 * Видео повторяет проверенное поведение Headswap 2.6.0 / штатного CarLife:
 *  - авторизация CarLife 2.x и RSA;
 *  - 2240x976 @ 30 FPS, 8 Мбит/с для VW CNS3;
 *  - CSD (SPS+PPS) перед первым IDR;
 *  - ограничение очереди и восстановление только со следующего IDR;
 *  - повтор последнего кадра делает кодировщик, поэтому поток не замирает.
 */
class CarLifeMirrorSession(
    private val appContext: Context,
    private val transport: CarTransport,
    private val settings: Settings,
    private val createEncoder: (w: Int, h: Int, fps: Int, bitrate: Int, listener: ScreenEncoder.Listener) -> ScreenEncoder,
    private val createAudio: ((onPcm: (ByteArray) -> Unit) -> MirrorAudioCapture?)? = null,
    private val onClosed: (reason: String) -> Unit,
    private val onHuBluetoothAudio: (() -> Unit)? = null,
) : ScreenEncoder.Listener {

    private val closed = AtomicBoolean(false)
    private val startedAt = SystemClock.elapsedRealtime()
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mirror-session").also { it.isDaemon = true }
    }

    @Volatile private var matchSent = false
    @Volatile private var huMajor = 0
    @Volatile private var huMinor = 0
    @Volatile private var pendingW = 0
    @Volatile private var pendingH = 0
    @Volatile private var pendingFps = Handshake.WORKING_FPS
    @Volatile private var requestedW = 0
    @Volatile private var requestedH = 0
    @Volatile private var requestedFps = 0
    @Volatile private var videoInited = false
    @Volatile private var carlifeStarted = false
    @Volatile private var paused = false
    @Volatile private var projectionLost = false
    @Volatile private var csd: ByteArray? = null
    @Volatile private var csdPending = true
    @Volatile private var encoder: ScreenEncoder? = null
    @Volatile private var lastVideoTx = 0L
    @Volatile private var sentFrames = 0L
    @Volatile private var sentBytes = 0L
    @Volatile private var keyFrames = 0L
    @Volatile private var dropped = 0L
    @Volatile private var heartbeats = 0L

    private var audio: MirrorAudioCapture? = null
    @Volatile private var audioInited = false
    @Volatile private var huBluetoothAudio = false
    @Volatile private var audioFirstLogged = false
    private val audioBytes = AtomicLong()
    private val audioPackets = AtomicLong()
    private val audioDropped = AtomicLong()

    private var mdInfoKey = ""
    @Volatile private var huName = "—"
    @Volatile private var protocol = "—"
    @Volatile private var authMode = "—"
    @Volatile private var videoWhy = ""
    @Volatile private var videoBitrate = 0
    @Volatile private var touchCount = 0
    private var touchSecond: PointF? = null
    private var multiTouchLogged = false
    private var vrMicPackets = 0L
    private var vrMicBytes = 0L
    @Volatile private var huChannel = ""
    @Volatile private var huHeartbeats = 0L
    @Volatile private var lastHeartbeatAt = 0L
    private var idleHeartbeatLogged = false
    @Volatile private var lastVideoQueuedAt = 0L
    @Volatile private var lastTransportWriteAt = 0L
    private var firstPacketLogged = false
    private var configMissingLogged = false
    @Volatile private var lastKeyRequestAt = 0L
    private var lastWatchdogLogAt = 0L
    private var lastOverflowLogAt = 0L
    @Volatile private var lastWifiOverflowAt = 0L
    private var wifiOverflowCount = 0
    private var wifiFpsReduced = false
    @Volatile private var resumeGraceUntil = 0L
    @Volatile private var lastStatLogAt = 0L
    @Volatile private var lastStatFrames = 0L
    @Volatile private var lastMusicControlStatus = 1
    @Volatile private var wirelessBitrateCap = Int.MAX_VALUE
    @Volatile private var lastWirelessDropAt = 0L
    @Volatile private var lastWirelessRaiseAt = 0L
    @Volatile private var wirelessStableSince = 0L
    @Volatile private var naviSent = false
    @Volatile private var naviInfoSent = false
    @Volatile private var naviModuleActive = false
    @Volatile private var naviInfoLastVersion = -1L
    private var naviShown = false
    private var naviLastSentAt = 0L
    private var naviLastShowAt = 0L
    private var naviLastSignature: String? = null
    private var naviLastTurn = -1
    private var naviLastRoad = ""
    private var naviSegmentTotal = 0
    private var naviSegmentRoad = ""
    private var naviLastLogKey = ""
    private var naviInfoLastLogKey = ""
    private var naviField6Last: NaviField6? = null
    private var naviPolicyLogAt = 0L
    private var naviShows = 0L
    private var naviUpdates = 0L
    private var naviKeepalives = 0L
    private var naviPeriodicShows = 0L

    fun start() {
        MirrorState.update {
            it.copy(
                phase = Phase.CONNECTING,
                title = "Подключение",
                detail = "Ожидаю ответ магнитолы CarLife…",
                error = false,
            )
        }
        Logx.i("=== Новая сессия CarLife ===")
        Logx.i(
            "Настройки: ${if (settings.customResolution) "своё" else settings.resolution.name} " +
                "${settings.targetWidth}x${settings.targetHeight} @${settings.fps} FPS"
        )
        Logx.i("Транспорт: ${transport.label}")
        try {
            transport.start(
                ::dispatch,
                { chan, _ ->
                    if (chan == Ch.VIDEO) lastTransportWriteAt = SystemClock.elapsedRealtime()
                },
                ::closedByTransport,
            )
        } catch (e: Exception) {
            val kind = if (transport.caps.kind == "tcp6") "Wi-Fi" else "USB"
            close("Не удалось открыть $kind: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        sched.schedule({
            if (!matchSent && !closed.get()) {
                Logx.w("Рукопожатие: версия ГУ не пришла за 15 с, повторяю MD_INFO")
                sendCmd(Msg.MD_INFO, Msgs.deviceInfo(deviceInfo()))
            }
        }, 15, TimeUnit.SECONDS)
        sched.schedule({
            if (!matchSent && !closed.get()) {
                close("Магнитола не прислала версию протокола CarLife за 30 с")
            }
        }, 30, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({
            if (!matchSent && !closed.get()) {
                Logx.w("Рукопожатие: ГУ молчит, ждём HU_PROTOCOL_VERSION")
            }
        }, 10, 10, TimeUnit.SECONDS)
        sched.schedule({
            if (!closed.get() && !carlifeStarted) {
                close("Магнитола не начала видео за 30 с. Проверьте кабель и повторите.")
            }
        }, 30, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ heartbeat() }, 100, 100, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ videoWatchdog() }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ publishStats() }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ naviTick() }, 1, 500, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay(
            { audioResync() },
            AUDIO_RESYNC_INTERVAL_MS,
            AUDIO_RESYNC_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        val effectiveReason = if (paused) "$reason (пауза)" else reason
        Logx.i("Сеанс завершён: $effectiveReason")
        try { sched.shutdownNow() } catch (_: Exception) {}
        try { MirrorAccessibilityService.instance?.cancelStroke() } catch (_: Exception) {}
        val e = encoder
        Logx.i("Сеанс: остановка видео")
        try { e?.stop() } catch (ex: Exception) { Logx.e("Остановка видео", ex) }
        encoder = null
        stopAudio(sendStop = true)
        Logx.i(buildSummary(effectiveReason, e))
        Logx.i("Сеанс: закрытие транспорта")
        try { transport.close() } catch (_: Exception) {}
        val discarded = dropped + (e?.droppedCount ?: 0L)
        MirrorState.update {
            it.copy(
                phase = Phase.ERROR,
                title = "Отключено",
                detail = effectiveReason,
                error = true,
                video = it.video,
                frames = sentFrames,
                dropped = discarded,
            )
        }
        Logx.i("Сеанс: onClosed")
        onClosed(effectiveReason)
    }

    private fun closedByTransport(reason: String) {
        if (!closed.get()) close(reason)
    }

    // ---------------- приём ----------------

    private fun dispatch(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner) ?: return
            if (chan == Ch.CMD) {
                if (m.type != Msg.VIDEO_HEARTBEAT && m.type != Msg.CAR_VELOCITY) {
                    Logx.i("RX ${Msg.name(m.type)} (${m.body.size} Б)")
                }
                onCmd(m)
            } else {
                onTouch(m)
            }
        } else {
            val m = Wire.parseData(inner) ?: return
            if (m.type == Msg.VR_MIC_DATA) {
                vrMicPackets++
                vrMicBytes += m.data.size
                if (vrMicPackets == 1L || vrMicPackets % 200L == 0L) {
                    Logx.i(
                        "VR MIC: ${m.data.size} Б, пакетов=$vrMicPackets, " +
                            "всего=${vrMicBytes / 1024} КБ"
                    )
                }
            } else {
                Logx.i("RX ${Ch.name(chan)} ${Msg.name(m.type)} ${m.data.size} Б")
            }
        }
    }

    private fun onCmd(m: CmdMsg) {
        when (m.type) {
            Msg.HU_PROTOCOL_VERSION -> onProtocolVersion(m)
            Msg.HU_INFO -> {
                val f = PbReader.parse(m.body)
                val manufacturer = f?.let { PbReader.str(it, 13) }.orEmpty()
                val model = f?.let { PbReader.str(it, 14) }.orEmpty()
                val name = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
                if (name.isNotBlank()) {
                    huName = name
                    MirrorState.update { it.copy(hu = name) }
                    Logx.i("ГУ: $name")
                }
            }
            Msg.STATISTIC_INFO -> {
                val f = PbReader.parse(m.body)
                val channel = f?.let { PbReader.str(it, 4) }.orEmpty().trim()
                if (channel.isNotBlank()) {
                    huChannel = channel
                    Logx.i("ГУ channel=$channel")
                    if (channel == "20082141" && transport.caps.kind == "usb-aoa") {
                        transport.setWriteChunkSize(Wire.CHUNK / 2)
                        Logx.i("USB 20082141: тело пишу кусками ${Wire.CHUNK / 2} Б")
                    }
                }
                Logx.i("STATISTIC_INFO: подтверждаю сессию, запрашиваю FEATURE_CONFIG")
                for (c in Handshake.onStatisticInfo(sendFeatureConfigRequest = true)) sendCmd(c.type, c.body)
            }
            Msg.VIDEO_HEARTBEAT -> {
                huHeartbeats++
                if (huHeartbeats == 1L || huHeartbeats % 60L == 0L) {
                    Logx.i("HU VIDEO_HEARTBEAT #$huHeartbeats")
                }
            }
            Msg.CAR_VELOCITY -> Unit
            Msg.BT_START_IDENTIFY_REQ -> Logx.i("BT_START_IDENTIFY_REQ: игнорирую (как Headswap)")
            Msg.BT_HFP_CONNECTION -> {
                val state = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                val label = when (state) {
                    0 -> "отключён"
                    2 -> "подключён"
                    else -> "неизвестно"
                }
                Logx.i("HFP: соединение $label (state=$state)")
            }
            Msg.BT_HFP_REQUEST,
            Msg.BT_HFP_INDICATION,
            Msg.BT_HFP_RESPONSE,
            Msg.BT_HFP_STATUS_REQUEST,
            Msg.BT_HFP_STATUS_RESPONSE,
            Msg.BT_HFP_CALL_STATUS_COVER -> {
                Logx.i(
                    "HFP ${Msg.name(m.type)}: ${m.body.size} Б" +
                        if (m.body.isNotEmpty()) " [${Hex.compact(m.body, 64)}]" else ""
                )
            }
            Msg.HU_AUTHEN_REQUEST -> onAuth(m)
            Msg.HU_AUTHEN_RESULT -> {
                val ok = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                Logx.i("HU_AUTHEN_RESULT=$ok")
                if (ok == 0) close("Магнитола отклонила авторизацию CarLife")
                else if (ok != null && ok != 1) Logx.w("Неожиданный HU_AUTHEN_RESULT=$ok")
            }
            Msg.HU_FEATURE_CONFIG_RESPONSE -> {
                val fc = FeatureConfig.parse(m.body)
                if (fc != null) {
                    val enc = fc.value("CONTENT_ENCRYPTION")
                    Logx.i(
                        "FEATURE_CONFIG: ${fc.items.take(40).joinToString(", ") { "${it.first}=${it.second}" }}" +
                            (if (fc.items.size > 40) " …" else "")
                    )
                    val audioMode = fc.value("AUDIO_TRANSMISSION_MODE")
                    huBluetoothAudio = audioMode == 1
                    Logx.i(
                        "Звук: AUDIO_TRANSMISSION_MODE=${audioMode ?: "не задан"}, " +
                            if (huBluetoothAudio) "магнитола просит Bluetooth" else "USB разрешён"
                    )
                    if (huBluetoothAudio) {
                        stopAudio(sendStop = true)
                        onHuBluetoothAudio?.invoke()
                    } else if (settings.usbAudio && carlifeStarted) {
                        startAudioIfNeeded()
                    }
                    if (enc == 1) {
                        Logx.w("ГУ требует CONTENT_ENCRYPTION=1; эта версия не шифрует видео")
                        MirrorState.update {
                            it.copy(detail = "ГУ требует шифрование контента — видео может не отобразиться")
                        }
                    }
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE -> {
                val done = Msgs.subscribeDone(m.body)
                if (done != null) sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
            }
            Msg.CARLIFE_DATA_SUBSCRIBE_START, Msg.CARLIFE_DATA_SUBSCRIBE_STOP -> {
                Logx.i("Подписка ${Msg.name(m.type)}: ${Msgs.subscribeIds(m.body)}")
            }
            Msg.VIDEO_ENCODER_JPEG -> sendCmd(Msg.VIDEO_ENCODER_JPEG_ACK, null)
            Msg.REQUEST_MD_THEME_STATE -> sendCmd(Msg.RESPONSE_MD_THEME_STATE, Msgs.themeState(0))
            Msg.CHANGE_MD_THEME -> Unit
            Msg.VIDEO_ENCODER_INIT -> onVideoInit(m)
            Msg.VIDEO_ENCODER_START -> onVideoStart()
            Msg.VIDEO_ENCODER_PAUSE -> {
                paused = true
                resumeGraceUntil = 0L
                lastHeartbeatAt = 0L
                encoder?.setPaused(true)
                // Если ГУ ушла в своё меню, видео ставится на паузу, но
                // музыка должна продолжаться. Захват и MEDIA-поток не рвём.
                Logx.i("Видео: PAUSE от ГУ; звук CarLife оставляю активным")
                MirrorAccessibilityService.instance?.cancelStroke()
                MirrorState.update {
                    it.copy(phase = Phase.PAUSED, title = "Пауза", detail = "Магнитола приостановила видео")
                }
            }
            Msg.VIDEO_ENCODER_RESET -> {
                Logx.i("Видео: RESET, запрашиваю IDR и CSD")
                csdPending = true
                requestKeyFrame(SystemClock.elapsedRealtime())
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val fps = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: -1
                if (Handshake.frameRateChangeAllowed(fps)) {
                    pendingFps = fps
                    encoder?.setTargetFps(fps)
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                    MirrorState.update {
                        it.copy(video = videoLabel())
                    }
                    Logx.i("Видео: FRAME_RATE_CHANGE -> $fps FPS")
                } else {
                    Logx.i("Видео: FRAME_RATE_CHANGE fps=$fps вне 3..30, игнорирую (как Baidu)")
                }
            }
            Msg.MODULE_CONTROL -> onModuleControl(m)
            Msg.MD_EXIT -> close("Магнитола завершила сеанс")
            else -> {
                Logx.w(
                    "Неизвестная CMD 0x%08X (%d Б): %s".format(
                        m.type,
                        m.body.size,
                        Hex.compact(m.body, 64),
                    )
                )
            }
        }
    }

    private fun onProtocolVersion(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val major = f?.let { PbReader.int(it, 1) } ?: 0
        val minor = f?.let { PbReader.int(it, 2) } ?: 0
        if (matchSent) return
        huMajor = major
        huMinor = minor
        protocol = "$major.$minor"
        matchSent = true
        val info = deviceInfo()
        mdInfoKey = HuAuth.mdInfoKey(info)
        for (c in Handshake.startBatch(screenOn = true, info = info)) sendCmd(c.type, c.body)
        sched.schedule({
            if (!closed.get()) {
                val mod = Handshake.moduleStatus(huMajor)
                sendCmd(mod.type, mod.body)
            }
        }, Handshake.MODULE_STATUS_DELAY_MS, TimeUnit.MILLISECONDS)
        MirrorState.update {
            it.copy(
                phase = Phase.CONNECTING,
                title = "Рукопожатие",
                detail = "CarLife $major.$minor — обмен возможностями",
                error = false,
            )
        }
        Logx.i("HU_PROTOCOL_VERSION $major.$minor; MD_INFO отправлен")
    }

    private fun onAuth(m: CmdMsg) {
        val random = PbReader.parse(m.body)?.let { PbReader.str(it, 1) }
        if (random == null) {
            close("HU_AUTHEN_REQUEST без randomValue")
            return
        }
        if (mdInfoKey.isBlank()) mdInfoKey = HuAuth.mdInfoKey(deviceInfo())
        val mode = if (HuAuth.isV2Branch(random)) "2.0" else "RSA"
        val response = try {
            HuAuth.encryptValue(random, mdInfoKey)
        } catch (e: Exception) {
            Logx.e("Не удалось подписать randomValue ($mode)", e)
            close("Ошибка авторизации CarLife ($mode): ${e.message ?: e.javaClass.simpleName}")
            return
        }
        authMode = mode
        sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(response))
        Logx.i("MD_AUTHEN_RESPONSE отправлен, режим $mode (random ${random.length} символов)")
    }

    private fun onModuleControl(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val module = f?.let { PbReader.int(it, 1) } ?: return
        val status = f?.let { PbReader.int(it, 2) } ?: return
        Logx.i("MODULE_CONTROL: module=$module status=$status")
        if (module == Msg.MODULE_MUSIC) {
            val wasMuted = lastMusicControlStatus == 0
            lastMusicControlStatus = status
            if (status == 1) {
                Logx.i("MODULE_CONTROL MUSIC=1: запускаю/восстанавливаю звук CarLife")
                if (wasMuted) reinitAudioStream()
                startAudioIfNeeded()
            } else {
                // На VW MUSIC=0 приходит и при mute с магнитолы, а MUSIC=1
                // после снятия mute часто не приходит. Не рвём захват и поток:
                // иначе звук возвращается только после переключения источника.
                Logx.i("MODULE_CONTROL MUSIC=$status: mute/пауза ГУ, звук CarLife оставляю активным")
            }
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(
                Msg.MODULE_MUSIC,
                if (audioInited) 1 else 0,
            ))
        } else if (module == MODULE_VR) {
            // Голосовое управление не поддерживается.
            Logx.i("MODULE_CONTROL VR=$status проигнорирован")
        }
    }

    private fun onVideoInit(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val rw = f?.let { PbReader.int(it, 1) } ?: 0
        val rh = f?.let { PbReader.int(it, 2) } ?: 0
        val rf = f?.let { PbReader.int(it, 3) } ?: 0
        if (videoInited && pendingW > 0) {
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
            Logx.i("Видео: повторный INIT -> ${pendingW}x$pendingH@$pendingFps")
            return
        }
        requestedW = rw
        requestedH = rh
        requestedFps = rf
        val selectedFps = settings.fps
        // Если ГУ явно просит меньшую частоту, не превышаем её; 0 по протоколу значит «без ограничения».
        var chosenFps = if (rf in 1..60 && rf < selectedFps) rf else selectedFps
        val (w, h, why) = Handshake.chooseVideoSize(
            rw,
            rh,
            settings.targetWidth,
            settings.targetHeight,
        )
        pendingW = w
        pendingH = h
        pendingFps = chosenFps
        videoWhy = why
        videoBitrate = Handshake.bitrateFor(w, h, chosenFps)
        videoInited = true
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(w, h, pendingFps))
        sendCmd(Msg.FOREGROUND, null)
        MirrorState.update { it.copy(video = videoLabel()) }
        Logx.i(
            "VIDEO_ENCODER_INIT=${rw}x$rh@$rf -> ${w}x$h@$pendingFps " +
                "($why, настройка $selectedFps, ${videoBitrate / 1000} кбит/с)"
        )
        sched.schedule({
            if (!closed.get() && !carlifeStarted) {
                Logx.i("Видео: START не пришёл за ${Handshake.START_FALLBACK_MS} мс, начинаю сам")
                onVideoStart()
            }
        }, Handshake.START_FALLBACK_MS, TimeUnit.MILLISECONDS)
    }

    private fun onVideoStart() {
        if (closed.get()) return
        if (projectionLost) {
            Logx.w("Видео: START после потери MediaProjection игнорирую; нужен новый захват")
            return
        }
        // START приходит и после PAUSE: это возобновление. Повторный START во время
        // трансляции игнорируем, чтобы не дёргать кодировщик без причины.
        if (carlifeStarted && !paused) return
        val resuming = paused
        if (pendingW <= 0) {
            val fallback = Handshake.chooseVideoSize(
                0,
                0,
                settings.targetWidth,
                settings.targetHeight,
            )
            pendingW = fallback.first
            pendingH = fallback.second
            pendingFps = settings.fps
            videoWhy = fallback.third
            videoBitrate = Handshake.bitrateFor(pendingW, pendingH, pendingFps)
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
        }
        carlifeStarted = true
        paused = false
        idleHeartbeatLogged = false
        wifiOverflowCount = 0
        lastWifiOverflowAt = 0L
        wifiFpsReduced = false
        csdPending = true
        firstPacketLogged = false
        val now = SystemClock.elapsedRealtime()
        lastVideoTx = now
        lastVideoQueuedAt = now
        lastTransportWriteAt = now
        lastHeartbeatAt = 0L
        lastStatLogAt = now
        lastStatFrames = sentFrames
        if (resuming) {
            // После паузы DrainMeter ещё хранит старое низкое значение скорости.
            // Из-за этого первые 2 кадра могли считаться гигантской очередью
            // (~830 с в логе) и отбрасываться. Даём новые измерения.
            resumeGraceUntil = now + VIDEO_RESUME_GRACE_MS
            lastKeyRequestAt = 0L
            lastOverflowLogAt = 0L
            transport.resetDrainStats()
        }
        val existing = encoder
        if (existing != null) {
            Logx.i("Видео: возобновление, запрашиваю IDR")
            existing.setPaused(false)
            requestKeyFrame(now, force = true)
        } else {
            videoBitrate = Handshake.bitrateFor(pendingW, pendingH, pendingFps)
            try {
                val e = createEncoder(pendingW, pendingH, pendingFps, videoBitrate, this)
                if (transport.caps.kind == "tcp6") {
                    wirelessBitrateCap = minOf(videoBitrate, WIRELESS_START_BITRATE)
                    e.setNetworkBitrateCap(wirelessBitrateCap)
                    Logx.i(
                        "Wi-Fi видео: стартовый предел ${wirelessBitrateCap / 1000} кбит/с " +
                            "(запрошено ${videoBitrate / 1000})"
                    )
                } else {
                    wirelessBitrateCap = Int.MAX_VALUE
                }
                e.start()
                encoder = e
            } catch (e: Exception) {
                close("Не удалось запустить кодировщик: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        }
        MirrorState.update {
            it.copy(
                phase = Phase.STREAMING,
                title = "Трансляция",
                detail = "Экран телефона передаётся на магнитолу",
                error = false,
                video = videoLabel(),
            )
        }
        Logx.i(
            "Видео начато: ${pendingW}x$pendingH@$pendingFps, " +
                "${videoBitrate / 1000} кбит/с (${videoWhy.ifBlank { "рабочий режим" }})"
        )
        startAudioIfNeeded()
        if (resuming && audioInited) {
            // После возврата ГУ из меню напоминаем, что CarLife-звук активен.
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 1))
        }
    }

    // ---------------- звук ----------------

    /**
     * Захват media-звука телефона -> CarLife MEDIA.
     * Формат и последовательность взяты из headswap: INIT 48k/stereo/16,
     * затем DATA по 8 КиБ и MODULE_STATUS MUSIC=1.
     */
    @Synchronized
    private fun startAudioIfNeeded() {
        if (closed.get() || !carlifeStarted) return
        if (!settings.usbAudio) {
            Logx.i("Звук: выключен в настройках")
            return
        }
        if (huBluetoothAudio) {
            Logx.i("Звук: магнитола запросила передачу через Bluetooth, звук CarLife не запускаю")
            return
        }
        if (audioInited || audio != null) return
        val factory = createAudio
        if (factory == null) {
            Logx.w("Звук: захват недоступен (нет MediaProjection)")
            return
        }
        val capture = try {
            factory(::onAudioPcm)
        } catch (e: Exception) {
            Logx.w("Звук: не удалось создать захват: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        if (capture == null) return

        if (!sendAudio(Msg.MEDIA_INIT, Msgs.audioInit(
                MirrorAudioCapture.SAMPLE_RATE,
                MirrorAudioCapture.CHANNELS,
                MirrorAudioCapture.BITS,
            ))
        ) {
            Logx.w("Звук: не удалось отправить MEDIA_INIT")
            return
        }
        audioInited = true
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 1))
        if (!capture.start()) {
            Logx.w("Звук: захват не запущен${capture.failure?.let { ": $it" } ?: ""}")
            audioInited = false
            sendAudio(Msg.MEDIA_STOP, null)
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 0))
            return
        }
        audio = capture
        audioFirstLogged = false
        Logx.i("Звук: MEDIA INIT 48000 Гц, 2 канала, 16 бит; MODULE_STATUS MUSIC=1")
    }

    /**
     * Переинициализация MEDIA без остановки захвата. Нужна, если ГУ после
     * mute/unmute потеряла AudioTrack и не возобновляет воспроизведение.
     */
    @Synchronized
    private fun reinitAudioStream() {
        if (!audioInited || audio == null) return
        Logx.i("Звук: переинициализирую MEDIA после mute")
        sendAudio(Msg.MEDIA_STOP, null)
        if (sendAudio(
                Msg.MEDIA_INIT,
                Msgs.audioInit(
                    MirrorAudioCapture.SAMPLE_RATE,
                    MirrorAudioCapture.CHANNELS,
                    MirrorAudioCapture.BITS,
                )
            )
        ) {
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 1))
        }
    }

    /**
     * Периодически перезапускает только MEDIA-поток, не трогая захват.
     * Такой сброс не даёт очереди AudioTrack на ГУ накапливаться минутами.
     */
    @Synchronized
    private fun audioResync() {
        if (closed.get() || !carlifeStarted || !audioInited || audio == null) return
        if (paused) return
        Logx.i("Звук: плановая переинициализация MEDIA (защита от дрейфа)")
        sendAudio(Msg.MEDIA_STOP, null)
        sendAudio(
            Msg.MEDIA_INIT,
            Msgs.audioInit(
                MirrorAudioCapture.SAMPLE_RATE,
                MirrorAudioCapture.CHANNELS,
                MirrorAudioCapture.BITS,
            ),
        )
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 1))
    }

    @Synchronized
    private fun stopAudio(sendStop: Boolean) {
        val capture = audio
        audio = null
        try { capture?.stop() } catch (_: Exception) {}
        if (!audioInited) return
        audioInited = false
        if (sendStop && !closed.get()) {
            sendAudio(Msg.MEDIA_STOP, null)
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_MUSIC, 0))
            Logx.i("Звук: MEDIA STOP; MODULE_STATUS MUSIC=0")
        }
    }

    /** Вызывается при переключении настройки «Звук по USB» во время трансляции. */
    fun refreshAudio() {
        if (closed.get() || !carlifeStarted) return
        if (settings.usbAudio) {
            startAudioIfNeeded()
        } else {
            stopAudio(sendStop = true)
        }
    }

    /**
     * MediaProjection остановлен системой (обычно блокировкой экрана).
     * Не закрываем сессию и транспорт: отправляем в HU чёрный кадр,
     * чтобы магнитола не висела на последнем кадре. Новый захват потребуется
     * только после явной остановки/повторного запуска.
     */
    fun onProjectionLost() {
        if (closed.get()) return
        if (projectionLost) return
        projectionLost = true
        Logx.w("Видео: MediaProjection потерян; сессию не закрываю, показываю чёрный кадр")
        encoder?.showBlack()
        stopAudio(sendStop = true)
        MirrorState.update {
            it.copy(
                phase = Phase.PAUSED,
                title = "Захват экрана остановлен",
                detail = "Телефон заблокирован. Разблокируйте его и перезапустите трансляцию.",
            )
        }
    }

    private fun onAudioPcm(pcm: ByteArray) {
        // Звук не привязан к паузе видео: при выходе ГУ в меню музыка
        // продолжает играть, а на возврате в CarLife не требует перезапуска.
        if (closed.get() || !audioInited || pcm.isEmpty()) return
        var off = 0
        while (off < pcm.size) {
            val n = minOf(MirrorAudioCapture.CHUNK_BYTES, pcm.size - off)
            val chunk = if (off == 0 && n == pcm.size) pcm else pcm.copyOfRange(off, off + n)
            if (sendAudio(Msg.MEDIA_DATA, chunk, unixMillis(), droppable = true)) {
                audioBytes.addAndGet(n.toLong())
                audioPackets.incrementAndGet()
            } else {
                audioDropped.incrementAndGet()
            }
            off += n
        }
        if (!audioFirstLogged) {
            audioFirstLogged = true
            Logx.i("Звук: первые PCM-данные ${pcm.size} Б")
        }
    }

    private fun sendAudio(
        type: Int,
        body: ByteArray?,
        time: Int = 0,
        droppable: Boolean = false,
    ): Boolean {
        if (closed.get()) return false
        if (droppable && transport.queueSize(Ch.MEDIA) >= MAX_AUDIO_QUEUE) return false
        val inner = Wire.dataInner(type, time, body)
        return if (droppable) transport.sendDropIfFull(Ch.MEDIA, inner)
        else transport.send(Ch.MEDIA, inner)
    }

    // ---------------- видео ----------------

    override fun onConfig(csd: ByteArray) {
        val normalized = AnnexB.normalize(csd)
        this.csd = normalized
        csdPending = true
        firstPacketLogged = false
        configMissingLogged = false
        val types = AnnexB.nalTypes(normalized)
        Logx.i("Кодек: CSD ${normalized.size} Б, NAL $types")
        if (!types.contains(7) || !types.contains(8)) {
            Logx.w("В CSD нет SPS+PPS; жду IDR с параметрами")
        }
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || paused || !carlifeStarted) return
        val now = SystemClock.elapsedRealtime()

        var payload = AnnexB.normalize(data)
        val types = AnnexB.nalTypes(payload)
        val idr = types.contains(5)
        if (key && !idr) {
            // Ключевой флаг без IDR бесполезен для декодера ГУ.
            dropped++
            csdPending = true
            requestKeyFrame(now)
            Logx.w("Видео: ключевой кадр без IDR (NAL $types), пропускаю")
            return
        }
        if (!key && csdPending) {
            // После потери/переполнения P-кадры нельзя слать: декодер уже разошёлся.
            dropped++
            return
        }
        if (key) {
            val withConfig = AnnexB.ensureSpsPps(payload, csd)
            if (withConfig == null) {
                dropped++
                csdPending = true
                requestKeyFrame(now)
                if (!configMissingLogged) {
                    configMissingLogged = true
                    Logx.w("Видео: нет SPS+PPS ни в IDR, ни в CSD; жду CSD")
                }
                return
            }
            payload = withConfig
            csdPending = false
        }

        payload = AnnexB.fixSps(payload, pendingW, pendingH, pendingFps)

        if (now >= resumeGraceUntil && VideoQueueLimit.overflow(
                transport.queuedVideoFrames,
                transport.queuedVideoBytes,
                transport.drainBytesPerSec,
            )
        ) {
            if (transport.caps.kind == "tcp6") {
                reduceWirelessBitrate("очередь видео переполнена")
                maybeLowerWifiFps(now)
            }
            dropped++
            csdPending = true
            requestKeyFrame(now)
            val backlog = VideoQueueLimit.backlogMs(transport.queuedVideoBytes, transport.drainBytesPerSec)
            if (now - lastOverflowLogAt >= 5000) {
                lastOverflowLogAt = now
                Logx.w(
                    "Видео: очередь переполнена (${transport.queuedVideoFrames} кадров, " +
                        "${transport.queuedVideoBytes / 1024} КБ, ~$backlog мс), жду IDR"
                )
            }
            return
        }

        if (!send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), payload))) {
            dropped++
            csdPending = true
            requestKeyFrame(now)
            return
        }
        sentFrames++
        sentBytes += payload.size.toLong()
        if (key) keyFrames++
        lastVideoTx = now
        lastVideoQueuedAt = now
        if (key && !firstPacketLogged) {
            firstPacketLogged = true
            val outTypes = AnnexB.nalTypes(payload)
            val ok = outTypes.contains(7) && outTypes.contains(8) && outTypes.contains(5)
            val text = "Первый видеопакет: ${payload.size} Б, NAL $outTypes"
            if (ok) Logx.i("$text — SPS+PPS+IDR") else Logx.w("$text — не SPS+PPS+IDR")
        }
    }

    override fun onError(message: String, t: Throwable?) {
        Logx.e(message, t)
        close(message + (t?.message?.let { ": $it" } ?: ""))
    }

    // ---------------- касания ----------------

    private fun onTouch(m: CmdMsg) {
        if (m.type == Msg.TOUCH_CAR_HARD_KEY_CODE) {
            val code = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: return
            Logx.i("Кнопка ГУ: 0x%02X %s".format(code, hardKeyName(code)))
            val svc = MirrorAccessibilityService.instance
            when (code) {
                0x1E -> svc?.home()
                0x0E -> svc?.back()
                0x0F, 0x06 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                0x10, 0x07 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                0x1F -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                0x20 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            return
        }
        if (!settings.touchEnabled) return
        val svc = MirrorAccessibilityService.instance
        if (svc == null) {
            MirrorState.update {
                it.copy(detail = "Касания приходят, но служба управления выключена")
            }
            return
        }
        val t = CarlifeTouch.parse(m.type, m.body) ?: return
        val e = encoder ?: return
        val first = e.mapTouch(t.x, t.y)
        if (first == null) {
            if (t.masked == 1 || t.masked == 5 || t.masked == 6) {
                svc.cancelStroke()
                clearTouchPoints()
            }
            return
        }
        touchCount++
        // Касания с магнитолы не должны менять яркость телефона. Яркость
        // возвращается только при физическом касании экрана телефона.
        when (t.masked) {
            0 -> {
                touchSecond = null
                svc.down(first.x, first.y)
            }

            5 -> {
                val second = mapSecondPoint(e, t) ?: first
                touchSecond = second
                logMultiTouch(first, second)
                svc.downTwo(first.x, first.y, second.x, second.y)
            }

            2 -> {
                val previousSecond = touchSecond
                if (previousSecond != null) {
                    val second = if (hasSecondPoint(t)) {
                        e.mapTouch(t.px, t.py) ?: previousSecond
                    } else {
                        previousSecond
                    }
                    touchSecond = second
                    svc.moveTwo(first.x, first.y, second.x, second.y)
                } else {
                    svc.move(first.x, first.y)
                }
            }

            6 -> {
                val second = touchSecond
                if (second != null) {
                    val updated = if (hasSecondPoint(t)) {
                        e.mapTouch(t.px, t.py) ?: second
                    } else {
                        second
                    }
                    svc.upTwo(first.x, first.y, updated.x, updated.y)
                } else {
                    svc.up(first.x, first.y)
                }
                clearTouchPoints()
            }

            1 -> {
                val second = touchSecond
                if (second != null) {
                    val updated = if (hasSecondPoint(t)) {
                        e.mapTouch(t.px, t.py) ?: second
                    } else {
                        second
                    }
                    svc.upTwo(first.x, first.y, updated.x, updated.y)
                } else {
                    svc.up(first.x, first.y)
                }
                clearTouchPoints()
            }

            3 -> {
                // Полный клик может прийти без предшествующего DOWN. Если незавершённый
                // жест всё же остался, сначала корректно отпускаем его.
                svc.cancelStroke()
                clearTouchPoints()
                if (m.type == Msg.TOUCH_LONG_PRESS) svc.longPress(first.x, first.y) else svc.tap(first.x, first.y)
            }

            else -> Unit
        }
        if (touchCount == 1) {
            MirrorState.update { it.copy(detail = "Управление с магнитолы включено") }
        }
    }

    private fun hardKeyName(code: Int): String = when (code) {
        0x01 -> "HOME"
        0x02 -> "PHONE_CALL"
        0x03 -> "PHONE_END"
        0x04 -> "PHONE_END_MUTE"
        0x05 -> "HFP"
        0x06 -> "SELECTOR_NEXT"
        0x07 -> "SELECTOR_PREVIOUS"
        0x08 -> "SETTING"
        0x09 -> "MEDIA"
        0x0A -> "RADIO"
        0x0B -> "NAVI"
        0x0C -> "SRC"
        0x0D -> "MODE"
        0x0E -> "BACK"
        0x0F -> "SEEK_SUB"
        0x10 -> "SEEK_ADD"
        0x11 -> "VOLUME_SUB"
        0x12 -> "VOLUME_ADD"
        0x13 -> "MUTE"
        0x14 -> "OK"
        0x15 -> "MOVE_LEFT"
        0x16 -> "MOVE_RIGHT"
        0x17 -> "MOVE_UP"
        0x18 -> "MOVE_DOWN"
        0x19 -> "MOVE_UP_LEFT"
        0x1A -> "MOVE_UP_RIGHT"
        0x1B -> "MOVE_DOWN_LEFT"
        0x1C -> "MOVE_DOWN_RIGHT"
        0x1D -> "TEL"
        0x1E -> "MAIN"
        0x1F -> "MEDIA_START"
        0x20 -> "MEDIA_STOP"
        0x21 -> "VR_START"
        0x22 -> "VR_STOP"
        in 0x23..0x2C -> "NUMBER_${code - 0x23}"
        0x2D -> "STAR"
        0x2E -> "POUND"
        0x2F -> "DEL"
        0x30 -> "CLEAR"
        0x31 -> "ADD"
        else -> "UNKNOWN"
    }

    /**
     * Пересылает медиа-клавишу активному плееру телефона (Яндекс.Музыка и т.п.).
     * SEEK_ADD/SUB — это следующий/предыдущий трек, MEDIA_START/STOP — play/pause.
     */
    private fun dispatchMediaKey(keyCode: Int) {
        val am = try {
            appContext.getSystemService(AudioManager::class.java)
        } catch (_: Exception) {
            null
        }
        if (am == null) {
            Logx.w("Кнопка ГУ: AudioManager недоступен")
            return
        }
        val now = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            )
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            )
            Logx.i("Кнопка ГУ: отправлено ${KeyEvent.keyCodeToString(keyCode)}")
        } catch (e: Exception) {
            Logx.w(
                "Кнопка ГУ: не удалось отправить ${KeyEvent.keyCodeToString(keyCode)}: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun hasSecondPoint(t: CarlifeTouch): Boolean = t.px != 0 || t.py != 0

    private fun mapSecondPoint(e: ScreenEncoder, t: CarlifeTouch): PointF? =
        if (hasSecondPoint(t)) e.mapTouch(t.px, t.py) else null

    private fun clearTouchPoints() {
        touchSecond = null
    }

    private fun logMultiTouch(first: PointF, second: PointF) {
        if (multiTouchLogged) return
        multiTouchLogged = true
        Logx.i("Касание: два пальца, p0=${first.x.toInt()},${first.y.toInt()} p1=${second.x.toInt()},${second.y.toInt()}")
    }

    // ---------------- служебные циклы ----------------

    /**
     * VW CNS3 20082141, как и известные 20082120/20082122, на PAUSE ждёт
     * именно VIDEO_HEARTBEAT, а не пустой VIDEO_DATA. Для Wi-Fi-канала
     * Tiguan L Pro используется 20082134, для CNS2 — 20082114.
     */
    private val pauseHeartbeatHu: Boolean
        get() = huChannel == "20082141" || huChannel == "20082120" ||
            huChannel == "20082122" || huChannel == "20082134" || huChannel == "20082114"

    /** VW CNS3 20082141 рвёт USB на простое без кадров; шлём VIDEO_HEARTBEAT. */
    private val idleHeartbeatHu: Boolean
        get() = huChannel == "20082141"

    private fun heartbeat() {
        if (closed.get() || !carlifeStarted) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastVideoTx
        val type: Int
        val period: Long
        if (paused) {
            if (pauseHeartbeatHu) {
                type = Msg.VIDEO_HEARTBEAT
                period = PAUSE_HEARTBEAT_MS
            } else {
                type = Msg.VIDEO_DATA
                period = PAUSE_KEEPALIVE_MS
            }
        } else {
            if (idle <= 1_000) return
            if (idleHeartbeatHu) {
                type = Msg.VIDEO_HEARTBEAT
                period = IDLE_HEARTBEAT_MS
                if (!idleHeartbeatLogged) {
                    idleHeartbeatLogged = true
                    Logx.i(
                        "Видео: 20082141 молчит ${idle} мс, шлю пустой VIDEO_HEARTBEAT " +
                            "раз в $IDLE_HEARTBEAT_MS мс",
                    )
                }
            } else {
                if (idle <= 2_000) return
                type = Msg.VIDEO_DATA
                period = PAUSE_KEEPALIVE_MS
            }
        }
        if (lastHeartbeatAt != 0L && now - lastHeartbeatAt < period) return
        if (!send(Ch.VIDEO, Wire.dataInner(type, unixSeconds(), ByteArray(0)))) return
        lastHeartbeatAt = now
        lastVideoQueuedAt = now
        heartbeats++
        if (heartbeats == 1L || heartbeats % 60L == 0L) {
            Logx.i(
                "Видео: keepalive #$heartbeats ${Msg.name(type)} " +
                    if (paused) "(пауза)" else "(нет кадров ${idle} мс)"
            )
        }
    }

    /**
     * Turn-by-turn на ГУ: NotificationListener складывает последний манёвр
     * в NaviTurnStore, здесь он превращается в NAVI_NEXT_TURN_INFO.
     * Дополнительно (экспериментально) можно отправить NAVI_INFO 0x1003B.
     */
    private fun naviTick() {
        if (closed.get() || !carlifeStarted) return
        val snap = NaviTurnStore.snapshot()
        val turn = snap.turn
        val active = snap.active && turn != null
        val standardWanted = settings.naviHud && active
        val infoWanted = settings.naviInfoHud && active

        // Стандартный режим выключили — сообщаем ГУ о завершении.
        if (!standardWanted && naviSent) {
            send(
                Ch.CMD,
                Wire.cmdInner(
                    Msg.NAVI_NEXT_TURN_INFO,
                    Msgs.naviNextTurn(
                        action = 3,
                        nextTurn = 24,
                        roadName = "Пункт назначения",
                        totalDistance = 0,
                        remainDistance = 0,
                        turnIcon = null,
                        timeSeconds = 0,
                        field6 = settings.naviField6,
                    ),
                ),
            )
            naviSent = false
            naviShown = false
            naviLastSentAt = 0L
            naviLastShowAt = 0L
            naviLastSignature = null
            naviLastTurn = -1
            naviLastRoad = ""
        }
        if (!infoWanted) naviInfoSent = false

        if (!standardWanted && !infoWanted) {
            if (naviModuleActive) {
                send(
                    Ch.CMD,
                    Wire.cmdInner(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_NAVI, 0)),
                )
                naviModuleActive = false
                Logx.i("Навигация: turn-by-turn завершён")
            }
            naviSegmentTotal = 0
            naviSegmentRoad = ""
            naviLastLogKey = ""
            naviInfoLastLogKey = ""
            naviShown = false
            naviLastSentAt = 0L
            naviLastShowAt = 0L
            naviLastSignature = null
            naviLastTurn = -1
            naviLastRoad = ""
            return
        }
        val t = turn ?: return
        if (!naviModuleActive) {
            send(
                Ch.CMD,
                Wire.cmdInner(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(Msg.MODULE_NAVI, 1)),
            )
            naviModuleActive = true
            Logx.i(
                "Навигация: turn-by-turn начат" +
                    if (settings.naviInfoHud) " (NAVI_INFO 0x1003B)" else ""
            )
        }
        // Как в Baidu CarLife: totalDistance — расстояние на момент смены улицы,
        // а remainDistance уменьшается по мере приближения к манёвру.
        if (t.roadName != naviSegmentRoad ||
            (naviSegmentTotal <= 0 && t.remainMeters > 0)
        ) {
            naviSegmentRoad = t.roadName
            naviSegmentTotal = t.remainMeters
        }
        val total = when {
            naviSegmentTotal > 0 -> naviSegmentTotal
            t.totalMeters > 0 -> t.totalMeters
            else -> t.remainMeters
        }

        val field6 = settings.naviField6
        if (standardWanted && field6 != naviField6Last) {
            naviField6Last = field6
            Logx.i("Навигация: поле 6 HUD -> ${field6.name}")
        }

        if (standardWanted) {
            if (!naviSent) {
                naviSent = true
                naviShown = false
                naviLastSentAt = 0L
                naviLastShowAt = 0L
                naviLastSignature = null
                naviLastTurn = -1
                naviLastRoad = ""
                naviPolicyLogAt = 0L
            }
            val now = SystemClock.elapsedRealtime()
            val idle = if (naviLastSentAt == 0L) Long.MAX_VALUE else now - naviLastSentAt
            // HUD/приборка может погасить подсказку сама: после паузы и раз в
            // 10 секунд повторяем SHOW, а неизменный манёвр поддерживаем
            // keepalive раз в 5 секунд.
            val reshow = naviShown && idle >= NAVI_RESHOW_MS
            val periodic = naviShown && now - naviLastShowAt >= NAVI_SHOW_EVERY_MS
            val signature = buildString {
                append(field6.name).append('|')
                append(t.nextTurn).append('|')
                append(t.roadName).append('|')
                append(total).append('|')
                append(t.remainMeters).append('|')
                append(t.timeSeconds)
            }
            val changed = signature != naviLastSignature
            val keepalive = !changed && idle >= NAVI_KEEPALIVE_MS
            // Первый пакет нового манёвра — SHOW: HUD мог погасить прошлую
            // подсказку, и UPDATE её уже не вернёт.
            val newManeuver = naviShown && (t.nextTurn != naviLastTurn || t.roadName != naviLastRoad)
            if (changed || reshow || periodic || keepalive) {
                val reason = when {
                    !naviShown -> "first SHOW"
                    newManeuver -> "new maneuver SHOW"
                    reshow -> "reshow SHOW"
                    periodic -> "periodic SHOW"
                    keepalive -> "keepalive"
                    else -> "update"
                }
                val action = if (!naviShown || newManeuver || reshow || periodic) 1 else 2
                val ok = send(
                    Ch.CMD,
                    Wire.cmdInner(
                        Msg.NAVI_NEXT_TURN_INFO,
                        Msgs.naviNextTurn(
                            action = action,
                            nextTurn = t.nextTurn,
                            roadName = t.roadName,
                            totalDistance = total,
                            remainDistance = t.remainMeters,
                            turnIcon = t.iconPng,
                            timeSeconds = t.timeSeconds,
                            field6 = field6,
                        ),
                    ),
                )
                if (ok) {
                    naviShown = true
                    naviLastSignature = signature
                    naviLastTurn = t.nextTurn
                    naviLastRoad = t.roadName
                    naviLastSentAt = now
                    if (action == 1) {
                        naviLastShowAt = now
                        naviShows++
                    } else {
                        naviUpdates++
                    }
                    if (reason == "keepalive") naviKeepalives++
                    if (reason == "periodic SHOW") naviPeriodicShows++
                    val logKey = "${t.nextTurn}|${t.roadName}"
                    if (logKey != naviLastLogKey) {
                        naviLastLogKey = logKey
                        Logx.i(
                            "Навигация: NAVI_NEXT_TURN_INFO · манёвр ${t.nextTurn} · " +
                                "${t.remainMeters} м · ${field6.name} · $reason · action=$action"
                        )
                    } else if (reason == "keepalive" || reason == "periodic SHOW" || reason == "reshow SHOW") {
                        // Не спамим на каждом 5-секундном keepalive: для проверки
                        // достаточно одной строки в 30 секунд.
                        if (now - naviPolicyLogAt >= NAVI_POLICY_LOG_MS) {
                            naviPolicyLogAt = now
                            Logx.i(
                                "Навигация: HUD $reason · action=$action · манёвр ${t.nextTurn} · " +
                                    "${t.remainMeters} м · ${field6.name}"
                            )
                        }
                    }
                }
            }
        }

        if (infoWanted) {
            if (!naviInfoSent) {
                naviInfoSent = true
                naviInfoLastVersion = -1L
            }
            if (t.version != naviInfoLastVersion) {
                if (sendNaviInfo(t)) {
                    naviInfoLastVersion = t.version
                    val logKey = "info|${t.nextTurn}"
                    if (logKey != naviInfoLastLogKey) {
                        naviInfoLastLogKey = logKey
                        Logx.i(
                            "Навигация: NAVI_INFO · манёвр ${t.nextTurn} · ${t.remainMeters} м"
                        )
                    }
                }
            }
        }
    }

    /** Экспериментальное HUD-сообщение NAVI_INFO 0x1003B. */
    private fun sendNaviInfo(turn: NaviTurn): Boolean {
        val body = Msgs.naviBikeInfo(
            icon = turn.iconPng,
            iconName = NaviText.iconName(turn.nextTurn),
            turnInfo1 = NaviText.maneuverText(turn.nextTurn),
            turnInfo2 = turn.roadName,
            remainDistanceDesc = NaviText.distanceDesc(turn.remainMeters),
            remainTimeDesc = NaviText.timeDesc(turn.timeSeconds),
            speed = 0,
            timeStamp = System.currentTimeMillis(),
        )
        return send(Ch.CMD, Wire.cmdInner(Msg.NAVI_INFO, body))
    }

    private fun videoWatchdog() {
        if (closed.get() || !carlifeStarted) return
        val now = SystemClock.elapsedRealtime()
        if (lastTransportWriteAt == 0L) lastTransportWriteAt = now
        val queued = transport.queuedVideoFrames
        val sinceWrite = now - lastTransportWriteAt
        // Reader может быть жив (VIDEO_HEARTBEAT/CAR_VELOCITY идут), а writer
        // стоит в output.write. Без этого watchdog сессия висит до ручной остановки.
        val recentlySending = now - lastVideoQueuedAt < TX_STALL_MS
        if ((queued > 0 || recentlySending) && sinceWrite >= TX_STALL_MS) {
            Logx.e(
                "TX: запись видео зависла на ${sinceWrite} мс, " +
                    "очередь $queued кадров / ${transport.queuedVideoBytes / 1024} КБ"
            )
            close("Транспорт: запись видео зависла ($sinceWrite мс)")
            return
        }
        if (paused) return

        val idle = now - lastVideoTx
        if (idle < 1500) return
        if (now - lastWatchdogLogAt >= 5000) {
            lastWatchdogLogAt = now
            Logx.w("Видео: нет кадров ${idle} мс, запрашиваю IDR")
        }
        requestKeyFrame(now)
    }

    private fun publishStats() {
        if (closed.get()) return
        val e = encoder
        val discarded = dropped + (e?.droppedCount ?: 0L)
        MirrorState.update {
            it.copy(
                frames = sentFrames,
                dropped = discarded,
                video = if (e != null) {
                    "${pendingW}x${pendingH} @${e.currentFps} • ${e.currentBitrateBps / 1000} кбит/с" +
                        (if (e.thermalLevelName == "NORMAL") "" else " (${e.thermalLevelName})") +
                        (if (audioInited) " • звук" else "")
                } else {
                    it.video
                },
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (!carlifeStarted) return
        if (lastStatLogAt == 0L) {
            lastStatLogAt = now
            lastStatFrames = sentFrames
            return
        }
        val elapsed = now - lastStatLogAt
        if (elapsed < 5000) return
        val delta = sentFrames - lastStatFrames
        val fps = delta * 1000.0 / elapsed
        val queueKb = transport.queuedVideoBytes / 1024
        val drainKbps = (transport.drainBytesPerSec * 8 / 1000).toLong()
        val codecFps = e?.currentFps ?: pendingFps
        val codecKbps = (e?.currentBitrateBps ?: videoBitrate) / 1000
        maybeRaiseWirelessBitrate(now)
        Logx.i(
            "Видео: ${String.format(Locale.US, "%.1f", fps)} FPS, " +
                "кадров=$sentFrames (ключевых=$keyFrames), " +
                "отправлено=${sentBytes / 1024} КБ, " +
                "очередь=${transport.queuedVideoFrames}/$queueKb КБ, " +
                "транспорт=$drainKbps кбит/с, tx=${transport.txBytes / 1024} КБ, " +
                "кодек=$codecFps FPS/$codecKbps кбит/с, " +
                "термо=${e?.thermalLevelName ?: "—"}, " +
                "звук=${audioBytes.get() / 1024} КБ/${audioPackets.get()} пак., " +
                "звук-очередь=${transport.queueSize(Ch.MEDIA)}, " +
                "звук-отброшено=${audioDropped.get()}, " +
                "HU hb=$huHeartbeats, " +
                "keepalive=$heartbeats, отброшено=$discarded${wifiLinkInfo()}"
        )
        lastStatLogAt = now
        lastStatFrames = sentFrames
    }

    private fun requestKeyFrame(now: Long, force: Boolean = false) {
        if (!force && now - lastKeyRequestAt < VideoQueueLimit.OVERFLOW_HOLD_MS) return
        lastKeyRequestAt = now
        encoder?.requestKeyFrame()
    }

    /** Wi-Fi: при переполнении снижаем битрейт, чтобы не терять каждый IDR. */
    private fun reduceWirelessBitrate(why: String) {
        if (transport.caps.kind != "tcp6") return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWirelessDropAt < WIRELESS_ADJUST_COOLDOWN_MS) return
        lastWirelessDropAt = now
        wirelessStableSince = 0L
        val current = if (wirelessBitrateCap == Int.MAX_VALUE) {
            videoBitrate
        } else {
            wirelessBitrateCap
        }
        val next = maxOf(WIRELESS_MIN_BITRATE, (current * 0.7).toInt())
        if (next >= current) return
        wirelessBitrateCap = next
        encoder?.setNetworkBitrateCap(next)
        Logx.w(
            "Wi-Fi видео: $why, снижаю битрейт " +
                "${current / 1000} -> ${next / 1000} кбит/с"
        )
    }

    /**
     * Wi-Fi: если очередь переполняется повторно, снижаем FPS до 15.
     * Разрешение не меняем — HU часто упирается именно в обработку 30 кадров/с.
     */
    private fun maybeLowerWifiFps(now: Long) {
        if (wifiFpsReduced || transport.caps.kind != "tcp6") return
        val e = encoder ?: return
        if (e.currentFps <= WIFI_FALLBACK_FPS) {
            wifiFpsReduced = true
            return
        }
        if (lastWifiOverflowAt != 0L && now - lastWifiOverflowAt <= WIFI_FPS_OVERFLOW_WINDOW_MS) {
            wifiOverflowCount++
        } else {
            wifiOverflowCount = 1
        }
        lastWifiOverflowAt = now
        if (wifiOverflowCount < WIFI_FPS_OVERFLOW_COUNT) return
        wifiFpsReduced = true
        pendingFps = WIFI_FALLBACK_FPS
        e.setTargetFps(WIFI_FALLBACK_FPS)
        MirrorState.update { it.copy(video = videoLabel()) }
        Logx.w(
            "Wi-Fi: очередь переполняется ($wifiOverflowCount раз), " +
                "снижаю FPS до $WIFI_FALLBACK_FPS; разрешение ${pendingW}x$pendingH оставляю",
        )
    }

    /** Wi-Fi: аккуратно возвращаем битрейт, когда канал стабильно разгружен. */
    private fun maybeRaiseWirelessBitrate(now: Long) {
        if (transport.caps.kind != "tcp6" || !carlifeStarted || paused) return
        if (wirelessBitrateCap == Int.MAX_VALUE || wirelessBitrateCap >= videoBitrate) return
        val drain = transport.drainBytesPerSec
        val queueLow = transport.queuedVideoFrames <= 2
        if (!queueLow || drain < wirelessBitrateCap * 1.25) {
            wirelessStableSince = 0L
            return
        }
        if (wirelessStableSince == 0L) wirelessStableSince = now
        if (now - wirelessStableSince < WIRELESS_STABLE_MS) return
        if (now - lastWirelessRaiseAt < WIRELESS_RAISE_INTERVAL_MS) return
        val next = minOf(videoBitrate, (wirelessBitrateCap * 1.2).toInt())
        if (next <= wirelessBitrateCap) return
        wirelessBitrateCap = next
        lastWirelessRaiseAt = now
        encoder?.setNetworkBitrateCap(next)
        Logx.i(
            "Wi-Fi видео: канал стабилен (${(drain * 8 / 1000).toInt()} кбит/с), " +
                "поднимаю битрейт до ${next / 1000} кбит/с"
        )
    }

    // ---------------- отправка ----------------

    private fun sendCmd(type: Int, body: ByteArray?) {
        send(Ch.CMD, Wire.cmdInner(type, body))
    }

    private fun send(chan: Int, inner: ByteArray): Boolean {
        if (closed.get()) return false
        return transport.send(chan, inner)
    }

    private fun unixSeconds(): Int = (System.currentTimeMillis() / 1000L).toInt()

    private fun unixMillis(): Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

    private fun videoLabel(): String =
        "${pendingW}x$pendingH @$pendingFps" +
            if (videoBitrate > 0) " • ${videoBitrate / 1000} кбит/с" else ""

    @Suppress("DEPRECATION")
    private fun wifiLinkInfo(): String {
        if (transport.caps.kind != "tcp6") return ""
        return try {
            val wm = appContext.getSystemService(WifiManager::class.java) ?: return ""
            val info = wm.connectionInfo ?: return ""
            " wifi=${info.frequency}MHz/${info.linkSpeed}Mbps/rssi=${info.rssi}"
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildSummary(reason: String, e: ScreenEncoder?): String {
        val uptime = (SystemClock.elapsedRealtime() - startedAt) / 1000L
        val discarded = dropped + (e?.droppedCount ?: 0L)
        val mb = String.format(Locale.US, "%.2f", sentBytes / (1024.0 * 1024.0))
        val actualFps = e?.currentFps ?: pendingFps
        val actualKbps = (e?.currentBitrateBps ?: videoBitrate) / 1000
        return buildString {
            appendLine("=== ИТОГ СЕССИИ ===")
            appendLine("Причина: $reason")
            appendLine("Длительность: $uptime с")
            appendLine("ГУ: $huName" + if (huChannel.isNotBlank()) " (канал $huChannel)" else "")
            appendLine("Протокол: $protocol, авторизация: $authMode")
            appendLine(
                "Видео: запрос ${requestedW}x${requestedH}@$requestedFps -> " +
                    "${pendingW}x${pendingH}@$pendingFps, ${videoBitrate / 1000} кбит/с (${videoWhy.ifBlank { "—" }})"
            )
            appendLine(
                "Фактически: $actualFps FPS, $actualKbps кбит/с, термо ${e?.thermalLevelName ?: "—"}; " +
                    "макс. ${e?.thermalMaxLevelName ?: "—"}, разгрузка ${(e?.throttledMs ?: 0L) / 1000} с"
            )
            appendLine(
                "Отправлено: $sentFrames кадров (ключевых $keyFrames), $mb МБ, " +
                    "keepalive $heartbeats, HU heartbeat $huHeartbeats"
            )
            appendLine(
                "HUD: SHOW=$naviShows, UPDATE=$naviUpdates, keepalive=$naviKeepalives, " +
                    "periodic=$naviPeriodicShows, поле 6=${settings.naviField6.name}"
            )
            appendLine(
                "Кодировщик: кадров ${e?.frameCount ?: 0}, отброшено ${e?.droppedCount ?: 0}; " +
                    "сессия отбросила $dropped"
            )
            appendLine(
                "Транспорт: принято ${transport.rxBytes / 1024} КБ, " +
                    "отправлено ${transport.txBytes / 1024} КБ, " +
                    "очередь ${transport.queuedVideoFrames} кадров / " +
                    "${transport.queuedVideoBytes / 1024} КБ, отброшено всего $discarded${wifiLinkInfo()}"
            )
            appendLine(
                "Звук: " + when {
                    !settings.usbAudio -> "выключен"
                    huBluetoothAudio -> "Bluetooth (просит ГУ)"
                    else -> "CarLife MEDIA"
                } + ", BT-режим ГУ=$huBluetoothAudio, отправлено ${audioBytes.get() / 1024} КБ / " +
                    "${audioPackets.get()} пакетов, отброшено ${audioDropped.get()}"
            )
            if (vrMicPackets > 0) {
                appendLine("VR MIC: получено ${vrMicBytes / 1024} КБ / $vrMicPackets пакетов")
            }
            append("Касаний: $touchCount; журнал: ${Logx.currentFilePath()}")
        }
    }

    private fun deviceInfo(): DeviceInfo {
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(Build.VERSION.SDK_INT)
        return DeviceInfo(
            os = "Android",
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            release = Build.VERSION.RELEASE.orEmpty(),
            sdk = sdk,
            sdkInt = sdkInt,
            serial = "unknown",
            carlifeVersion = Handshake.MD_VERSION,
            carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    companion object {
        private const val MODULE_VR = 4
        private const val PAUSE_KEEPALIVE_MS = 1_000L
        private const val PAUSE_HEARTBEAT_MS = 100L
        private const val IDLE_HEARTBEAT_MS = 1_000L
        private const val NAVI_KEEPALIVE_MS = 5_000L
        private const val NAVI_RESHOW_MS = 10_000L
        private const val NAVI_SHOW_EVERY_MS = 10_000L
        private const val NAVI_POLICY_LOG_MS = 30_000L
        private const val TX_STALL_MS = 5_000L
        private const val VIDEO_RESUME_GRACE_MS = 2_500L

        /** Не держим в очереди больше ~1 с аудио, иначе звук отстанет от видео. */
        private const val MAX_AUDIO_QUEUE = 8

        private const val WIRELESS_START_BITRATE = 4_000_000
        private const val WIRELESS_MIN_BITRATE = 2_000_000
        private const val WIFI_FALLBACK_FPS = 15
        private const val WIFI_FPS_OVERFLOW_COUNT = 2
        private const val WIFI_FPS_OVERFLOW_WINDOW_MS = 30_000L
        private const val WIRELESS_ADJUST_COOLDOWN_MS = 1_000L
        private const val WIRELESS_STABLE_MS = 10_000L
        private const val WIRELESS_RAISE_INTERVAL_MS = 5_000L
        private const val AUDIO_RESYNC_INTERVAL_MS = 5 * 60 * 1000L
    }
}
