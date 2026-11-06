package com.carlifemirror.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import com.carlifemirror.app.system.RotationController
import com.carlifemirror.app.util.Logx

/**
 * Отдельная задача-хозяин split-screen. Нужна, чтобы MainActivity не попадала
 * в первую половину split: иначе уведомление открывает её прямо в панели, а
 * из недавних приложений вместо CarLife открывается первое приложение пары.
 *
 * Host запускает второе приложение соседним окном, затем первое — уже в
 * собственной задаче. MainActivity остаётся отдельной полноэкранной задачей.
 */
class SplitHostActivity : Activity() {
    private var first: ActivityInfo? = null
    private var second: ActivityInfo? = null
    private var firstLaunchScheduled = false
    private var firstLaunched = false
    private var pendingFirst = false
    private var firstAttempts = 0
    private var firstStartedAt = 0L
    private var retryScheduled = false
    private var multiWindowObserved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK) })
        if (!MirrorState.value.projectionReady) {
            finishAndRemoveTask()
            return
        }
        first = componentFromExtra(EXTRA_FIRST)?.let { activityInfo(it) }
        second = componentFromExtra(EXTRA_SECOND)?.let { activityInfo(it) }
        if (first == null || second == null) {
            Logx.e("Split-host: не удалось разрешить первое или второе приложение")
            fallbackToMain()
            return
        }
        Logx.i(
            "Split-host: ${label(first!!)} + ${label(second!!)}; " +
                "задача ${taskId}"
        )
        window.decorView.postDelayed({ openSecond() }, HOST_SETTLE_MS)
    }

    override fun onResume() {
        super.onResume()
        if (!MirrorState.value.projectionReady) {
            fallbackToMain()
            return
        }
        if (pendingFirst) {
            scheduleFirstRetry()
        }
        RotationController.ensureLandscape(this)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        multiWindowObserved = isInMultiWindowMode
        Logx.i("Split-host: мультиокно ${if (isInMultiWindowMode) "включено" else "выключено"}")
        if (isInMultiWindowMode) {
            scheduleFirst()
            RotationController.ensureLandscape(this)
        }
    }

    private fun openSecond() {
        if (isFinishing || isDestroyed) return
        if (!MirrorState.value.projectionReady) {
            fallbackToMain()
            return
        }
        val app = second ?: return
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        try {
            Logx.i("Split-host: открываю ${label(app)} соседним окном")
            startActivity(intent)
            waitForSplit(0)
        } catch (e: Exception) {
            Logx.e("Split-host: не удалось открыть второе приложение", e)
            fallbackToMain()
        }
    }

    private fun waitForSplit(attempt: Int) {
        if (isFinishing || isDestroyed) return
        if (!MirrorState.value.projectionReady) {
            fallbackToMain()
            return
        }
        if (isInSplit()) {
            scheduleFirst()
            return
        }
        if (attempt >= SPLIT_WAIT_ATTEMPTS) {
            Logx.w("Split-host: не дождались split; возвращаю главный экран")
            fallbackToMain()
            return
        }
        window.decorView.postDelayed({ waitForSplit(attempt + 1) }, SPLIT_WAIT_STEP_MS)
    }

    private fun scheduleFirst() {
        if (firstLaunchScheduled || firstLaunched || isFinishing || isDestroyed) return
        firstLaunchScheduled = true
        window.decorView.postDelayed({
            firstLaunchScheduled = false
            if (!firstLaunched) openFirst()
        }, SPLIT_INPANE_SETTLE_MS)
    }

    private fun openFirst() {
        if (isFinishing || isDestroyed) return
        if (!MirrorState.value.projectionReady) {
            fallbackToMain()
            return
        }
        val app = first ?: return
        firstLaunched = true
        // Как и раньше, повторяем запуск только для splash-активности:
        // обычное приложение, закрытое пользователем, не должно открываться
        // само по себе.
        pendingFirst = app.name.contains("splash", ignoreCase = true)
        firstAttempts++
        firstStartedAt = SystemClock.elapsedRealtime()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.name)
        }
        try {
            Logx.i("Split-host: открываю ${label(app)} в первой панели")
            startActivity(intent)
        } catch (e: Exception) {
            Logx.e("Split-host: не удалось открыть первое приложение", e)
            pendingFirst = false
            fallbackToMain()
        }
    }

    private fun scheduleFirstRetry() {
        if (retryScheduled || firstAttempts >= MAX_FIRST_ATTEMPTS) {
            pendingFirst = false
            return
        }
        if (SystemClock.elapsedRealtime() - firstStartedAt > SPLASH_RETRY_WINDOW_MS) {
            pendingFirst = false
            return
        }
        retryScheduled = true
        Logx.i("Split-host: первое приложение вернуло host, повторяю запуск")
        window.decorView.postDelayed({
            retryScheduled = false
            if (pendingFirst && !isFinishing && !isDestroyed) {
                firstLaunched = false
                openFirst()
            } else {
                pendingFirst = false
            }
        }, SPLASH_RETRY_DELAY_MS)
    }

    private fun isInSplit(): Boolean = isInMultiWindowMode || multiWindowObserved

    private fun activityInfo(component: ComponentName): ActivityInfo? = try {
        packageManager.getActivityInfo(component, 0)
    } catch (e: Exception) {
        Logx.w("Split-host: нет ActivityInfo для $component: ${e.message}")
        null
    }

    private fun componentFromExtra(key: String): ComponentName? =
        intent.getStringExtra(key)?.let { ComponentName.unflattenFromString(it) }

    private fun label(info: ActivityInfo): String = try {
        info.loadLabel(packageManager).toString()
    } catch (_: Exception) {
        info.packageName
    }

    private fun fallbackToMain() {
        SplitApps.bringMainTaskToFront(this)
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (e: Exception) {
            Logx.w("Split-host: не удалось вернуть главный экран: ${e.message}")
        }
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_FIRST = "split_first"
        const val EXTRA_SECOND = "split_second"
        private const val HOST_SETTLE_MS = 1_000L
        private const val SPLIT_WAIT_STEP_MS = 200L
        private const val SPLIT_WAIT_ATTEMPTS = 25
        private const val SPLIT_INPANE_SETTLE_MS = 700L
        private const val SPLASH_RETRY_DELAY_MS = 800L
        private const val SPLASH_RETRY_WINDOW_MS = 8_000L
        private const val MAX_FIRST_ATTEMPTS = 2
    }
}
