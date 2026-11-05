package com.carlifemirror.app.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import com.carlifemirror.app.MirrorState
import com.carlifemirror.app.Phase
import com.carlifemirror.app.Settings
import com.carlifemirror.app.touch.MirrorAccessibilityService
import com.carlifemirror.app.util.Logx

/**
 * Затемнение системной яркости на время трансляции.
 *
 * Работает только при включённой настройке «Гасить экран»: экран затемняется
 * до выбранного процента, а физическое касание телефона возвращает исходную
 * яркость на 5 секунд. Если настройка выключена, яркость не меняется вовсе.
 *
 * Яркость меняется глобально, а не только у окна MainActivity: после старта
 * приложение может уйти на рабочий стол или в split. Исходный режим и значение
 * сохраняются, чтобы вернуть их при касании, остановке, отключении USB или
 * закрытии приложения.
 */
class BrightnessController(
    context: Context,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var sessionActive = false
    private var dimmed = false
    private var permissionWarningShown = false

    private val redim = Runnable {
        if (sessionActive) applyDim()
    }

    @Synchronized
    fun startSession() {
        if (!settings.dimScreen) {
            // Настройка «Гасить экран» выключена — яркость не трогаем.
            if (sessionActive || settings.brightnessDimSaved) endSession()
            return
        }
        if (sessionActive) {
            if (dimmed) applyDim()
            return
        }
        if (!RotationController.canWrite(appContext)) {
            warnNoPermission()
            return
        }
        if (!MirrorAccessibilityService.isEnabled(appContext)) {
            Logx.w("Яркость: служба управления выключена, касание не отследить")
            return
        }
        sessionActive = true
        ensureSnapshot()
        handler.removeCallbacks(redim)
        applyDim()
    }

    @Synchronized
    fun endSession() {
        handler.removeCallbacks(redim)
        sessionActive = false
        if (settings.brightnessDimSaved) {
            restoreOriginal(clearSnapshot = true)
        }
    }

    /** Любое физическое касание экрана телефона. */
    @Synchronized
    fun onPhysicalTouch() {
        if (!sessionActive) return
        // Сразу возвращаем исходную яркость и даём пользователю 5 секунд.
        if (dimmed) {
            restoreOriginal(clearSnapshot = false)
            Logx.i("Яркость: касание телефона, восстановлена на 5 секунд")
        } else {
            // Страховка от зависшего touch exploration: касаний больше не
            // ждём, пока экран не будет затемнён снова.
            MirrorAccessibilityService.instance?.setTouchCaptureEnabled(false)
        }
        handler.removeCallbacks(redim)
        handler.postDelayed(redim, DIM_REDELAY_MS)
    }

    /** Служба доступности подключилась после старта трансляции. */
    @Synchronized
    fun onAccessibilityReady() {
        if (settings.dimScreen && MirrorState.value.phase == Phase.STREAMING) {
            startSession()
        }
        // Если яркость уже затемнена, после переподключения службы заново
        // включаем отслеживание касаний: флаг живёт в AccessibilityServiceInfo.
        if (sessionActive && dimmed) {
            MirrorAccessibilityService.instance?.setTouchCaptureEnabled(true)
        }
    }

    /** Служба доступности выключена — нельзя отследить касание. */
    @Synchronized
    fun onAccessibilityLost() {
        if (sessionActive) {
            Logx.w("Яркость: служба управления отключена, возвращаю яркость")
            endSession()
        }
    }

    @Synchronized
    private fun ensureSnapshot() {
        if (settings.brightnessDimSaved) return
        val mode = readInt(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val value = readInt(AndroidSettings.System.SCREEN_BRIGHTNESS, 128)
        settings.brightnessOriginalMode = mode
        settings.brightnessOriginalValue = value
        settings.brightnessDimSaved = true
        Logx.i("Яркость: сохранил исходное значение mode=$mode value=$value")
    }

    @Synchronized
    private fun applyDim() {
        if (!sessionActive) return
        if (!settings.dimScreen) {
            // Настройку выключили, пока ждали возврата яркости после касания.
            endSession()
            return
        }
        if (!RotationController.canWrite(appContext)) {
            warnNoPermission()
            return
        }
        ensureSnapshot()
        val percent = settings.brightnessDimPercent.coerceIn(0, 100)
        val modeOk = putInt(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val target = brightnessValue(percent)
        val valueOk = putInt(AndroidSettings.System.SCREEN_BRIGHTNESS, target)
        dimmed = modeOk && valueOk
        // В обычном режиме TYPE_TOUCH_INTERACTION_START не приходит. Пока
        // экран затемнён, на время включаем touch exploration: первое
        // физическое касание вернёт яркость, после чего режим сразу
        // выключается и обычные касания снова работают как обычно.
        MirrorAccessibilityService.instance?.setTouchCaptureEnabled(dimmed)
        Logx.i("Яркость: установлена на $percent% ($target/255, mode=$modeOk value=$valueOk)")
    }

    @Synchronized
    private fun restoreOriginal(clearSnapshot: Boolean) {
        if (!settings.brightnessDimSaved) return
        if (!RotationController.canWrite(appContext)) {
            warnNoPermission()
            return
        }
        val savedMode = settings.brightnessOriginalMode
        val mode = if (savedMode == AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        val savedValue = settings.brightnessOriginalValue
        val value = if (savedValue >= 0) savedValue else 128
        // В автоматическом режиме сначала возвращаем значение, затем режим.
        putInt(AndroidSettings.System.SCREEN_BRIGHTNESS, value)
        putInt(AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, mode)
        dimmed = false
        MirrorAccessibilityService.instance?.setTouchCaptureEnabled(false)
        Logx.i("Яркость: восстановлено исходное значение mode=$mode value=$value")
        if (clearSnapshot) {
            settings.brightnessDimSaved = false
            settings.brightnessOriginalMode = -1
            settings.brightnessOriginalValue = -1
        }
    }

    private fun warnNoPermission() {
        if (!permissionWarningShown) {
            permissionWarningShown = true
            Logx.w("Яркость: нет разрешения «Изменение системных настроек»")
        }
    }

    private fun brightnessValue(percent: Int): Int =
        ((percent.coerceIn(0, 100) * 255 + 50) / 100).coerceIn(0, 255)

    private fun readInt(name: String, def: Int): Int = try {
        AndroidSettings.System.getInt(appContext.contentResolver, name, def)
    } catch (_: Exception) {
        def
    }

    private fun putInt(name: String, value: Int): Boolean = try {
        AndroidSettings.System.putInt(appContext.contentResolver, name, value)
    } catch (e: Exception) {
        Logx.w("Яркость: $name=$value не применено: ${e.message}")
        false
    }

    companion object {
        private const val DIM_REDELAY_MS = 5_000L

        @Volatile
        var instance: BrightnessController? = null
            private set

        fun attach(controller: BrightnessController) {
            instance = controller
        }

        fun detach(controller: BrightnessController) {
            if (instance === controller) instance = null
        }

        /**
         * Если процесс умер, не успев вернуть яркость, исправляем это при
         * следующем запуске приложения.
         */
        fun restoreIfStale(context: Context) {
            val settings = Settings(context)
            if (!settings.brightnessDimSaved) return
            if (!RotationController.canWrite(context)) {
                Logx.w("Яркость: найден незакрытый сеанс, но нет разрешения")
                return
            }
            val savedMode = settings.brightnessOriginalMode
            val mode = if (savedMode == AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            val savedValue = settings.brightnessOriginalValue
            val value = if (savedValue >= 0) savedValue else 128
            try {
                AndroidSettings.System.putInt(
                    context.contentResolver,
                    AndroidSettings.System.SCREEN_BRIGHTNESS,
                    value,
                )
                AndroidSettings.System.putInt(
                    context.contentResolver,
                    AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                    mode,
                )
                settings.brightnessDimSaved = false
                settings.brightnessOriginalMode = -1
                settings.brightnessOriginalValue = -1
                Logx.i("Яркость: восстановлена после незакрытого сеанса")
            } catch (e: Exception) {
                Logx.w("Яркость: не удалось восстановить после сеанса: ${e.message}")
            }
        }
    }
}
