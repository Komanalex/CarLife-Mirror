package com.carlifemirror.app

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.carlifemirror.app.touch.MirrorAccessibilityService
import com.carlifemirror.app.util.Logx

/**
 * Лучшее, что можно сделать без системных прав: увести split в фон и
 * завершить процессы приложений, которые в нём открыты. Публичного API,
 * позволяющего чужому приложению закрыть задачу другого приложения, нет;
 * после Home процессы split-приложений становятся фоновыми и их можно
 * завершить через killBackgroundProcesses. Обычно после этого пара split
 * распадается.
 */
object SplitApps {
    private const val HOME_SETTLE_MS = 450L
    private const val KILL_SETTLE_MS = 500L

    /**
     * Пакеты, которые реально были открыты в текущей split-сессии.
     * Настроенные, но не открытые приложения сюда не попадают.
     */
    fun packages(settings: Settings, ownPackage: String): List<String> {
        val raw = listOf(settings.lastSplitFirstApp, settings.lastSplitApp)
        return raw.asSequence()
            .mapNotNull { flat ->
                flat.substringBefore('/').trim()
                    .takeIf { it.isNotBlank() && it != ownPackage }
            }
            .distinct()
            .toList()
    }

    /**
     * Возвращает задачу MainActivity на передний план без запуска новой
     * Activity. Это обходит ограничение фонового запуска Activity после Home
     * и гарантированно не создаёт вторую копию приложения.
     */
    fun bringMainTaskToFront(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val tasks = try {
            am.appTasks
        } catch (e: Exception) {
            Logx.w("Split: не удалось получить задачи: ${e.message}")
            return false
        }
        val mainName = MainActivity::class.java.name
        val task = tasks.firstOrNull {
            val info = it.taskInfo
            info.baseActivity?.className == mainName ||
                (info.baseActivity == null && info.topActivity?.className == mainName)
        } ?: return false
        if (task.taskInfo.topActivity?.className != mainName) {
            Logx.i(
                "Split: верх задачи MainActivity — " +
                    "${task.taskInfo.topActivity?.className}; нужен CLEAR_TOP"
            )
            return false
        }
        return try {
            // Просим полный экран: если MainActivity всё-таки оказалась в
            // split, перевод задачи в fullscreen выводит её из панели.
            val options = fullscreenOptions(context)
            try {
                am.moveTaskToFront(task.taskInfo.taskId, 0, options)
                Logx.i("Split: задача MainActivity возвращена на передний план (fullscreen)")
            } catch (e: Exception) {
                Logx.w("Split: moveTaskToFront с fullscreen не сработал: ${e.message}")
                task.moveToFront()
            }
            true
        } catch (e: Exception) {
            Logx.w("Split: не удалось вернуть задачу MainActivity: ${e.message}")
            false
        }
    }

    fun finishSplitHostTask(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val hostName = SplitHostActivity::class.java.name
        try {
            for (task in am.appTasks) {
                if (task.taskInfo.baseActivity?.className == hostName) {
                    try {
                        task.finishAndRemoveTask()
                        Logx.i("Split: host-задача удалена")
                    } catch (e: Exception) {
                        Logx.w("Split: не удалось удалить host-задачу: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Logx.w("Split: не удалось получить host-задачи: ${e.message}")
        }
    }

    private fun fullscreenOptions(context: Context): android.os.Bundle {
        val options = ActivityOptions.makeBasic()
        try {
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            } else {
                val dm = context.resources.displayMetrics
                Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
            if (bounds != null) options.setLaunchBounds(bounds)
            try {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, 1)
            } catch (_: Throwable) {
                // Hidden API недоступен — остаются полные launch bounds.
            }
        } catch (_: Exception) {
        }
        return options.toBundle()
    }

    fun close(
        context: Context,
        packages: List<String>,
        sendHome: Boolean = true,
        onComplete: () -> Unit,
    ) {
        if (packages.isEmpty()) {
            onComplete()
            return
        }
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val home = if (sendHome) {
            try {
                MirrorAccessibilityService.instance?.home() ?: false
            } catch (e: Exception) {
                Logx.w("Split: не удалось отправить Home: ${e.message}")
                false
            }
        } else {
            false
        }
        Logx.i("Split: закрываю приложения ${packages.joinToString()} (home=$home)")
        handler.postDelayed({
            val am = app.getSystemService(ActivityManager::class.java)
            for (pkg in packages) {
                try {
                    am?.killBackgroundProcesses(pkg)
                    Logx.i("Split: killBackgroundProcesses($pkg)")
                } catch (e: Exception) {
                    Logx.w("Split: не удалось завершить $pkg: ${e.message}")
                }
            }
            handler.postDelayed(onComplete, KILL_SETTLE_MS)
        }, if (sendHome) HOME_SETTLE_MS else 0L)
    }
}
