package com.carlifemirror.app.system

import android.Manifest
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Surface
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
    private const val KEY_HOME_MODIFIED = "home_modified"
    private const val KEY_HOME_ORIGINAL = "saved_home_portrait_only"
    private const val HOME_ROTATE_GLOBAL = "sehome_portrait_mode_only"

    fun canWrite(context: Context): Boolean = try {
        Settings.System.canWrite(context)
    } catch (_: Exception) {
        false
    }

    /**
     * WRITE_SECURE_SETTINGS нужен для настройки Samsung
     * «Главный экран → Переход в альбомный режим».
     */
    fun canWriteSecure(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
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
            sp.edit()
                .putInt(KEY_AUTO, auto)
                .putInt(KEY_USER, user)
                .putBoolean(KEY_MODIFIED, true)
                .commit()
            Logx.i("Поворот системы: сохранил авто=$auto, user=$user")
        }
        enterHomeRotation(context, sp)
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

    /**
     * Страховка на время трансляции: если Samsung после сворачивания или
     * перестроения split сбросил фиксацию, возвращаем ландшафт.
     */
    @Synchronized
    fun ensureLandscape(context: Context): Boolean {
        if (!canWrite(context)) return false
        val auto = getInt(context, Settings.System.ACCELEROMETER_ROTATION, -1)
        val user = getInt(context, Settings.System.USER_ROTATION, -1)
        val rotation = displayRotation(context)
        val landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        if (auto == 0 && user == Surface.ROTATION_90 && landscape) return true
        Logx.w(
            "Поворот системы: сторож восстанавливает ландшафт " +
                "(авто=$auto, user=$user, дисплей=$rotation)"
        )
        return enterLandscape(context)
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
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rotationModified = sp.getBoolean(KEY_MODIFIED, false)
        val homeModified = sp.getBoolean(KEY_HOME_MODIFIED, false)
        if (!rotationModified && !homeModified) return
        if (rotationModified) {
            if (!canWrite(context)) {
                Logx.w("Поворот системы: не могу восстановить без разрешения")
            } else {
                val auto = sp.getInt(KEY_AUTO, 1)
                val user = sp.getInt(KEY_USER, Surface.ROTATION_0)
                putInt(context, Settings.System.USER_ROTATION, user)
                putInt(context, Settings.System.ACCELEROMETER_ROTATION, auto)
                sp.edit()
                    .remove(KEY_MODIFIED)
                    .remove(KEY_AUTO)
                    .remove(KEY_USER)
                    .commit()
                Logx.i("Поворот системы: восстановлен авто=$auto, user=$user")
            }
        }
        if (homeModified) {
            restoreHomeRotation(context, sp)
        }
    }

    /**
     * Samsung One UI Home отдельно решает, разрешать ли главному экрану
     * ландшафт. Без этого даже при USER_ROTATION=90 домашний экран остаётся
     * портретным. На время трансляции разрешаем, после — возвращаем.
     */
    private fun enterHomeRotation(
        context: Context,
        sp: android.content.SharedPreferences,
    ) {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
        if (!canWriteSecure(context)) {
            Logx.w("Поворот системы: нет WRITE_SECURE_SETTINGS для настройки главного экрана")
            return
        }
        val current = getGlobalInt(context, HOME_ROTATE_GLOBAL, 1)
        if (current == 0) return
        if (!sp.getBoolean(KEY_HOME_MODIFIED, false)) {
            sp.edit()
                .putInt(KEY_HOME_ORIGINAL, current)
                .putBoolean(KEY_HOME_MODIFIED, true)
                .commit()
        }
        if (putGlobalInt(context, HOME_ROTATE_GLOBAL, 0)) {
            Logx.i("Поворот системы: разрешён ландшафт главного экрана (было $current)")
        } else {
            Logx.w("Поворот системы: не удалось разрешить ландшафт главного экрана")
        }
    }

    private fun restoreHomeRotation(
        context: Context,
        sp: android.content.SharedPreferences,
    ) {
        if (!canWriteSecure(context)) {
            Logx.w("Поворот системы: нет WRITE_SECURE_SETTINGS для возврата главного экрана")
            return
        }
        val original = sp.getInt(KEY_HOME_ORIGINAL, 1)
        if (!putGlobalInt(context, HOME_ROTATE_GLOBAL, original)) {
            Logx.w("Поворот системы: не удалось вернуть настройку главного экрана")
            return
        }
        sp.edit()
            .remove(KEY_HOME_MODIFIED)
            .remove(KEY_HOME_ORIGINAL)
            .commit()
        Logx.i("Поворот системы: настройка главного экрана возвращена ($original)")
    }

    /**
     * Страховка после краха/убийства процесса: если предыдущая сессия не
     * успела вернуть настройки, делаем это при следующем запуске приложения.
     */
    @Synchronized
    fun restoreIfStale(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_MODIFIED, false) ||
            sp.getBoolean(KEY_HOME_MODIFIED, false)
        ) {
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

    private fun getGlobalInt(context: Context, name: String, def: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, name, def)
    } catch (_: Exception) {
        def
    }

    private fun putGlobalInt(context: Context, name: String, value: Int): Boolean = try {
        Settings.Global.putInt(context.contentResolver, name, value)
    } catch (e: Exception) {
        Logx.w("Поворот системы: global $name=$value не применён: ${e.message}")
        false
    }

    private fun putInt(context: Context, name: String, value: Int): Boolean = try {
        Settings.System.putInt(context.contentResolver, name, value)
    } catch (e: Exception) {
        Logx.w("Поворот системы: $name=$value не применён: ${e.message}")
        false
    }
}
