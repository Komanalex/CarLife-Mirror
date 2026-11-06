package com.carlifemirror.app.touch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import com.carlifemirror.app.MirrorState
import com.carlifemirror.app.Settings
import com.carlifemirror.app.util.Logx

/**
 * Запоминает, какие приложения реально находятся в левой и правой половинах
 * split-screen. Используются только packageName и границы окна; содержимое
 * окон не читается.
 */
object SplitWindowTracker {
    private val ignoredPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )
    private var lastNoBoundsPackage = ""

    fun onWindowChanged(context: Context, packageName: String, bounds: Rect?) {
        if (!MirrorState.value.projectionReady) return
        if (!Settings(context).splitSessionActive) return
        if (packageName.isBlank()) return
        if (bounds == null) {
            if (lastNoBoundsPackage != packageName) {
                lastNoBoundsPackage = packageName
                Logx.w("Разделение экрана: нет границ окна для $packageName")
            }
            return
        }
        val settings = Settings(context)
        if (packageName == context.packageName) return
        if (isIgnored(packageName)) return

        val screen = screenBounds(context) ?: return
        val horizontal = screen.width() > screen.height()
        val paneFraction = if (horizontal) {
            bounds.width().toFloat() / screen.width()
        } else {
            bounds.height().toFloat() / screen.height()
        }
        // Полноэкранные окна (не split) не запоминаем.
        if (paneFraction > 0.8f) return
        val isFirstPane = if (horizontal) {
            bounds.centerX() < screen.centerX()
        } else {
            bounds.centerY() < screen.centerY()
        }

        val pm = context.packageManager
        if (packageName == homePackage(pm)) return
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return
        val component = launch.component ?: return
        val flat = component.flattenToString()
        val old = if (isFirstPane) settings.lastSplitFirstApp else settings.lastSplitApp
        if (old == flat) return
        val other = if (isFirstPane) settings.lastSplitApp else settings.lastSplitFirstApp
        if (other == flat) {
            // Одно и то же приложение уже записано во второй панели. Такое
            // событие окна не должно затирать корректную пару.
            Logx.w("Разделение экрана: ${component.packageName} уже в другой панели, пропускаю")
            return
        }
        if (isFirstPane) {
            settings.lastSplitFirstApp = flat
        } else {
            settings.lastSplitApp = flat
        }
        val label = try {
            pm.getActivityInfo(component, 0).loadLabel(pm).toString()
        } catch (_: Exception) {
            packageName
        }
        val pane = if (isFirstPane) "первом" else "втором"
        Logx.i("Разделение экрана: в $pane окне теперь $label ($component)")
    }

    /**
     * Резервное обновление по набору окон. Сравнивается только множество
     * приложений, без порядка панелей: свап не меняет набор и игнорируется.
     */
    @Synchronized
    fun updateFromPanes(context: Context, firstPackage: String, secondPackage: String) {
        if (!MirrorState.value.projectionReady) return
        val settings = Settings(context)
        if (!settings.splitSessionActive) return
        if (firstPackage.isBlank() || secondPackage.isBlank()) return
        if (firstPackage == secondPackage) return
        val pm = context.packageManager
        if (firstPackage == context.packageName || secondPackage == context.packageName) return
        if (isIgnored(firstPackage) || isIgnored(secondPackage)) return
        if (firstPackage == homePackage(pm) || secondPackage == homePackage(pm)) return
        val firstFlat = launchFlat(pm, firstPackage) ?: return
        val secondFlat = launchFlat(pm, secondPackage) ?: return
        if (firstFlat == secondFlat) return

        val oldFirst = settings.lastSplitFirstApp
        val oldSecond = settings.lastSplitApp
        val oldSet = setOf(oldFirst, oldSecond).filter { it.isNotBlank() }.toSet()
        val newSet = setOf(firstFlat, secondFlat)
        if (oldSet == newSet) return

        settings.lastSplitFirstApp = firstFlat
        settings.lastSplitApp = secondFlat
        Logx.i("Разделение экрана: набор окон изменился first=$firstFlat second=$secondFlat")
    }

    private fun launchFlat(pm: PackageManager, packageName: String): String? =
        pm.getLaunchIntentForPackage(packageName)?.component?.flattenToString()

    private fun isIgnored(packageName: String): Boolean {
        if (packageName in ignoredPackages) return true
        if (packageName.startsWith("com.android.settings")) return true
        if (packageName.startsWith("com.samsung.android.settings")) return true
        if (packageName.contains("inputmethod", ignoreCase = true)) return true
        if (packageName.endsWith(".ime")) return true
        return false
    }

    private fun homePackage(pm: PackageManager): String? = try {
        pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        )?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    private fun screenBounds(context: Context): Rect? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            // maximumWindowMetrics — размер всего дисплея, а не одной панели split.
            context.getSystemService(WindowManager::class.java)
                ?.maximumWindowMetrics?.bounds
        } else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    } catch (_: Exception) {
        null
    }
}
