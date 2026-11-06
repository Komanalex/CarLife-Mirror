package com.carlifemirror.app.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Surface
import com.carlifemirror.app.MirrorState
import com.carlifemirror.app.util.Logx

/**
 * Глобальный поворот системы на время трансляции.
 *
 * Обычный Activity.requestedOrientation действует только на окно приложения.
 * Автоповорот (ACCELEROMETER_ROTATION=1) означает «разрешить поворот», но не
 * фиксирует ландшафт: split-screen Samsung может сам перевести дисплей в
 * портрет, если приложения в панелях это позволяют. Поэтому на время
 * трансляции ландшафт именно фиксируется:
 *   ACCELEROMETER_ROTATION = 0
 *   USER_ROTATION = ROTATION_90
 *
 * Системная галочка автоповорота при этом будет выключена — это нормальная
 * плата за принудительную фиксацию. После остановки оба значения
 * восстанавливаются.
 *
 * Для этого нужно специальное разрешение «Изменение системных настроек».
 */
object RotationController {
    private const val PREFS = "rotation_guard"
    private const val KEY_MODIFIED = "modified"
    private const val KEY_AUTO = "saved_auto_rotate"
    private const val KEY_USER = "saved_user_rotation"
    private const val KEY_HOME_PORTRAIT = "saved_home_portrait_only"
    private const val HOME_PORTRAIT_KEY = "sehome_portrait_mode_only"

    fun canWrite(context: Context): Boolean = try {
        Settings.System.canWrite(context)
    } catch (_: Exception) {
        false
    }

    private val main = Handler(Looper.getMainLooper())
    private var portraitPackage: String? = null
    private var portraitLogged: String? = null
    private var pendingLandscape: Runnable? = null
    private var landscapeRetries = 0
    private val portraitOrientations = setOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
    )

    /**
     * Вызывается службой доступности при смене foreground-activity.
     * Пока открыто портретное приложение, ландшафт не навязываем; после
     * выхода из него возвращаем системный ландшафт.
     */
    fun onForegroundActivity(context: Context, packageName: String, className: String?) {
        if (!MirrorState.value.projectionReady) {
            resetPortraitTracking()
            return
        }
        if (packageName.isBlank()) return
        if (packageName == context.packageName) {
            portraitPackage = null
            portraitLogged = null
            scheduleLandscape(context)
            return
        }
        if (isPortraitActivity(context, packageName, className)) {
            portraitPackage = packageName
            if (portraitLogged != packageName) {
                portraitLogged = packageName
                Logx.i("Поворот системы: портретное приложение $packageName, оставляю портрет")
            }
            return
        }
        if (portraitPackage != null) {
            Logx.i("Поворот системы: выход из портретного приложения $portraitPackage")
        }
        portraitPackage = null
        portraitLogged = null
        scheduleLandscape(context)
    }

    fun ensureLandscape(context: Context): Boolean {
        if (!MirrorState.value.projectionReady) return false
        if (!com.carlifemirror.app.Settings(context).systemLandscape) return false
        if (!canWrite(context)) return false
        val rotation = displayRotation(context)
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            landscapeRetries = 0
            return true
        }
        if (landscapeRetries == 0) {
            Logx.i("Поворот системы: возвращаю ландшафт после смены приложения (дисплей=$rotation)")
        }
        enterLandscape(context)
        val after = displayRotation(context)
        if (after == Surface.ROTATION_90 || after == Surface.ROTATION_270) {
            landscapeRetries = 0
            return true
        }
        if (landscapeRetries >= 8) {
            landscapeRetries = 0
            Logx.w("Поворот системы: ландшафт не возвращается после 8 попыток")
            return false
        }
        landscapeRetries++
        scheduleLandscapeRetry(context)
        return false
    }

    private fun scheduleLandscapeRetry(context: Context) {
        val app = context.applicationContext
        pendingLandscape?.let { main.removeCallbacks(it) }
        val action = Runnable {
            pendingLandscape = null
            if (MirrorState.value.projectionReady) {
                ensureLandscape(app)
            }
        }
        pendingLandscape = action
        main.postDelayed(action, 500L)
    }

    private fun scheduleLandscape(context: Context) {
        val app = context.applicationContext
        landscapeRetries = 0
        pendingLandscape?.let { main.removeCallbacks(it) }
        val action = Runnable {
            if (MirrorState.value.projectionReady) {
                ensureLandscape(app)
            }
        }
        pendingLandscape = action
        main.postDelayed(action, 400L)
    }

    fun resetPortraitTracking() {
        pendingLandscape?.let { main.removeCallbacks(it) }
        pendingLandscape = null
        landscapeRetries = 0
        portraitPackage = null
        portraitLogged = null
    }

    private fun isPortraitActivity(
        context: Context,
        packageName: String,
        className: String?,
    ): Boolean {
        if (className.isNullOrBlank()) return false
        return try {
            val info = context.packageManager.getActivityInfo(
                ComponentName(packageName, className),
                0,
            )
            info.screenOrientation in portraitOrientations
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Фиксирует системный ландшафт. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun enterLandscape(context: Context): Boolean {
        if (!canWrite(context)) {
            Logx.w("Поворот системы: нет разрешения «Изменение системных настроек»")
            return false
        }
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_MODIFIED, false)) {
            val auto = getInt(context, Settings.System.ACCELEROMETER_ROTATION, 1)
            val user = getInt(context, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            val homePortrait = getGlobal(context, HOME_PORTRAIT_KEY, -1)
            sp.edit()
                .putInt(KEY_AUTO, auto)
                .putInt(KEY_USER, user)
                .putInt(KEY_HOME_PORTRAIT, homePortrait)
                .putBoolean(KEY_MODIFIED, true)
                .commit()
            Logx.i("Поворот системы: сохранил авто=$auto, user=$user, home=$homePortrait")
        }
        // Samsung Launcher удерживает домашний экран в портрете отдельной
        // глобальной настройкой. На время трансляции снимаем этот запрет,
        // иначе рабочий стол остаётся вертикальным независимо от user_rotation.
        val homeOk = putGlobal(context, HOME_PORTRAIT_KEY, 0)
        if (!homeOk) {
            Logx.w("Поворот системы: нет доступа к $HOME_PORTRAIT_KEY (нужен WRITE_SECURE_SETTINGS)")
        }
        // Сначала выключаем автоповорот, затем фиксируем user-rotation:
        // на Samsung это удерживает split горизонтальным.
        val autoOk = putInt(context, Settings.System.ACCELEROMETER_ROTATION, 0)
        val userOk = putInt(context, Settings.System.USER_ROTATION, Surface.ROTATION_90)
        val actualAuto = getInt(context, Settings.System.ACCELEROMETER_ROTATION, -1)
        val actualUser = getInt(context, Settings.System.USER_ROTATION, -1)
        if (!autoOk || !userOk || actualAuto != 0 || actualUser != Surface.ROTATION_90) {
            Logx.w(
                "Поворот системы: ландшафт не зафиксирован " +
                    "(write=$autoOk/$userOk, стало=$actualAuto/$actualUser)"
            )
        } else {
            logState(context, "ландшафт зафиксирован")
        }
        return actualAuto == 0 && actualUser == Surface.ROTATION_90
    }

    /** Диагностика: что реально записалось в Settings и как повёрнут дисплей. */
    fun logState(context: Context, prefix: String) {
        val auto = getInt(context, Settings.System.ACCELEROMETER_ROTATION, -1)
        val user = getInt(context, Settings.System.USER_ROTATION, -1)
        val rotation = displayRotation(context)
        val orientation = context.resources.configuration.orientation
        Logx.i("Поворот системы: $prefix (авто=$auto, user=$user, дисплей=$rotation, ориентация=$orientation)")
    }

    private fun displayRotation(context: Context): Int = try {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
    } catch (_: Exception) {
        -1
    }

    /** Возвращает автоповорот и ориентацию, которые были до трансляции. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun restore(context: Context) {
        resetPortraitTracking()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_MODIFIED, false)) return
        if (!canWrite(context)) {
            Logx.w("Поворот системы: не могу восстановить без разрешения")
            return
        }
        val auto = sp.getInt(KEY_AUTO, 1)
        val user = sp.getInt(KEY_USER, Surface.ROTATION_0)
        val homePortrait = sp.getInt(KEY_HOME_PORTRAIT, -1)
        // USER_ROTATION — только для текущего положения; главное для системных
        // экранов — вернуть ACCELEROMETER_ROTATION, иначе рабочий стол остаётся
        // в портрете, хотя приложения умеют поворачиваться сами.
        putInt(context, Settings.System.USER_ROTATION, user)
        putInt(context, Settings.System.ACCELEROMETER_ROTATION, auto)
        if (homePortrait >= 0) {
            putGlobal(context, HOME_PORTRAIT_KEY, homePortrait)
        }
        sp.edit()
            .remove(KEY_MODIFIED)
            .remove(KEY_AUTO)
            .remove(KEY_USER)
            .remove(KEY_HOME_PORTRAIT)
            .commit()
        Logx.i("Поворот системы: восстановлен авто=$auto, user=$user, home=$homePortrait")
    }

    /**
     * Страховка после краха/убийства процесса: если предыдущая сессия не
     * успела вернуть настройки, делаем это при следующем запуске приложения.
     */
    @Synchronized
    fun restoreIfStale(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_MODIFIED, false)) {
            Logx.i("Поворот системы: найден незакрытый сеанс, восстанавливаю")
            restore(context)
        }
    }

    @Synchronized
    fun isRotationHeld(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MODIFIED, false)

    private fun getInt(context: Context, name: String, def: Int): Int = try {
        Settings.System.getInt(context.contentResolver, name, def)
    } catch (_: Exception) {
        def
    }

    private fun putInt(context: Context, name: String, value: Int): Boolean = try {
        Settings.System.putInt(context.contentResolver, name, value)
    } catch (e: Exception) {
        Logx.w("Поворот системы: $name=$value не применён: ${e.message}")
        false
    }

    private fun getGlobal(context: Context, name: String, def: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, name, def)
    } catch (_: Exception) {
        def
    }

    private fun putGlobal(context: Context, name: String, value: Int): Boolean = try {
        Settings.Global.putInt(context.contentResolver, name, value)
    } catch (e: Exception) {
        Logx.w("Поворот системы: $name=$value не применён: ${e.message}")
        false
    }
}
