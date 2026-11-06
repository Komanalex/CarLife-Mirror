package com.carlifemirror.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.carlifemirror.app.carlife.Handshake
import com.carlifemirror.app.system.RotationController
import com.carlifemirror.app.system.ThemeController
import com.carlifemirror.app.touch.MirrorAccessibilityService
import com.carlifemirror.app.util.Logx
import java.text.Collator
import java.util.Locale

private enum class SplitSlot { FIRST, SECOND }

open class MainActivity : Activity() {
    private lateinit var settings: Settings

    private lateinit var themeSystem: TextView
    private lateinit var themeLight: TextView
    private lateinit var themeDark: TextView
    private lateinit var rootView: View
    private lateinit var rootScroll: ScrollView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsBack: ImageButton
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusMeta: TextView
    private lateinit var notificationWarning: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var heroIcon: ImageView
    private lateinit var primaryButton: Button
    private lateinit var splitButton: LinearLayout
    private lateinit var splitIcon: ImageView
    private lateinit var splitButtonLabel: TextView
    private lateinit var splitHint: TextView
    private lateinit var versionText: TextView
    private lateinit var aboutVersion: TextView
    private lateinit var connectionUsb: TextView
    private lateinit var connectionWifi: TextView
    private lateinit var connectionHint: TextView
    private lateinit var connectionApplyNote: TextView
    private lateinit var openWifiSettings: Button
    private lateinit var qualityAuto: TextView
    private lateinit var quality720: TextView
    private lateinit var quality1080: TextView
    private lateinit var quality2k: TextView
    private lateinit var customSizeSwitch: Switch
    private lateinit var customSizeRow: View
    private lateinit var customWidth: EditText
    private lateinit var customHeight: EditText
    private lateinit var customSizeError: TextView
    private lateinit var fps15: TextView
    private lateinit var fps30: TextView
    private lateinit var fps60: TextView
    private lateinit var autoStartSwitch: Switch
    private lateinit var touchSwitch: Switch
    private lateinit var dimScreenSwitch: Switch
    private lateinit var brightnessDimOptions: View
    private lateinit var brightnessDimSeek: SeekBar
    private lateinit var brightnessDimValue: TextView
    private lateinit var brightnessDimWarning: TextView
    private lateinit var usbAudioSwitch: Switch
    private lateinit var usbAudioNote: TextView
    private lateinit var systemLandscapeSwitch: Switch
    private lateinit var rotationPermissionStatus: TextView
    private lateinit var rotationPermissionButton: Button
    private lateinit var splitAutoSwitch: Switch
    private lateinit var splitOptions: LinearLayout
    private lateinit var splitFirstAppRow: View
    private lateinit var splitFirstAppValue: TextView
    private lateinit var splitAppRow: View
    private lateinit var splitAppValue: TextView
    private lateinit var splitRatioSeek: SeekBar
    private lateinit var splitRatioValue: TextView
    private lateinit var rememberSplitAppsSwitch: Switch
    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityButton: Button
    private lateinit var accessibilityHint: TextView
    private lateinit var detailsToggle: TextView
    private lateinit var detailsText: TextView
    private lateinit var saveLogButton: Button

    private var detailsVisible = false
    private var projectionPending = false
    private var awaitingConsent = false
    private var startingService = false
    private var stopping = false
    private var pendingServiceStart: (() -> Unit)? = null
    private var bindingVideoUi = false
    private var bindingAudioUi = false
    private var bindingSplitUi = false
    private var bindingRotationUi = false
    private var bindingBrightnessUi = false
    private var pendingAudioSwitch = false
    private var audioPermissionRequestPending = false
    private var pendingWirelessMode = false
    private var pendingWirelessForProjection = false
    private var wirelessPermissionRequestPending = false
    private var checkingRotationPermission = false
    private var mirrorLandscape = false
    private var splitRequested = false
    private var splitRecreateInProgress = false
    private var splitRatioApplyAttempts = 0
    private var multiWindowObserved = false
    private var pendingAutoSplit = false
    private var autoSplitScheduled = false
    private var pendingFirstLaunch: ActivityInfo? = null
    private var firstLaunchAttempts = 0
    private var firstLaunchAt = 0L
    private var firstRetryScheduled = false
    private var settingsVisible = false
    private var permissionDialog: Dialog? = null
    private var permissionPromptShown = false
    private var permissionPromptDismissed = false
    private var checkingBrightnessPermission = false
    private var autoStartAttempted = false
    private var backCallback: OnBackInvokedCallback? = null
    private var lastProjectionReady = false
    private var projectionDenied = false

    private val startAfterRotation = Runnable {
        val start = pendingServiceStart
        pendingServiceStart = null
        if (startingService) start?.invoke()
    }

    /**
     * После старта трансляции без автосплита приложение должно уйти на
     * рабочий стол. MediaProjection и сервис при этом продолжают работать,
     * поэтому пользователь позже может вернуться из «Недавних».
     */
    private val applySplitRatio: Runnable = Runnable {
        if (!MirrorState.value.projectionReady || !settings.splitSessionActive) {
            splitRatioApplyAttempts = 0
            return@Runnable
        }
        val access = MirrorAccessibilityService.instance
        val applied = access?.applySplitRatio(settings.splitRatioFirst) == true
        if (!applied && splitRatioApplyAttempts < 40) {
            splitRatioApplyAttempts++
            window.decorView.postDelayed(applySplitRatio, 300L)
        } else {
            splitRatioApplyAttempts = 0
        }
    }

    private val goHomeAfterStart = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (!MirrorState.value.projectionReady) return@Runnable
        if (settings.splitAuto || settings.splitSessionActive) {
            Logx.i("Старт: сплит уже запланирован, на рабочий стол не ухожу")
            return@Runnable
        }
        if (!hasWindowFocus()) {
            Logx.i("Старт: приложение уже не на переднем плане, рабочий стол не открываю")
            return@Runnable
        }
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Logx.i("Старт: открыт рабочий стол, приложение осталось в фоне")
        } catch (e: Exception) {
            Logx.w("Не удалось открыть рабочий стол: ${e.message}; сворачиваю задачу")
            moveTaskToBack(true)
        }
    }

    private val stateListener: (MirrorSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    private val immersiveGuard = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !shouldHideStatusBar()) return
            applyImmersiveMode()
            window.decorView.postDelayed(this, IMMERSIVE_REHIDE_MS)
        }
    }

    private val customSizeWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (bindingVideoUi) return
            customSizeError.visibility = View.GONE
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        splitRequested = savedInstanceState?.getBoolean(KEY_SPLIT_REQUESTED) ?: false
        val restoreSettings = savedInstanceState?.getBoolean(KEY_SETTINGS_VISIBLE, false) ?: false
        // Уведомление возвращает уже существующий главный экран. Split при
        // этом не трогаем: он остаётся в фоне, но автосплит больше не
        // запускаем до следующей трансляции.
        if (intent.getBooleanExtra(EXTRA_OPEN_FROM_NOTIFICATION, false)) {
            settings.autoSplitSuppressedForSession = true
            splitRequested = false
            pendingAutoSplit = false
            autoSplitScheduled = false
            pendingFirstLaunch = null
            firstLaunchAttempts = 0
            firstRetryScheduled = false
            Logx.i("Шторка: возвращён существующий главный экран; split не закрываю")
        }
        setContentView(R.layout.activity_main)
        publishMultiWindowState()
        if (intent.getBooleanExtra(EXTRA_SUPPRESS_AUTO_START, false)) {
            autoStartAttempted = true
            intent.removeExtra(EXTRA_SUPPRESS_AUTO_START)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!,
            )
        }
        applyWindowInsets()
        bindViews()
        setupListeners()
        window.decorView.post { updateImmersiveMode() }
        touchSwitch.isChecked = settings.touchEnabled
        updateVideoSettingsUi()
        updateSplitUi()
        updateRotationUi()
        render(MirrorState.value)
        handleIntent(intent)
        refreshEnvironment()
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        versionText.text = getString(R.string.version_short, versionName)
        aboutVersion.text = getString(R.string.version_full, versionName)
        window.decorView.postDelayed({ maybeShowPermissionPrompt() }, 350)
        window.decorView.postDelayed({ maybeAutoStart() }, 900)
        if (restoreSettings || intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            window.decorView.postDelayed({ showSettings() }, 80)
        }
    }

    private fun maybeAutoStart() {
        if (!settings.autoStart || autoStartAttempted) return
        if (settingsVisible || awaitingConsent || startingService || projectionPending ||
            MirrorState.value.projectionReady || permissionDialog?.isShowing == true
        ) {
            return
        }
        autoStartAttempted = true
        Logx.i("Автозапуск: открываю запрос захвата экрана")
        requestProjection()
    }

    @Suppress("DEPRECATION")
    private fun applyWindowInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        rootView = findViewById(R.id.root)
        rootView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            if (bars.top > 0 && shouldHideStatusBar()) {
                // Пользователь показал статусную строку свайпом: даём ей
                // побыть видимой и снова прячем через несколько секунд.
                window.decorView.removeCallbacks(immersiveGuard)
                window.decorView.postDelayed(immersiveGuard, IMMERSIVE_REHIDE_MS)
            }
            insets
        }
        rootView.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        publishMultiWindowState()
        MirrorState.addListener(stateListener)
        refreshEnvironment()
        updateAudioUi()
        render(MirrorState.value)
        updateImmersiveMode()
        scheduleImmersiveGuard()
        syncSystemRotation()
        updatePermissionDialog()
        if (checkingRotationPermission) {
            checkingRotationPermission = false
            val granted = RotationController.canWrite(this)
            Toast.makeText(
                this,
                if (granted) R.string.rotation_permission_granted
                else R.string.rotation_permission_not_granted,
                Toast.LENGTH_LONG,
            ).show()
        }
        if (checkingBrightnessPermission) {
            checkingBrightnessPermission = false
            if (!RotationController.canWrite(this)) {
                Toast.makeText(
                    this,
                    R.string.brightness_permission_not_granted,
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                refreshBrightnessService()
            }
        }
        if (pendingAutoSplit) {
            window.decorView.postDelayed({ attemptAutoSplit() }, AUTO_SPLIT_FOCUS_SETTLE_MS)
        }
        maybeRetryFirstInOurTask()
    }

    override fun onPause() {
        if (!isInMultiWindowMode && !multiWindowObserved) {
            window.decorView.removeCallbacks(immersiveGuard)
        }
        MirrorState.removeListener(stateListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            rootScroll.scrollTo(0, 0)
            val start = pendingServiceStart
            if (start != null) {
                pendingServiceStart = null
                window.decorView.removeCallbacks(startAfterRotation)
                if (startingService) start()
            }
        }
        updateImmersiveMode()
        scheduleImmersiveGuard()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        multiWindowObserved = isInMultiWindowMode
        publishMultiWindowState()
        Logx.i("Мультиокно: ${if (isInMultiWindowMode) "включено" else "выключено"}")
        updateImmersiveMode()
        scheduleImmersiveGuard()
        if (!isInMultiWindowMode) {
            maybeAutoSplit(MirrorState.value)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        updateImmersiveMode()
        scheduleImmersiveGuard()
        if (!pendingAutoSplit || !MirrorState.value.projectionReady) return
        window.decorView.postDelayed({
            if (pendingAutoSplit) {
                pendingAutoSplit = false
                attemptAutoSplit()
            }
        }, AUTO_SPLIT_FOCUS_SETTLE_MS)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(startAfterRotation)
        window.decorView.removeCallbacks(goHomeAfterStart)
        window.decorView.removeCallbacks(applySplitRatio)
        window.decorView.removeCallbacks(immersiveGuard)
        pendingServiceStart = null
        permissionDialog?.dismiss()
        permissionDialog = null
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        super.onDestroy()
    }

    private fun publishMultiWindowState() {
        val inSplit = isInMultiWindowMode
        if (MirrorState.value.mainInMultiWindow != inSplit) {
            MirrorState.update { it.copy(mainInMultiWindow = inSplit) }
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun shouldHideStatusBar(): Boolean =
        MirrorState.value.projectionReady && isLandscape()

    /**
     * В горизонтальном режиме трансляции держим статусную строку скрытой.
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE оставляет возможность вызвать её
     * свайпом сверху; после этого она снова прячется.
     */
    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        val hide = shouldHideStatusBar()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val controller = window.insetsController ?: return
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hide) {
                    controller.hide(WindowInsets.Type.statusBars())
                } else {
                    controller.show(WindowInsets.Type.statusBars())
                }
            } else {
                val decor = window.decorView
                var flags = decor.systemUiVisibility
                if (hide) {
                    flags = flags or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else {
                    flags = flags and
                        View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                }
                decor.systemUiVisibility = flags
            }
        } catch (e: Exception) {
            Logx.w("Не удалось обновить полноэкранный режим: ${e.message}")
        }
    }

    private fun scheduleImmersiveGuard() {
        window.decorView.removeCallbacks(immersiveGuard)
        if (shouldHideStatusBar()) {
            window.decorView.postDelayed(immersiveGuard, IMMERSIVE_REHIDE_MS)
        }
    }

    private fun updateImmersiveMode() {
        applyImmersiveMode()
        scheduleImmersiveGuard()
    }

    private fun handleBack() {
        when {
            settingsVisible -> hideSettings()
            MirrorState.value.projectionReady -> {
                // Пользователь закрывает приложение, а не просто уходит в фон:
                // останавливаем трансляцию и не открываем главный экран снова.
                stopMirror(closeApp = true)
                finishAndRemoveTask()
            }
            else -> finish()
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SPLIT_REQUESTED, splitRequested)
        outState.putBoolean(KEY_SETTINGS_VISIBLE, settingsVisible)
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_FROM_NOTIFICATION, false)) {
            settings.autoSplitSuppressedForSession = true
            splitRequested = false
            pendingAutoSplit = false
            autoSplitScheduled = false
            pendingFirstLaunch = null
            firstLaunchAttempts = 0
            firstRetryScheduled = false
            Logx.i("Шторка: главный экран уже открыт; split не закрываю")
        }
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SUPPRESS_AUTO_START, false)) {
            autoStartAttempted = true
            intent.removeExtra(EXTRA_SUPPRESS_AUTO_START)
        }
        handleIntent(intent)
        refreshEnvironment()
    }

    private fun bindViews() {
        themeSystem = findViewById(R.id.themeSystem)
        themeLight = findViewById(R.id.themeLight)
        themeDark = findViewById(R.id.themeDark)
        rootScroll = findViewById(R.id.homeScroll)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsScroll = findViewById(R.id.settingsScroll)
        settingsButton = findViewById(R.id.settingsButton)
        settingsBack = findViewById(R.id.settingsBack)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusMeta = findViewById(R.id.statusMeta)
        notificationWarning = findViewById(R.id.notificationWarning)
        statusProgress = findViewById(R.id.statusProgress)
        heroIcon = findViewById(R.id.heroIcon)
        primaryButton = findViewById(R.id.primaryButton)
        splitButton = findViewById(R.id.splitButton)
        splitIcon = findViewById(R.id.splitIcon)
        splitButtonLabel = findViewById(R.id.splitButtonLabel)
        splitHint = findViewById(R.id.splitHint)
        versionText = findViewById(R.id.versionText)
        aboutVersion = findViewById(R.id.aboutVersion)
        connectionUsb = findViewById(R.id.connectionUsb)
        connectionWifi = findViewById(R.id.connectionWifi)
        connectionHint = findViewById(R.id.connectionHint)
        connectionApplyNote = findViewById(R.id.connectionApplyNote)
        openWifiSettings = findViewById(R.id.openWifiSettings)
        qualityAuto = findViewById(R.id.qualityAuto)
        quality720 = findViewById(R.id.quality720)
        quality1080 = findViewById(R.id.quality1080)
        quality2k = findViewById(R.id.quality2k)
        customSizeSwitch = findViewById(R.id.customSizeSwitch)
        customSizeRow = findViewById(R.id.customSizeRow)
        customWidth = findViewById(R.id.customWidth)
        customHeight = findViewById(R.id.customHeight)
        customSizeError = findViewById(R.id.customSizeError)
        fps15 = findViewById(R.id.fps15)
        fps30 = findViewById(R.id.fps30)
        fps60 = findViewById(R.id.fps60)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        touchSwitch = findViewById(R.id.touchSwitch)
        dimScreenSwitch = findViewById(R.id.dimScreenSwitch)
        brightnessDimOptions = findViewById(R.id.brightnessDimOptions)
        brightnessDimSeek = findViewById(R.id.brightnessDimSeek)
        brightnessDimValue = findViewById(R.id.brightnessDimValue)
        brightnessDimWarning = findViewById(R.id.brightnessDimWarning)
        usbAudioSwitch = findViewById(R.id.usbAudioSwitch)
        usbAudioNote = findViewById(R.id.usbAudioNote)
        systemLandscapeSwitch = findViewById(R.id.systemLandscapeSwitch)
        rotationPermissionStatus = findViewById(R.id.rotationPermissionStatus)
        rotationPermissionButton = findViewById(R.id.rotationPermissionButton)
        splitAutoSwitch = findViewById(R.id.splitAutoSwitch)
        splitOptions = findViewById(R.id.splitOptions)
        splitFirstAppRow = findViewById(R.id.splitFirstAppRow)
        splitFirstAppValue = findViewById(R.id.splitFirstAppValue)
        splitAppRow = findViewById(R.id.splitAppRow)
        splitAppValue = findViewById(R.id.splitAppValue)
        splitRatioSeek = findViewById(R.id.splitRatioSeek)
        splitRatioValue = findViewById(R.id.splitRatioValue)
        rememberSplitAppsSwitch = findViewById(R.id.rememberSplitAppsSwitch)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        accessibilityHint = findViewById(R.id.accessibilityHint)
        detailsToggle = findViewById(R.id.detailsToggle)
        detailsText = findViewById(R.id.detailsText)
        saveLogButton = findViewById(R.id.saveLogButton)
    }

    private fun setupListeners() {
        primaryButton.setOnClickListener {
            val snapshot = MirrorState.value
            when {
                snapshot.projectionReady && snapshot.phase == Phase.ERROR -> retryMirror()
                snapshot.projectionReady -> stopMirror()
                else -> requestProjection()
            }
        }
        splitButton.setOnClickListener { requestSplitScreen() }
        settingsButton.setOnClickListener { showSettings() }
        settingsBack.setOnClickListener { hideSettings() }
        splitFirstAppRow.setOnClickListener { showSplitAppPicker(SplitSlot.FIRST, launchAfterPick = false) }
        splitAppRow.setOnClickListener { showSplitAppPicker(SplitSlot.SECOND, launchAfterPick = false) }

        splitRatioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (bindingSplitUi) return
                val first = (progress * 10).coerceIn(30, 70)
                settings.splitRatioFirst = first
                val snapped = first / 10
                if (progress != snapped) {
                    bindingSplitUi = true
                    try {
                        seekBar?.progress = snapped
                    } finally {
                        bindingSplitUi = false
                    }
                }
                updateSplitRatioLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (settings.splitSessionActive) {
                    scheduleSplitRatioApply(initialDelayMs = 250L)
                }
            }
        })

        splitAutoSwitch.setOnCheckedChangeListener { _, checked ->
            settings.splitAuto = checked
            Logx.i("Автоделение экрана: $checked")
            updateSplitUi()
            if (checked) {
                pendingAutoSplit = false
                autoSplitScheduled = false
                // Если второе приложение ещё не выбрано, сначала показываем
                // настройки пары и не запускаем split с пустым значением.
                if (effectiveSplitPair().second != null) {
                    maybeAutoSplit(MirrorState.value)
                }
            } else {
                pendingAutoSplit = false
                autoSplitScheduled = false
            }
        }

        connectionUsb.setOnClickListener { selectConnection(ConnectMode.USB) }
        connectionWifi.setOnClickListener { selectConnection(ConnectMode.WIRELESS) }
        openWifiSettings.setOnClickListener { openWifiSettings() }

        themeSystem.setOnClickListener { selectTheme(AppTheme.SYSTEM) }
        themeLight.setOnClickListener { selectTheme(AppTheme.LIGHT) }
        themeDark.setOnClickListener { selectTheme(AppTheme.DARK) }

        qualityAuto.setOnClickListener { selectPreset(Resolution.AUTO) }
        quality720.setOnClickListener { selectPreset(Resolution.P720) }
        quality1080.setOnClickListener { selectPreset(Resolution.P1080) }
        quality2k.setOnClickListener { selectPreset(Resolution.P2K) }

        fps15.setOnClickListener { selectFps(15) }
        fps30.setOnClickListener { selectFps(30) }
        fps60.setOnClickListener { selectFps(60) }

        customSizeSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingVideoUi) return@setOnCheckedChangeListener
            if (checked) {
                settings.customResolution = true
                customSizeError.visibility = View.GONE
                fillCustomFields()
                saveCustomSize(showError = false)
            } else {
                saveCustomSize(showError = false)
                settings.customResolution = false
                customSizeError.visibility = View.GONE
            }
            updateVideoSettingsUi()
        }

        customWidth.addTextChangedListener(customSizeWatcher)
        customHeight.addTextChangedListener(customSizeWatcher)
        customWidth.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && settings.customResolution) saveCustomSize(showError = true)
        }
        customHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && settings.customResolution) saveCustomSize(showError = true)
        }
        customWidth.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                customHeight.requestFocus()
                true
            } else {
                false
            }
        }
        customHeight.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                customHeight.clearFocus()
                true
            } else {
                false
            }
        }

        autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoStart = checked
        }
        touchSwitch.setOnCheckedChangeListener { _, checked ->
            settings.touchEnabled = checked
            MirrorState.update { it.copy(touchEnabled = checked) }
            if (checked && !MirrorAccessibilityService.isEnabled(this)) {
                accessibilityHint.visibility = View.VISIBLE
            }
        }
        dimScreenSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingBrightnessUi) return@setOnCheckedChangeListener
            settings.dimScreen = checked
            updateBrightnessUi()
            if (!checked) {
                // Выключили — сразу возвращаем обычную яркость.
                refreshBrightnessService()
                return@setOnCheckedChangeListener
            }
            // Включили затемнение: без права менять системные настройки
            // яркость не изменить. Служба доступности для затемнения больше
            // не нужна: она отвечает только за касания.
            if (!RotationController.canWrite(this)) {
                checkingBrightnessPermission = true
                Toast.makeText(this, R.string.brightness_permission_short, Toast.LENGTH_LONG).show()
                openWriteSettings()
                return@setOnCheckedChangeListener
            }
            refreshBrightnessService()
        }

        brightnessDimSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (bindingBrightnessUi) return
                settings.brightnessDimPercent = progress
                updateBrightnessValue()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                settings.brightnessDimPercent = seekBar?.progress ?: settings.brightnessDimPercent
                updateBrightnessValue()
                refreshBrightnessService()
            }
        })

        usbAudioSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingAudioUi) return@setOnCheckedChangeListener
            if (checked && !hasAudioPermission()) {
                pendingAudioSwitch = true
                requestAudioPermission()
                return@setOnCheckedChangeListener
            }
            pendingAudioSwitch = false
            settings.usbAudio = checked
            updateAudioUi()
            refreshAudioService()
        }

        systemLandscapeSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingRotationUi) return@setOnCheckedChangeListener
            settings.systemLandscape = checked
            syncActivityOrientation()
            updateRotationUi()
            if (checked) {
                if (RotationController.canWrite(this)) {
                    if (MirrorState.value.projectionReady) {
                        RotationController.enterLandscape(this)
                    }
                } else {
                    openWriteSettings()
                }
            } else {
                RotationController.restore(this)
            }
        }
        rotationPermissionButton.setOnClickListener {
            checkingRotationPermission = true
            openWriteSettings()
        }

        rememberSplitAppsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.rememberSplitApps = checked
            if (!checked) {
                settings.hasRememberedSplit = false
                settings.rememberedSplitFirstApp = ""
                settings.rememberedSplitSecondApp = ""
            }
            Logx.i("Сохранять последние приложения: $checked")
        }

        accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        notificationWarning.setOnClickListener { openNotificationSettings() }
        detailsToggle.setOnClickListener { toggleDetails() }
        saveLogButton.setOnClickListener { saveLogToDownloads() }
    }

    private fun requiredWirelessPermissions(): Array<String> {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        return permissions.toTypedArray()
    }

    /**
     * Android 17 умеет отдельно ограничивать локальную сеть. При targetSdk 36
     * это обычно ещё не включено, поэтому разрешение просим, но запуск не
     * блокируем: иначе точка доступа могла бы не заработать из-за лишнего отказа.
     */
    private fun optionalLocalNetworkPermission(): String? {
        if (Build.VERSION.SDK_INT < 37) return null
        val name = "android.permission.ACCESS_LOCAL_NETWORK"
        return try {
            packageManager.getPermissionInfo(name, 0)
            name
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun hasWirelessPermissions(): Boolean {
        // Bluetooth нужен только для Wi-Fi Direct. Через точку доступа
        // беспроводной CarLife работает и без него, поэтому не блокируем режим.
        val bluetoothConnect = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            null
        }
        return requiredWirelessPermissions()
            .filter { it != bluetoothConnect }
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestWirelessPermissions() {
        if (wirelessPermissionRequestPending) return
        wirelessPermissionRequestPending = true
        val requested = requiredWirelessPermissions().toMutableList()
        optionalLocalNetworkPermission()?.let { requested += it }
        requestPermissions(requested.toTypedArray(), REQ_WIRELESS)
    }

    private fun selectConnection(mode: ConnectMode) {
        if (settings.connectionMode == mode) {
            if (mode == ConnectMode.WIRELESS && !hasWirelessPermissions()) {
                pendingWirelessMode = true
                requestWirelessPermissions()
            }
            return
        }
        if (mode == ConnectMode.WIRELESS && !hasWirelessPermissions()) {
            pendingWirelessMode = true
            requestWirelessPermissions()
            return
        }
        settings.connectionMode = mode
        Logx.i("Подключение: ${if (mode == ConnectMode.WIRELESS) "Wi-Fi" else "USB"}")
        if (!MirrorState.value.projectionReady) {
            MirrorState.update {
                it.copy(
                    connection = mode,
                    title = if (mode == ConnectMode.WIRELESS) "Готово к Wi-Fi" else "Готово к запуску",
                    detail = if (mode == ConnectMode.WIRELESS) {
                        "Нажмите «Начать», включите точку доступа телефона и подключите к ней магнитолу."
                    } else {
                        "Подключите телефон к магнитоле USB-кабелем и разрешите захват экрана."
                    },
                    error = false,
                )
            }
        }
        updateConnectionUi()
        render(MirrorState.value)
    }

    private fun updateConnectionUi() {
        val mode = settings.connectionMode
        val wireless = mode == ConnectMode.WIRELESS
        connectionUsb.isSelected = !wireless
        connectionWifi.isSelected = wireless
        connectionHint.setText(
            when {
                !wireless -> R.string.connection_hint_usb
                !hasWirelessPermissions() -> R.string.connection_permission_needed
                else -> R.string.connection_hint_wifi
            }
        )
        openWifiSettings.visibility = if (wireless) View.VISIBLE else View.GONE
        val note = MirrorState.value.projectionReady &&
            MirrorState.value.connection != mode
        connectionApplyNote.visibility = if (note) View.VISIBLE else View.GONE
        val prefix = getString(R.string.section_connection) + ", "
        connectionUsb.contentDescription = prefix + getString(R.string.connection_usb)
        connectionWifi.contentDescription = prefix + getString(R.string.connection_wifi)
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS))
        } catch (e: Exception) {
            Logx.e("Не удалось открыть настройки Wi-Fi", e)
            try {
                startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun refreshEnvironment() {
        val usb = getSystemService(UsbManager::class.java)
        val attached = !usb.accessoryList.isNullOrEmpty()
        // Служба может быть включена в настройках, но ещё/уже не подключена
        // после перезапуска процесса. Для статуса достаточно разрешения:
        // после подключения она восстановится сама.
        val accessibility = MirrorAccessibilityService.instance != null ||
            MirrorAccessibilityService.isEnabled(this)
        MirrorState.update {
            it.copy(
                usbAttached = attached,
                accessibilityEnabled = accessibility,
                touchEnabled = settings.touchEnabled,
            )
        }
        if (!MirrorState.value.projectionReady && MirrorState.value.phase == Phase.IDLE) {
            if (settings.connectionMode == ConnectMode.WIRELESS) {
                MirrorState.update {
                    it.copy(
                        title = "Готово к Wi-Fi",
                        detail = "Нажмите «Начать», включите точку доступа телефона и подключите к ней магнитолу.",
                        connection = ConnectMode.WIRELESS,
                        error = false,
                    )
                }
            } else if (attached) {
                MirrorState.update {
                    it.copy(
                        title = "Магнитола подключена",
                        detail = "Нажмите «Начать трансляцию», чтобы разрешить захват экрана.",
                        usbAttached = true,
                        connection = ConnectMode.USB,
                        error = false,
                    )
                }
            }
        }
        autoStartSwitch.isChecked = settings.autoStart
        touchSwitch.isChecked = settings.touchEnabled
        updateConnectionUi()
        updateThemeUi()
        updateSplitUi()
        updateRotationUi()
        updateBrightnessUi()
        updateVideoSettingsUi()
    }

    private fun render(snapshot: MirrorSnapshot) {
        statusTitle.text = snapshot.title
        statusDetail.text = snapshot.detail
        statusProgress.visibility =
            if (snapshot.phase == Phase.CONNECTING || snapshot.phase == Phase.WAITING_WIFI) {
                View.VISIBLE
            } else {
                View.GONE
            }
        val color = if (projectionDenied || missingPermissions()) {
            getColor(R.color.hero_error)
        } else {
            statusColor(snapshot)
        }
        heroIcon.imageTintList = ColorStateList.valueOf(color)

        val streaming = snapshot.projectionReady
        if (streaming != lastProjectionReady) {
            lastProjectionReady = streaming
            syncActivityOrientation()
            syncSystemRotation()
        }
        val retry = streaming && snapshot.phase == Phase.ERROR
        primaryButton.visibility = View.VISIBLE
        primaryButton.text = when {
            retry -> getString(R.string.retry)
            streaming -> getString(R.string.stop_mirror)
            snapshot.phase == Phase.ERROR -> getString(R.string.retry)
            else -> getString(R.string.start_mirror)
        }
        primaryButton.setBackgroundResource(
            if (streaming && !retry) R.drawable.bg_button_danger else R.drawable.bg_button_primary
        )
        primaryButton.setTextColor(
            getColor(if (streaming && !retry) R.color.error else R.color.on_accent)
        )
        primaryButton.contentDescription = getString(
            when {
                retry -> R.string.retry
                streaming -> R.string.primary_stop_desc
                else -> R.string.primary_start_desc
            }
        )

        splitButton.visibility = View.VISIBLE
        val splitEnabled = streaming && !settings.splitAuto && !splitRecreateInProgress
        splitButton.isEnabled = splitEnabled
        splitButton.alpha = if (splitEnabled) 1f else 0.48f
        val splitColor = getColor(if (splitEnabled) R.color.accent else R.color.text_muted)
        splitButtonLabel.setTextColor(splitColor)
        splitIcon.imageTintList = ColorStateList.valueOf(splitColor)
        splitButton.contentDescription = getString(R.string.split_screen_desc)

        val second = effectiveSplitPair().second
        splitHint.text = when {
            !streaming -> getString(R.string.split_hint_need_stream)
            settings.splitAuto -> getString(R.string.split_hint_auto)
            second == null -> getString(R.string.split_hint_choose_apps)
            else -> ""
        }
        splitHint.visibility = if (splitHint.text.isBlank()) View.GONE else View.VISIBLE

        statusMeta.text = buildMeta(snapshot)
        statusMeta.setCompoundDrawablesWithIntrinsicBounds(
            if ((if (snapshot.projectionReady) snapshot.connection else settings.connectionMode) ==
                ConnectMode.WIRELESS
            ) R.drawable.ic_wifi else R.drawable.ic_usb,
            0,
            0,
            0,
        )
        statusMeta.compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.text_muted))
        notificationWarning.visibility = if (streaming && !notificationsAvailable()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateConnectionUi()
        syncOrientation(snapshot)
        updateImmersiveMode()

        accessibilityStatus.text = getString(
            if (snapshot.accessibilityEnabled) R.string.accessibility_on else R.string.accessibility_off
        )
        accessibilityStatus.setTextColor(getColor(
            if (snapshot.accessibilityEnabled) R.color.success else R.color.text_muted
        ))
        accessibilityButton.text = getString(
            if (snapshot.accessibilityEnabled) R.string.accessibility_check else R.string.accessibility_setup
        )
        accessibilityHint.visibility = if (snapshot.accessibilityEnabled) View.GONE else View.VISIBLE

        if (detailsVisible) detailsText.text = buildDetails(snapshot)

        if (!streaming) {
            splitRequested = false
            splitRecreateInProgress = false
            multiWindowObserved = false
            splitRatioApplyAttempts = 0
            window.decorView.removeCallbacks(applySplitRatio)
        }
        maybeLeaveAppAfterStart(snapshot)
        maybeAutoSplit(snapshot)
    }

    private fun scheduleSplitRatioApply(initialDelayMs: Long) {
        if (!settings.splitSessionActive) return
        splitRatioApplyAttempts = 0
        MirrorAccessibilityService.instance?.resetSplitRatioApply()
        window.decorView.removeCallbacks(applySplitRatio)
        window.decorView.postDelayed(applySplitRatio, initialDelayMs)
    }

    private fun maybeLeaveAppAfterStart(snapshot: MirrorSnapshot) {
        if (!snapshot.projectionReady) {
            if (settings.autoHomeDoneForSession) {
                settings.autoHomeDoneForSession = false
            }
            window.decorView.removeCallbacks(goHomeAfterStart)
            return
        }
        if (settings.autoHomeDoneForSession) return
        if (settings.connectionMode == ConnectMode.WIRELESS &&
            snapshot.phase != Phase.STREAMING && snapshot.phase != Phase.PAUSED
        ) {
            // В Wi-Fi-режиме пользователю нужно видеть подсказку и открыть
            // точку доступа. Уходим на рабочий стол только после подключения.
            window.decorView.removeCallbacks(goHomeAfterStart)
            return
        }
        settings.autoHomeDoneForSession = true
        if (settings.splitAuto) {
            Logx.i("Старт: автосплит включён, приложение остаётся до появления split")
            return
        }
        Logx.i("Старт: автосплит выключен, через ${HOME_AFTER_START_DELAY_MS} мс ухожу на рабочий стол")
        window.decorView.removeCallbacks(goHomeAfterStart)
        window.decorView.postDelayed(goHomeAfterStart, HOME_AFTER_START_DELAY_MS)
    }

    private fun statusColor(snapshot: MirrorSnapshot): Int = when {
        snapshot.error || snapshot.phase == Phase.ERROR -> getColor(R.color.error)
        snapshot.phase == Phase.STREAMING -> getColor(R.color.success)
        snapshot.phase == Phase.CONNECTING ||
            snapshot.phase == Phase.WAITING_USB ||
            snapshot.phase == Phase.WAITING_WIFI ||
            snapshot.phase == Phase.PAUSED -> getColor(R.color.warning)
        else -> getColor(R.color.text_muted)
    }

    private fun missingPermissions(): Boolean =
        (settings.systemLandscape && !RotationController.canWrite(this)) ||
            (settings.touchEnabled && !MirrorAccessibilityService.isEnabled(this)) ||
            (settings.connectionMode == ConnectMode.WIRELESS && !hasWirelessPermissions())

    private fun buildMeta(snapshot: MirrorSnapshot): String {
        val parts = ArrayList<String>()
        val mode = if (snapshot.projectionReady) snapshot.connection else settings.connectionMode
        if (mode == ConnectMode.WIRELESS) {
            parts.add(
                getString(
                    if (snapshot.phase == Phase.WAITING_WIFI) {
                        R.string.connection_wifi_waiting
                    } else {
                        R.string.connection_wifi_ready
                    }
                )
            )
        } else {
            parts.add(
                getString(
                    if (snapshot.usbAttached) R.string.usb_connected else R.string.usb_not_connected
                )
            )
        }
        if (snapshot.hu.isNotBlank()) parts.add(snapshot.hu)
        if (snapshot.video.isNotBlank()) parts.add(snapshot.video)
        return parts.joinToString("  ·  ")
    }

    private fun showSettings() {
        if (settingsVisible) return
        dismissPermissionPrompt(markDismissed = true)
        settingsVisible = true
        clearInputFocus()
        refreshEnvironment()
        updateVideoSettingsUi()
        updateSplitUi()
        updateRotationUi()
        render(MirrorState.value)
        settingsScroll.scrollTo(0, 0)
        rootScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settingsPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.alpha = 0f
        settingsPanel.translationY = dp(18)
        settingsPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
        settingsBack.post { settingsBack.requestFocus() }
    }

    private fun hideSettings() {
        if (!settingsVisible) return
        settingsVisible = false
        clearInputFocus()
        settingsPanel.animate()
            .alpha(0f)
            .translationY(dp(18))
            .setDuration(170L)
            .withEndAction {
                settingsPanel.visibility = View.GONE
                settingsPanel.importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                rootScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                settingsPanel.translationY = 0f
                settingsButton.requestFocus()
                render(MirrorState.value)
                maybeAutoStart()
            }
            .start()
    }

    private fun dp(value: Int): Float =
        value * resources.displayMetrics.density

    private fun rotationPermissionMissing(): Boolean =
        settings.systemLandscape && !RotationController.canWrite(this)

    private fun accessibilityPermissionMissing(): Boolean =
        settings.touchEnabled && !MirrorAccessibilityService.isEnabled(this)

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        if (audioPermissionRequestPending) return
        audioPermissionRequestPending = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    private fun updateAudioUi() {
        bindingAudioUi = true
        try {
            usbAudioSwitch.isChecked = settings.usbAudio
            usbAudioNote.visibility =
                if (settings.usbAudio && !hasAudioPermission()) View.VISIBLE else View.GONE
        } finally {
            bindingAudioUi = false
        }
    }

    private fun refreshAudioService() {
        if (!MirrorState.value.projectionReady) return
        try {
            startService(
                Intent(this, MirrorService::class.java)
                    .setAction(MirrorService.ACTION_REFRESH_AUDIO)
            )
        } catch (e: Exception) {
            Logx.w("Звук: не удалось обновить настройку в сервисе: ${e.message}")
        }
    }

    private fun maybeShowPermissionPrompt() {
        if (permissionPromptShown || permissionPromptDismissed ||
            settingsVisible || MirrorState.value.projectionReady
        ) {
            return
        }
        if (!rotationPermissionMissing() && !accessibilityPermissionMissing() &&
            hasAudioPermission()
        ) {
            return
        }
        permissionPromptShown = true
        val view = layoutInflater.inflate(
            R.layout.dialog_permissions,
            findViewById<android.view.ViewGroup>(android.R.id.content),
            false,
        )
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = minOf(
                    (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    dp(480).toInt(),
                )
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
                val attrs = attributes
                attrs.dimAmount = 0.72f
                attributes = attrs
            }
        }
        permissionDialog = dialog
        dialog.show()
        updatePermissionDialog()
    }

    private fun updatePermissionDialog() {
        val dialog = permissionDialog ?: return
        val rotationRow = dialog.findViewById<View>(R.id.permissionRotationRow) ?: return
        val accessibilityRow = dialog.findViewById<View>(R.id.permissionAccessibilityRow)
        val audioRow = dialog.findViewById<View>(R.id.permissionAudioRow)
        val rotationStatus = dialog.findViewById<TextView>(R.id.permissionRotationStatus)
        val accessibilityStatus = dialog.findViewById<TextView>(R.id.permissionAccessibilityStatus)
        val audioStatus = dialog.findViewById<TextView>(R.id.permissionAudioStatus)
        val rotationAction = dialog.findViewById<TextView>(R.id.permissionRotationAction)
        val accessibilityAction = dialog.findViewById<TextView>(R.id.permissionAccessibilityAction)
        val audioAction = dialog.findViewById<TextView>(R.id.permissionAudioAction)
        val rotationNeeded = rotationPermissionMissing()
        val accessibilityNeeded = accessibilityPermissionMissing()
        val audioNeeded = !hasAudioPermission()
        val needed = listOf(rotationNeeded, accessibilityNeeded, audioNeeded).count { it }
        if (needed == 0) {
            dialog.dismiss()
            permissionDialog = null
            permissionPromptDismissed = true
            window.decorView.postDelayed({ maybeAutoStart() }, 250)
            return
        }
        rotationRow.visibility = if (rotationNeeded) View.VISIBLE else View.GONE
        accessibilityRow?.visibility = if (accessibilityNeeded) View.VISIBLE else View.GONE
        audioRow?.visibility = if (audioNeeded) View.VISIBLE else View.GONE
        dialog.findViewById<TextView>(R.id.permissionDialogSub)?.text = getString(
            if (needed == 1) R.string.permission_dialog_sub_one else R.string.permission_dialog_sub
        )
        bindPermissionStatus(rotationStatus, rotationAction, rotationNeeded)
        bindPermissionStatus(accessibilityStatus, accessibilityAction, accessibilityNeeded)
        bindPermissionStatus(audioStatus, audioAction, audioNeeded)
        rotationRow.setOnClickListener { openWriteSettings() }
        accessibilityRow?.setOnClickListener { openAccessibilitySettings() }
        audioRow?.setOnClickListener {
            pendingAudioSwitch = false
            if (!hasAudioPermission()) requestAudioPermission()
            else updatePermissionDialog()
        }
        dialog.findViewById<TextView>(R.id.permissionLater)?.setOnClickListener {
            dismissPermissionPrompt(markDismissed = true)
        }
    }

    private fun bindPermissionStatus(status: TextView?, action: TextView?, needed: Boolean) {
        status?.text = getString(
            if (needed) R.string.permission_not_granted else R.string.permission_granted
        )
        status?.setTextColor(getColor(if (needed) R.color.warning else R.color.success))
        action?.text = getString(
            if (needed) R.string.permission_allow else R.string.permission_check
        )
    }

    private fun dismissPermissionPrompt(markDismissed: Boolean) {
        permissionDialog?.dismiss()
        permissionDialog = null
        if (markDismissed) permissionPromptDismissed = true
        window.decorView.postDelayed({ maybeAutoStart() }, 250)
    }

    private fun buildDetails(snapshot: MirrorSnapshot): String {
        val usb = getSystemService(UsbManager::class.java)
        val accessory = usb.accessoryList?.firstOrNull()?.let { describe(it) }.orEmpty()
        val sb = StringBuilder()
        sb.appendLine("Состояние: ${snapshot.phase}")
        val activeMode = if (snapshot.projectionReady) snapshot.connection else settings.connectionMode
        sb.appendLine("Подключение: " + if (activeMode == ConnectMode.WIRELESS) "Wi-Fi" else "USB")
        if (snapshot.linkState.isNotBlank()) sb.appendLine("Канал: ${snapshot.linkState}")
        sb.appendLine("USB: ${if (snapshot.usbAttached) accessory.ifBlank { "подключён" } else "нет"}")
        sb.appendLine("ГУ: ${snapshot.hu.ifBlank { "не определена" }}")
        sb.appendLine("Видео: ${snapshot.video.ifBlank { "не запущено" }}")
        sb.appendLine("Звук: ${if (settings.usbAudio) "включён" else "выключен"}")
        sb.appendLine("Кадры: ${snapshot.frames}, отброшено: ${snapshot.dropped}")
        sb.appendLine("Управление: ${if (snapshot.touchEnabled) "включено" else "выключено"}")
        sb.appendLine("Служба доступности: ${if (snapshot.accessibilityEnabled) "включена" else "выключена"}")
        sb.appendLine("Журнал: ${Logx.currentFilePath()}")
        sb.appendLine(getString(R.string.log_auto_note))
        sb.appendLine()
        sb.appendLine("Журнал (последние строки):")
        sb.append(Logx.snapshot().lineSequence().toList().takeLast(30).joinToString("\n"))
        return sb.toString().trimEnd()
    }

    @Suppress("DEPRECATION")
    private fun requestProjection() {
        autoStartAttempted = true
        // Своё разрешение проверяем до поворота: при ошибке экран не должен дёргаться.
        if (settings.customResolution && !saveCustomSize(showError = true)) {
            customWidth.requestFocus()
            customSizeError.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            customSizeError.post {
                // Добавляем запас снизу, иначе текст ошибки оказывается под
                // системной панелью навигации в edge-to-edge режиме.
                val extra = (56 * resources.displayMetrics.density).toInt()
                customSizeError.requestRectangleOnScreen(
                    Rect(0, 0, customSizeError.width, customSizeError.height + extra),
                    true,
                )
            }
            return
        }
        clearInputFocus()
        if (settings.systemLandscape && !RotationController.canWrite(this)) {
            Toast.makeText(this, R.string.rotation_permission_short, Toast.LENGTH_LONG).show()
        }
        awaitingConsent = true
        startingService = false
        // При повторном захвате Activity уже может быть в ландшафте:
        // системный диалог согласия показываем в портрете.
        stopping = MirrorState.value.projectionReady
        pendingServiceStart = null
        window.decorView.removeCallbacks(startAfterRotation)
        // Системный диалог захвата показываем в портрете: в ландшафте он
        // не помещается по высоте и требует прокрутки. Поворот выполняем
        // после подтверждения, до создания VirtualDisplay.
        beginProjectionFlow()
    }

    private fun beginProjectionFlow() {
        if (!awaitingConsent) return
        if (settings.connectionMode == ConnectMode.WIRELESS && !hasWirelessPermissions()) {
            pendingWirelessForProjection = true
            requestWirelessPermissions()
            return
        }
        if (settings.usbAudio && !hasAudioPermission()) {
            requestAudioPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            projectionPending = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            return
        }
        launchProjection()
    }

    private fun setMirrorLandscape(landscape: Boolean) {
        if (mirrorLandscape == landscape) return
        mirrorLandscape = landscape
        // При смене режима показываем статус и кнопку остановки, а не
        // настройки, до которых пользователь мог прокрутить экран.
        rootScroll.scrollTo(0, 0)
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun clearInputFocus() {
        currentFocus?.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun syncOrientation(snapshot: MirrorSnapshot) {
        if (snapshot.phase == Phase.IDLE) stopping = false
        if (snapshot.projectionReady ||
            snapshot.phase == Phase.ERROR ||
            snapshot.phase == Phase.IDLE
        ) {
            startingService = false
        }
        val landscape = !stopping && (snapshot.projectionReady || startingService)
        setMirrorLandscape(landscape)
    }

    private fun launchProjection() {
        projectionDenied = false
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                try {
                    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } catch (e: Exception) {
                    Logx.w("MediaProjectionConfig недоступен: ${e.message}; обычный запрос")
                    mpm.createScreenCaptureIntent()
                }
            } else {
                mpm.createScreenCaptureIntent()
            }
            startActivityForResult(captureIntent, REQ_PROJECTION)
            MirrorState.update {
                it.copy(
                    phase = Phase.CONNECTING,
                    title = getString(R.string.waiting_permission),
                    detail = getString(R.string.permission_detail),
                    error = false,
                )
            }
        } catch (e: Exception) {
            Logx.e("Не удалось открыть диалог MediaProjection", e)
            awaitingConsent = false
            startingService = false
            MirrorState.update {
                it.copy(
                    phase = Phase.ERROR,
                    title = "Захват экрана недоступен",
                    detail = "Система не разрешила открыть диалог захвата: ${e.message ?: e.javaClass.simpleName}",
                    error = true,
                )
            }
            setMirrorLandscape(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        awaitingConsent = false
        projectionPending = false
        stopping = false
        pendingServiceStart = null
        window.decorView.removeCallbacks(startAfterRotation)
        if (resultCode == RESULT_OK && data != null) {
            projectionDenied = false
            startingService = true
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                startMirrorService(data)
            } else {
                // Сначала даём Activity повернуться, и только потом создаём
                // VirtualDisplay в сервисе, иначе захват зафиксирует портрет.
                pendingServiceStart = { startMirrorService(data) }
                setMirrorLandscape(true)
                window.decorView.postDelayed(startAfterRotation, ORIENTATION_FALLBACK_MS)
            }
        } else {
            projectionDenied = true
            startingService = false
            if (MirrorState.value.projectionReady) stopMirror()
            MirrorState.update {
                it.copy(
                    phase = Phase.IDLE,
                    title = "Разрешение не выдано",
                    detail = "Без захвата экрана трансляция невозможна.",
                    error = true,
                )
            }
            setMirrorLandscape(false)
        }
    }

    private fun startMirrorService(data: Intent) {
        val intent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
            putExtra(MirrorService.EXTRA_RESULT_CODE, RESULT_OK)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            putExtra(MirrorService.EXTRA_REPLACE_PROJECTION, true)
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Logx.e("Не удалось запустить сервис", e)
            startingService = false
            pendingServiceStart = null
            MirrorState.update {
                it.copy(
                    phase = Phase.ERROR,
                    title = "Не удалось запустить сервис",
                    detail = e.message ?: e.javaClass.simpleName,
                    error = true,
                )
            }
            setMirrorLandscape(false)
        }
    }

    private fun retryMirror() {
        Logx.i("Повтор: запрашиваю новый захват экрана")
        requestProjection()
    }

    private fun stopMirror(closeApp: Boolean = false) {
        stopping = true
        awaitingConsent = false
        startingService = false
        projectionPending = false
        pendingServiceStart = null
        pendingFirstLaunch = null
        firstLaunchAttempts = 0
        firstRetryScheduled = false
        window.decorView.removeCallbacks(startAfterRotation)
        clearInputFocus()
        val intent = Intent(this, MirrorService::class.java)
            .setAction(MirrorService.ACTION_STOP)
        if (closeApp) intent.putExtra(MirrorService.EXTRA_CLOSE_APP, true)
        try {
            startService(intent)
        } catch (e: Exception) {
            stopService(Intent(this, MirrorService::class.java))
            setMirrorLandscape(false)
            RotationController.restore(this)
        }
    }

    private fun selectPreset(value: Resolution) {
        settings.resolution = value
        settings.customResolution = false
        customSizeError.visibility = View.GONE
        updateVideoSettingsUi()
    }

    private fun selectFps(value: Int) {
        settings.fps = value
        updateFpsUi()
    }

    private fun updateVideoSettingsUi() {
        autoStartSwitch.isChecked = settings.autoStart
        updateResolutionUi()
        updateFpsUi()
        updateAudioUi()
    }

    private fun updateThemeUi() {
        themeSystem.isSelected = settings.themeMode == AppTheme.SYSTEM
        themeLight.isSelected = settings.themeMode == AppTheme.LIGHT
        themeDark.isSelected = settings.themeMode == AppTheme.DARK
    }

    private fun selectTheme(mode: AppTheme) {
        if (settings.themeMode == mode) return
        settings.themeMode = mode
        intent.putExtra(EXTRA_OPEN_SETTINGS, true)
        recreate()
    }

    private fun updateResolutionUi() {
        bindingVideoUi = true
        try {
            val custom = settings.customResolution
            val preset = settings.resolution
            qualityAuto.isSelected = !custom && preset == Resolution.AUTO
            quality720.isSelected = !custom && preset == Resolution.P720
            quality1080.isSelected = !custom && preset == Resolution.P1080
            quality2k.isSelected = !custom && preset == Resolution.P2K

            val prefix = getString(R.string.resolution_title) + ", "
            qualityAuto.contentDescription = prefix + getString(R.string.quality_auto)
            quality720.contentDescription = prefix + getString(R.string.quality_720)
            quality1080.contentDescription = prefix + getString(R.string.quality_1080)
            quality2k.contentDescription = prefix + getString(R.string.quality_2k)

            customSizeSwitch.isChecked = custom
            customSizeRow.visibility = if (custom) View.VISIBLE else View.GONE
            customSizeError.visibility = View.GONE
            if (custom) fillCustomFields()
        } finally {
            bindingVideoUi = false
        }
    }

    private fun updateFpsUi() {
        val fps = settings.fps
        fps15.isSelected = fps == 15
        fps30.isSelected = fps == 30
        fps60.isSelected = fps == 60
        fps15.alpha = 1f
        fps30.alpha = 1f
        fps60.alpha = 1f
        val prefix = getString(R.string.fps_title) + ", "
        fps15.contentDescription = prefix + getString(R.string.fps_15)
        fps30.contentDescription = prefix + getString(R.string.fps_30)
        fps60.contentDescription = prefix + getString(R.string.fps_60)
    }

    private fun fillCustomFields() {
        val w = settings.customWidth.toString()
        val h = settings.customHeight.toString()
        if (customWidth.text.toString() != w) customWidth.setText(w)
        if (customHeight.text.toString() != h) customHeight.setText(h)
    }

    private fun saveCustomSize(showError: Boolean): Boolean {
        val w = customWidth.text.toString().toIntOrNull()
        val h = customHeight.text.toString().toIntOrNull()
        if (w != null && h != null && Handshake.isValidVideoSize(w, h)) {
            settings.customWidth = w
            settings.customHeight = h
            customSizeError.visibility = View.GONE
            updateFpsUi()
            return true
        }
        customSizeError.visibility =
            if (showError && settings.customResolution) View.VISIBLE else View.GONE
        return false
    }

    // ---------------- разделённый экран ----------------

    private fun updateSplitUi() {
        val selectedSecond = resolveRawComponent(settings.splitApp)
        val selectedSecondLabel = selectedSecond?.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        val hasSelectedSecond = selectedSecondLabel.isNotBlank()
        val selectedFirst = resolveRawComponent(settings.splitAppFirst)
        val selectedFirstLabel = selectedFirst?.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        val hasSelectedFirst = selectedFirstLabel.isNotBlank()
        splitFirstAppValue.text = if (hasSelectedFirst) selectedFirstLabel else getString(R.string.split_first_app_default)
        splitFirstAppValue.setTextColor(
            getColor(if (hasSelectedFirst) R.color.text_primary else R.color.text_muted)
        )
        splitAppValue.text = if (hasSelectedSecond) selectedSecondLabel else getString(R.string.split_app_none)
        splitAppValue.setTextColor(
            getColor(if (hasSelectedSecond) R.color.text_primary else R.color.text_muted)
        )
        splitAutoSwitch.isChecked = settings.splitAuto
        splitAutoSwitch.isEnabled = true
        splitAutoSwitch.alpha = 1f
        splitOptions.visibility = if (settings.splitAuto) View.VISIBLE else View.GONE
        bindingSplitUi = true
        try {
            splitRatioSeek.progress = settings.splitRatioFirst / 10
            updateSplitRatioLabel()
        } finally {
            bindingSplitUi = false
        }
        rememberSplitAppsSwitch.isChecked = settings.rememberSplitApps
        splitFirstAppRow.contentDescription = getString(R.string.split_first_app_title) + ": " +
            (if (hasSelectedFirst) selectedFirstLabel else getString(R.string.split_first_app_default))
        splitAppRow.contentDescription = getString(R.string.split_app_title) + ": " +
            (if (hasSelectedSecond) selectedSecondLabel else getString(R.string.split_app_none))
    }

    private fun updateSplitRatioLabel() {
        val first = settings.splitRatioFirst
        val second = 100 - first
        val text = getString(R.string.split_ratio_value, first, second)
        splitRatioValue.text = text
        splitRatioSeek.contentDescription = getString(R.string.split_ratio_title) + ": " + text
    }

    private fun updateRotationUi() {
        bindingRotationUi = true
        try {
            systemLandscapeSwitch.isChecked = settings.systemLandscape
            val granted = RotationController.canWrite(this)
            rotationPermissionStatus.text = getString(
                if (granted) R.string.rotation_permission_on else R.string.rotation_permission_off
            )
            rotationPermissionStatus.setTextColor(
                getColor(if (granted) R.color.success else R.color.warning)
            )
            rotationPermissionButton.text = getString(
                if (granted) R.string.permission_check else R.string.permission_allow
            )
        } finally {
            bindingRotationUi = false
        }
    }

    /**
     * Показывает переключатель «Гасить экран» и строку процента только когда
     * затемнение включено. Выключенная настройка не должна занимать место
     * и не должна выглядеть как рабочая.
     */
    private fun updateBrightnessUi() {
        bindingBrightnessUi = true
        try {
            val enabled = settings.dimScreen
            dimScreenSwitch.isChecked = enabled
            brightnessDimOptions.visibility = if (enabled) View.VISIBLE else View.GONE
            brightnessDimSeek.progress = settings.brightnessDimPercent
            updateBrightnessValue()
            updateBrightnessWarning(enabled)
        } finally {
            bindingBrightnessUi = false
        }
    }

    /**
     * Если затемнение включено, но не хватает разрешения или службы управления,
     * экран не изменится. Пишем об этом прямо под ползунком, а не оставляем
     * включённый переключатель без объяснений.
     */
    private fun updateBrightnessWarning(enabled: Boolean) {
        val text = when {
            !enabled -> null
            !RotationController.canWrite(this) -> getString(R.string.brightness_permission_not_granted)
            !MirrorAccessibilityService.isEnabled(this) ->
                getString(R.string.brightness_accessibility_not_enabled)
            else -> null
        }
        brightnessDimWarning.text = text.orEmpty()
        brightnessDimWarning.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun updateBrightnessValue() {
        val percent = settings.brightnessDimPercent
        val text = getString(R.string.brightness_dim_value, percent)
        brightnessDimValue.text = text
        brightnessDimSeek.contentDescription = getString(R.string.brightness_dim_title) + ": " + text
    }

    /**
     * Приводит системный поворот в соответствие с настройкой и текущей сессией.
     * Вызывается при возврате из системных настроек и при открытии Activity.
     */
    private fun syncSystemRotation() {
        syncActivityOrientation()
        val streaming = MirrorState.value.projectionReady
        if (streaming && settings.systemLandscape) {
            if (RotationController.canWrite(this)) {
                RotationController.enterLandscape(this)
            }
        } else {
            RotationController.restore(this)
        }
        updateRotationUi()
    }

    /**
     * MainActivity объявлена portrait-only, поэтому сама удерживает дисплей
     * в портрете даже после записи USER_ROTATION. На время трансляции
     * разрешаем ей landscape, иначе системный экран не поворачивается.
     */
    private fun syncActivityOrientation() {
        val target = if (MirrorState.value.projectionReady && settings.systemLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != target) {
            requestedOrientation = target
        }
    }

    /**
     * Возвращает launcher-activity выбранного приложения. Если его удалили или
     * отключили, настройки очищаются, чтобы автозапуск не пытался открыть пустоту.
     */
    private fun resolveSplitTarget(clearIfMissing: Boolean = true): ActivityInfo? {
        val raw = settings.splitApp
        if (raw.isBlank()) return null
        val component = ComponentName.unflattenFromString(raw)
        val info = if (component == null || component.packageName == packageName) {
            null
        } else {
            try {
                packageManager.getActivityInfo(component, 0)
            } catch (_: Exception) {
                null
            }
        }
        if (info == null || !info.enabled || info.applicationInfo?.enabled != true) {
            if (clearIfMissing) {
                settings.splitApp = ""
                settings.splitAuto = false
            }
            return null
        }
        return info
    }

    private fun resolveRawComponent(raw: String): ActivityInfo? {
        if (raw.isBlank()) return null
        val component = ComponentName.unflattenFromString(raw) ?: return null
        if (component.packageName == packageName) return null
        return try {
            val info = packageManager.getActivityInfo(component, 0)
            if (info.enabled && info.applicationInfo?.enabled == true) info else null
        } catch (_: Exception) {
            null
        }
    }

    private data class SplitPair(val first: ActivityInfo?, val second: ActivityInfo?)

    /**
     * Что открывать сейчас:
     * - текущая сессия — реальные окна, которые видит трекер;
     * - новый запуск при включённом запоминании — окна прошлой сессии;
     * - иначе (или если запомненных нет) — приложения из настроек.
     */
    private fun effectiveSplitPair(): SplitPair {
        val useLive = settings.splitSessionActive && settings.lastSplitApp.isNotBlank()
        val useRemembered = !useLive && settings.rememberSplitApps && settings.hasRememberedSplit
        val rawFirst = when {
            useLive -> settings.lastSplitFirstApp
            useRemembered -> settings.rememberedSplitFirstApp
            else -> settings.splitAppFirst
        }
        val rawSecond = when {
            useLive -> settings.lastSplitApp
            useRemembered -> settings.rememberedSplitSecondApp
            else -> settings.splitApp
        }
        var first = resolveRawComponent(rawFirst)
        var second = resolveRawComponent(rawSecond)
        if (second == null) {
            if (useRemembered) {
                settings.hasRememberedSplit = false
                settings.rememberedSplitFirstApp = ""
                settings.rememberedSplitSecondApp = ""
                Logx.w("Разделение экрана: запомненное второе приложение недоступно, беру настройки")
            }
            second = resolveSplitTarget()
            first = resolveFirstTarget()
        } else if (first == null && rawFirst.isNotBlank()) {
            first = resolveFirstTarget()
        }

        // Страховка от устаревшей пары в last/remembered: если оба окна
        // оказались одним и тем же компонентом, это не даёт запустить split,
        // хотя в настройках выбраны разные приложения.
        if (first != null && second != null && sameActivity(first, second)) {
            val badFirst = ComponentName(first.packageName, first.name).flattenToString()
            val badSecond = ComponentName(second.packageName, second.name).flattenToString()
            Logx.w(
                "Разделение экрана: сохранённая пара совпала ($badFirst / $badSecond); " +
                    "беру приложения из настроек"
            )
            val configuredFirst = resolveFirstTarget(clearIfMissing = false)
            val configuredSecond = resolveSplitTarget(clearIfMissing = false)
            if (configuredSecond != null &&
                (configuredFirst == null || !sameActivity(configuredFirst, configuredSecond))
            ) {
                first = configuredFirst
                second = configuredSecond
                if (useRemembered) {
                    settings.hasRememberedSplit = false
                    settings.rememberedSplitFirstApp = ""
                    settings.rememberedSplitSecondApp = ""
                }
                if (useLive) {
                    settings.lastSplitFirstApp = ""
                    settings.lastSplitApp = ""
                }
            }
        }
        return SplitPair(first, second)
    }

    /**
     * Первое окно необязательно: пустое значение означает сам CarLife Mirror.
     * Если выбранное приложение удалили, возвращаемся к значению по умолчанию.
     */
    private fun resolveFirstTarget(clearIfMissing: Boolean = true): ActivityInfo? {
        val raw = settings.splitAppFirst
        if (raw.isBlank()) return null
        val component = ComponentName.unflattenFromString(raw)
        val info = if (component == null || component.packageName == packageName) {
            null
        } else {
            try {
                packageManager.getActivityInfo(component, 0)
            } catch (_: Exception) {
                null
            }
        }
        if (info == null || !info.enabled || info.applicationInfo?.enabled != true) {
            if (clearIfMissing) settings.splitAppFirst = ""
            return null
        }
        return info
    }

    private fun requestSplitScreen() {
        if (!MirrorState.value.projectionReady) {
            Toast.makeText(this, R.string.split_need_stream, Toast.LENGTH_SHORT).show()
            return
        }
        if (splitRecreateInProgress) return
        val pair = effectiveSplitPair()
        val second = pair.second
        if (second == null) {
            updateSplitUi()
            showSplitAppPicker(SplitSlot.SECOND, launchAfterPick = true)
            return
        }
        val first = pair.first
        if (first != null && sameActivity(first, second)) {
            Toast.makeText(this, R.string.split_same_app, Toast.LENGTH_LONG).show()
            showSplitAppPicker(SplitSlot.FIRST, launchAfterPick = false)
            return
        }

        // Если split уже был открыт и остался в фоне, повторный LAUNCH_ADJACENT
        // из полноэкранного CarLife открывает только второе приложение во весь
        // экран. Сначала закрываем старую пару и затем строим split заново.
        if (settings.splitSessionActive && !isInMultiWindowMode && !multiWindowObserved) {
            val oldPackages = SplitApps.packages(settings, packageName)
            if (oldPackages.isNotEmpty()) {
                splitRecreateInProgress = true
                render(MirrorState.value)
                Logx.i("Разделение экрана: старый split в фоне, пересоздаю пару")
                // Home уводит старую split-пару в фон, затем её приложения
                // завершаются. После этого возвращаем нашу задачу без создания
                // второй копии и строим split заново.
                SplitApps.close(this, oldPackages) {
                    if (isFinishing || isDestroyed) {
                        splitRecreateInProgress = false
                        return@close
                    }
                    SplitApps.finishSplitHostTask(this)
                    SplitApps.bringMainTaskToFront(this)
                    bringSelfToFront()
                    window.decorView.postDelayed({
                        splitRecreateInProgress = false
                        if (!isFinishing && !isDestroyed && MirrorState.value.projectionReady) {
                            render(MirrorState.value)
                            openSplitPair(first, second)
                        } else {
                            render(MirrorState.value)
                        }
                    }, SPLIT_REOPEN_SETTLE_MS)
                }
                return
            }
        }

        openSplitPair(first, second)
    }

    /**
     * Возвращает именно этот экземпляр MainActivity после Home. Задача уже
     * поднята через ActivityManager, поэтому CLEAR_TOP + SINGLE_TOP не создают
     * новую копию, а лишь доводят главный экран до верха.
     */
    private fun bringSelfToFront() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Logx.w("Не удалось вернуть главный экран: ${e.message}")
        }
    }

    /**
     * Для split важны два разных приложения, а не две Activity одного
     * пакета: выбрать одну и ту же программу в обе панели нельзя.
     */
    private fun sameActivity(a: ActivityInfo, b: ActivityInfo): Boolean =
        a.packageName == b.packageName

    /**
     * Второе приложение открывается соседним окном, затем первое заменяет
     * CarLife Mirror в его половине. Если первое не выбрано, остаётся
     * привычная пара CarLife Mirror + второе приложение.
     */
    private fun openSplitPair(first: ActivityInfo?, second: ActivityInfo) {
        if (!MirrorState.value.projectionReady) {
            Toast.makeText(this, R.string.split_need_stream, Toast.LENGTH_SHORT).show()
            return
        }
        if (first != null && sameActivity(first, second)) {
            Toast.makeText(this, R.string.split_same_app, Toast.LENGTH_LONG).show()
            return
        }
        // Перед созданием split повторно проверяем системный автоповорот:
        // Samsung иногда сбрасывает его после смены конфигурации Activity.
        if (settings.systemLandscape && RotationController.canWrite(this)) {
            RotationController.enterLandscape(this)
        }
        splitRequested = true
        settings.splitSessionActive = true
        pendingFirstLaunch = null
        firstLaunchAttempts = 0
        firstRetryScheduled = false
        val secondComponent = ComponentName(second.packageName, second.name)
        settings.lastSplitApp = secondComponent.flattenToString()
        settings.lastSplitFirstApp = first?.let {
            ComponentName(it.packageName, it.name).flattenToString()
        }.orEmpty()
        val secondLabel = second.loadLabel(packageManager).toString()

        // Оба приложения split должны жить в отдельной задаче-хосте. Иначе
        // первое приложение оказывается поверх MainActivity в одной задаче:
        // уведомление открывает CarLife прямо в панели, а «Недавние» — чужое
        // приложение вместо CarLife.
        if (first != null) {
            val firstLabel = first.loadLabel(packageManager).toString()
            Logx.i("Разделение экрана: $firstLabel + $secondLabel (host-задача)")
            launchSplitHost(first, second)
            return
        }

        val secondIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = secondComponent
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        try {
            Logx.i("Разделение экрана: CarLife Mirror + $secondLabel")
            startActivity(secondIntent)
            scheduleSplitRatioApply(SPLIT_RATIO_INITIAL_DELAY_MS)
            window.decorView.postDelayed({ logSplitResult(secondLabel) }, SPLIT_CHECK_DELAY_MS)
        } catch (e: Exception) {
            Logx.e("Не удалось открыть второе приложение", e)
            Toast.makeText(
                this,
                getString(R.string.split_open_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun launchSplitHost(first: ActivityInfo, second: ActivityInfo) {
        val intent = Intent(this, SplitHostActivity::class.java)
            .putExtra(
                SplitHostActivity.EXTRA_FIRST,
                ComponentName(first.packageName, first.name).flattenToString(),
            )
            .putExtra(
                SplitHostActivity.EXTRA_SECOND,
                ComponentName(second.packageName, second.name).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(intent)
            scheduleSplitRatioApply(SPLIT_RATIO_INITIAL_DELAY_MS)
        } catch (e: Exception) {
            Logx.e("Не удалось открыть split-host", e)
            splitRequested = false
            settings.splitSessionActive = false
            Toast.makeText(
                this,
                getString(R.string.split_open_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun looksLikeSplash(info: ActivityInfo): Boolean =
        info.name.contains("splash", ignoreCase = true)

    private fun waitForSplitThenOpenInOurTask(app: ActivityInfo, attempt: Int) {
        if (isFinishing || isDestroyed || !MirrorState.value.projectionReady) return
        if (isInMultiWindowMode || multiWindowObserved) {
            // Даём системе завершить переход в split, чтобы запуск второго
            // приложения происходил уже в устоявшейся панели.
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && MirrorState.value.projectionReady &&
                    (isInMultiWindowMode || multiWindowObserved)
                ) {
                    openAppInOurTask(app)
                }
            }, SPLIT_INPANE_SETTLE_MS)
            return
        }
        if (attempt >= SPLIT_WAIT_ATTEMPTS) {
            Logx.w(
                "Мультиокно: не дождались split, приложение в панели не открываю " +
                    "(focus=${hasWindowFocus()}, split=${isInMultiWindowMode || multiWindowObserved})"
            )
            splitRequested = false
            pendingAutoSplit = false
            return
        }
        window.decorView.postDelayed({
            waitForSplitThenOpenInOurTask(app, attempt + 1)
        }, SPLIT_WAIT_STEP_MS)
    }

    /**
     * Без FLAG_ACTIVITY_NEW_TASK activity открывается в нашей задаче и заменяет
     * CarLife Mirror в его половине split-screen.
     */
    private fun openAppInOurTask(app: ActivityInfo) {
        if (isFinishing || isDestroyed || !MirrorState.value.projectionReady) return
        val component = ComponentName(app.packageName, app.name)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = component
        }
        val splash = looksLikeSplash(app)
        if (splash) {
            pendingFirstLaunch = app
            firstLaunchAt = SystemClock.elapsedRealtime()
            firstLaunchAttempts++
            Logx.i(
                "Разделение экрана: splash первого приложения, попытка $firstLaunchAttempts; " +
                    "после возврата CarLife повторю"
            )
        } else {
            pendingFirstLaunch = null
            firstLaunchAttempts = 0
        }
        try {
            Logx.i("Разделение экрана: открываю ${app.loadLabel(packageManager)} в панели CarLife ($component)")
            startActivity(intent)
        } catch (e: Exception) {
            Logx.e("Не удалось открыть приложение в панели CarLife", e)
        }
    }

    /**
     * SplashScreen первого приложения может завершиться и вернуть в его панели
     * CarLife Mirror. Если Activity снова стала видимой, повторяем запуск —
     * к этому моменту приложение уже тёплое и открывает основное окно.
     */
    private fun maybeRetryFirstInOurTask() {
        val app = pendingFirstLaunch ?: return
        if (!MirrorState.value.projectionReady || !splitRequested || firstRetryScheduled) return
        if (firstLaunchAttempts >= MAX_FIRST_LAUNCH_ATTEMPTS) {
            pendingFirstLaunch = null
            return
        }
        if (SystemClock.elapsedRealtime() - firstLaunchAt > SPLASH_RETRY_WINDOW_MS) {
            pendingFirstLaunch = null
            return
        }
        firstRetryScheduled = true
        Logx.i("Разделение экрана: первое приложение вернуло CarLife, повторяю запуск")
        window.decorView.postDelayed({
            firstRetryScheduled = false
            if (MirrorState.value.projectionReady && splitRequested && pendingFirstLaunch === app) {
                openAppInOurTask(app)
            }
        }, SPLASH_RETRY_DELAY_MS)
    }

    private fun logSplitResult(label: String) {
        if (isFinishing || isDestroyed) return
        val inSplit = isInMultiWindowMode || multiWindowObserved
        Logx.i("Мультиокно: $label, isInMultiWindowMode=$inSplit")
        if (!inSplit) {
            Toast.makeText(this, R.string.split_failed, Toast.LENGTH_LONG).show()
        }
    }


    private fun maybeAutoSplit(snapshot: MirrorSnapshot) {
        if (settingsVisible) return
        if (!snapshot.projectionReady) {
            splitRequested = false
            pendingAutoSplit = false
            autoSplitScheduled = false
            pendingFirstLaunch = null
            firstLaunchAttempts = 0
            firstRetryScheduled = false
            return
        }
        if (splitRequested || !settings.splitAuto) return
        if (settings.autoSplitSuppressedForSession) return
        if (autoSplitScheduled || pendingAutoSplit) return
        val pair = effectiveSplitPair()
        val target = pair.second
        if (target == null) {
            splitRequested = true
            updateSplitUi()
            Logx.w("Автоделение экрана: второе приложение не выбрано")
            Toast.makeText(this, R.string.split_auto_no_app, Toast.LENGTH_LONG).show()
            return
        }
        val first = pair.first
        if (first != null && sameActivity(first, target)) {
            splitRequested = true
            Logx.w(
                "Автоделение экрана: первое и второе приложения совпадают " +
                    "(${ComponentName(first.packageName, first.name)} / " +
                    "${ComponentName(target.packageName, target.name)})"
            )
            Toast.makeText(this, R.string.split_same_app, Toast.LENGTH_LONG).show()
            return
        }
        autoSplitScheduled = true
        Logx.i("Автоделение экрана: отложенный запуск, ждём готовность окна")
        window.decorView.postDelayed({
            autoSplitScheduled = false
            attemptAutoSplit()
        }, AUTO_SPLIT_DELAY_MS)
    }

    /**
     * Samsung может не войти в split, если LAUNCH_ADJACENT вызван сразу после
     * получения MediaProjection, пока окно ещё не получило фокус/завершило
     * переход. Поэтому запускаем только из сфокусированного Activity, а если
     * фокуса нет — ждём onWindowFocusChanged.
     */
    private fun attemptAutoSplit() {
        if (isFinishing || isDestroyed || settingsVisible) return
        if (!MirrorState.value.projectionReady || splitRequested || !settings.splitAuto) return
        if (settings.autoSplitSuppressedForSession) return
        if (!hasWindowFocus()) {
            pendingAutoSplit = true
            Logx.i("Автоделение экрана: ждём фокус окна")
            return
        }
        val pair = effectiveSplitPair()
        val target = pair.second
        if (target == null) {
            splitRequested = true
            updateSplitUi()
            Toast.makeText(this, R.string.split_auto_no_app, Toast.LENGTH_LONG).show()
            return
        }
        val first = pair.first
        if (first != null && sameActivity(first, target)) {
            splitRequested = true
            Logx.w(
                "Автоделение экрана после старта: приложения совпадают " +
                    "(${ComponentName(first.packageName, first.name)} / " +
                    "${ComponentName(target.packageName, target.name)})"
            )
            Toast.makeText(this, R.string.split_same_app, Toast.LENGTH_LONG).show()
            return
        }
        pendingAutoSplit = false
        Logx.i("Автоделение экрана после старта трансляции (фокус есть)")
        openSplitPair(first, target)
    }

    private fun showSplitAppPicker(slot: SplitSlot, launchAfterPick: Boolean) {
        val pm = packageManager
        val otherPackage = when (slot) {
            SplitSlot.FIRST -> resolveSplitTarget(clearIfMissing = false)?.packageName
            SplitSlot.SECOND -> resolveFirstTarget(clearIfMissing = false)?.packageName
        }
        val apps = loadLaunchableApps(pm, otherPackage)
        if (slot == SplitSlot.SECOND && apps.isEmpty()) {
            Toast.makeText(this, R.string.split_no_apps, Toast.LENGTH_LONG).show()
            return
        }
        val labels = ArrayList<String>()
        if (slot == SplitSlot.FIRST) {
            labels.add(getString(R.string.split_first_app_default))
        }
        labels.addAll(apps.map { it.loadLabel(pm).toString() })
        AlertDialog.Builder(this)
            .setTitle(
                if (slot == SplitSlot.FIRST) R.string.split_first_choose_app
                else R.string.split_choose_app
            )
            .setItems(labels.toTypedArray()) { _, which ->
                if (slot == SplitSlot.FIRST && which == 0) {
                    settings.splitAppFirst = ""
                    Logx.i("Первое приложение: CarLife Mirror (по умолчанию)")
                    updateSplitUi()
                    return@setItems
                }
                val appIndex = if (slot == SplitSlot.FIRST) which - 1 else which
                val chosen = apps[appIndex].activityInfo
                val component = ComponentName(chosen.packageName, chosen.name)
                if (slot == SplitSlot.FIRST) {
                    settings.splitAppFirst = component.flattenToString()
                    Logx.i("Первое приложение: ${chosen.loadLabel(pm)} ($component)")
                    if (resolveSplitTarget(clearIfMissing = false) != null) {
                        settings.splitAuto = true
                    }
                    updateSplitUi()
                } else {
                    settings.splitApp = component.flattenToString()
                    Logx.i("Второе приложение: ${chosen.loadLabel(pm)} ($component)")
                    if (resolveFirstTarget(clearIfMissing = false) != null) {
                        settings.splitAuto = true
                    }
                    if (launchAfterPick) splitRequested = true
                    updateSplitUi()
                    if (launchAfterPick) {
                        val first = effectiveSplitPair().first
                        if (first != null && sameActivity(first, chosen)) {
                            Toast.makeText(this, R.string.split_same_app, Toast.LENGTH_LONG).show()
                        } else {
                            openSplitPair(first, chosen)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadLaunchableApps(pm: android.content.pm.PackageManager, excludePackage: String?): List<ResolveInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        val collator = Collator.getInstance(Locale.getDefault())
        return resolved.asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .filter { excludePackage == null || it.activityInfo.packageName != excludePackage }
            .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .filter { it.loadLabel(pm).toString().isNotBlank() }
            .distinctBy { it.activityInfo.packageName }
            .sortedWith(Comparator<ResolveInfo> { a, b ->
                collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString())
            })
            .toList()
    }

    private fun toggleDetails() {
        detailsVisible = !detailsVisible
        detailsText.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        saveLogButton.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        detailsToggle.text = getString(if (detailsVisible) R.string.details_hide else R.string.details_show)
        if (detailsVisible) detailsText.text = buildDetails(MirrorState.value)
    }

    private fun saveLogToDownloads() {
        val saved = Logx.exportToDownloads(this)
        val message = if (saved != null) {
            getString(R.string.save_log_ok, saved)
        } else {
            getString(R.string.save_log_error)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Logx.e("Не удалось открыть настройки доступности", e)
        }
    }

    private fun refreshBrightnessService() {
        if (!MirrorState.value.projectionReady) return
        try {
            startService(
                Intent(this, MirrorService::class.java)
                    .setAction(MirrorService.ACTION_REFRESH_BRIGHTNESS)
            )
        } catch (e: Exception) {
            Logx.w("Не удалось обновить режим яркости: ${e.message}")
        }
    }

    private fun openWriteSettings() {
        try {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            Logx.e("Не удалось открыть настройки изменения системы", e)
            Toast.makeText(this, R.string.rotation_permission_error, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            Logx.e("Не удалось открыть настройки уведомлений", e)
        }
    }

    private fun notificationsAvailable(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(MirrorService.CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            Logx.i("Activity запущена по USB: ${accessory?.let { describe(it) } ?: "аксессуар"}")
        }
    }

    private fun describe(a: UsbAccessory): String =
        listOf(a.manufacturer, a.model, a.version).filter { !it.isNullOrBlank() }.joinToString(" / ")

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            audioPermissionRequestPending = false
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Logx.i("Разрешение на запись звука: $granted")
            val wantAudio = pendingAudioSwitch || awaitingConsent
            if (granted && wantAudio) {
                settings.usbAudio = true
                updateAudioUi()
            } else if (!granted) {
                settings.usbAudio = false
                pendingAudioSwitch = false
                updateAudioUi()
                Toast.makeText(this, R.string.usb_audio_permission_denied, Toast.LENGTH_LONG).show()
            }
            if (pendingAudioSwitch) {
                pendingAudioSwitch = false
                refreshAudioService()
            }
            updatePermissionDialog()
            if (awaitingConsent) beginProjectionFlow()
            return
        }
        if (requestCode == REQ_NOTIFICATION) {
            Logx.i("Разрешение на уведомления: ${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
            if (projectionPending) {
                projectionPending = false
                if (awaitingConsent) launchProjection()
            }
            return
        }
        if (requestCode == REQ_WIRELESS) {
            wirelessPermissionRequestPending = false
            val granted = hasWirelessPermissions()
            Logx.i("Разрешения для Wi-Fi: $granted")
            if (granted) {
                if (pendingWirelessMode) {
                    pendingWirelessMode = false
                    settings.connectionMode = ConnectMode.WIRELESS
                    updateConnectionUi()
                }
                if (pendingWirelessForProjection) {
                    pendingWirelessForProjection = false
                    if (awaitingConsent) beginProjectionFlow()
                }
            } else {
                pendingWirelessMode = false
                val wasProjection = pendingWirelessForProjection
                pendingWirelessForProjection = false
                updateConnectionUi()
                Toast.makeText(
                    this,
                    R.string.connection_permission_needed,
                    Toast.LENGTH_LONG,
                ).show()
                if (wasProjection && awaitingConsent) {
                    awaitingConsent = false
                    startingService = false
                    if (MirrorState.value.projectionReady) stopMirror()
                    MirrorState.update {
                        it.copy(
                            phase = Phase.IDLE,
                            title = "Нет разрешений для Wi-Fi",
                            detail = "Разрешите доступ к устройствам поблизости и Bluetooth.",
                            projectionReady = false,
                            error = true,
                        )
                    }
                    setMirrorLandscape(false)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SUPPRESS_AUTO_START = "suppress_auto_start"
        const val EXTRA_OPEN_FROM_NOTIFICATION = "open_from_notification"
        private const val REQ_PROJECTION = 1001
        private const val REQ_NOTIFICATION = 1002
        private const val REQ_AUDIO = 1003
        private const val REQ_WIRELESS = 1004
        private const val ORIENTATION_FALLBACK_MS = 900L
        private const val SPLIT_CHECK_DELAY_MS = 3_000L
        private const val SPLIT_RATIO_INITIAL_DELAY_MS = 2_200L
        private const val SPLIT_WAIT_STEP_MS = 200L
        private const val SPLIT_WAIT_ATTEMPTS = 20
        private const val SPLIT_INPANE_SETTLE_MS = 700L
        private const val SPLIT_REOPEN_SETTLE_MS = 800L
        private const val AUTO_SPLIT_DELAY_MS = 1_500L
        private const val AUTO_SPLIT_FOCUS_SETTLE_MS = 350L
        private const val HOME_AFTER_START_DELAY_MS = 900L
        private const val IMMERSIVE_REHIDE_MS = 3_000L
        private const val SPLASH_RETRY_DELAY_MS = 800L
        private const val SPLASH_RETRY_WINDOW_MS = 8_000L
        private const val MAX_FIRST_LAUNCH_ATTEMPTS = 2
        private const val KEY_SPLIT_REQUESTED = "split_requested"
        private const val KEY_SETTINGS_VISIBLE = "settings_visible"
        private const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
