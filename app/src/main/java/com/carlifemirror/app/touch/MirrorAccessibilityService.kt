package com.carlifemirror.app.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.carlifemirror.app.MirrorState
import com.carlifemirror.app.Settings as AppSettings
import com.carlifemirror.app.system.BrightnessController
import com.carlifemirror.app.system.RotationController
import com.carlifemirror.app.util.Logx

/**
 * Служба доступности используется для отправки жестов (касаний) и определения
 * границ окон в split-screen. Текст и содержимое окон не читаются и не сохраняются.
 */
class MirrorAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveAt = 0L
    private var multiStrokes: List<GestureDescription.StrokeDescription> = emptyList()
    private var multiLast: List<PointF> = emptyList()
    private var multiActive = false
    private var lastMultiMoveAt = 0L
    private var lastBoundsDiagnosticPackage = ""
    private var splitRatioTarget = -1
    private var splitRatioDrags = 0
    private var splitRatioLastDragAt = 0L
    private var splitRatioStablePair: SplitPanePair? = null
    private var splitRatioStableSince = 0L
    private var splitRatioDispatched = false
    private var splitRatioDispatchedAt = 0L
    private var splitRatioGaveUpLogged = false
    private var lastSplitAppsRefreshAt = 0L
    private val splitAppsPoll = object : Runnable {
        override fun run() {
            refreshSplitApps()
            main.postDelayed(this, SPLIT_APPS_POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MirrorState.update { it.copy(accessibilityEnabled = true) }
        val info = try {
            serviceInfo?.also { current ->
                // На некоторых прошивках флаг из XML не подхватывается до
                // перезапуска службы; включаем его программно.
                val wanted = current.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                if (wanted != current.flags) {
                    current.flags = wanted
                }
                val wantedEvents = current.eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                if (wantedEvents != current.eventTypes) {
                    current.eventTypes = wantedEvents
                }
                setServiceInfo(current)
            }
        } catch (_: Exception) {
            null
        }
        Logx.i(
            "Служба управления подключена (flags=${info?.flags ?: -1}, " +
                "capabilities=${info?.capabilities ?: -1})"
        )
        if (info != null && (info.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) == 0
        ) {
            Logx.w("Служба управления: нет доступа к границам окон; выключите и включите службу заново")
        }
        BrightnessController.instance?.onAccessibilityReady()
        main.removeCallbacks(splitAppsPoll)
        main.postDelayed(splitAppsPoll, SPLIT_APPS_POLL_MS)
    }

    override fun onDestroy() {
        BrightnessController.instance?.onAccessibilityLost()
        main.removeCallbacks(splitAppsPoll)
        if (instance === this) instance = null
        // Не сбрасываем статус вслепую: система может пересоздать подключение.
        // Если службу действительно выключили в настройках, isEnabled вернёт false.
        val enabled = isEnabled(this)
        MirrorState.update { it.copy(accessibilityEnabled = enabled) }
        Logx.i("Служба управления отключена (разрешение: $enabled)")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshSplitApps()
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        RotationController.onForegroundActivity(this, pkg, event.className?.toString())
        onWindowEvent(event, pkg, 2)
    }

    /**
     * Резервная проверка набора приложений в split. Порядок панелей не
     * сравнивается, поэтому свап не считается изменением пары.
     */
    fun refreshSplitApps() {
        if (!MirrorState.value.projectionReady) return
        if (!AppSettings(this).splitSessionActive) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSplitAppsRefreshAt < 300L) return
        lastSplitAppsRefreshAt = now
        val pair = findSplitPair() ?: return
        val current = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return
        var firstPkg: String? = null
        var secondPkg: String? = null
        for (win in current) {
            try {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = windowPackage(win) ?: continue
                val bounds = Rect()
                win.getBoundsInScreen(bounds)
                if (sameBounds(bounds, pair.first)) {
                    firstPkg = pkg
                } else if (sameBounds(bounds, pair.second)) {
                    secondPkg = pkg
                }
            } catch (_: Exception) {
            }
        }
        val first = firstPkg ?: return
        val second = secondPkg ?: return
        SplitWindowTracker.updateFromPanes(this, first, second)
    }

    private fun onWindowEvent(event: AccessibilityEvent, pkg: String, retries: Int) {
        var bounds = windowBounds(event, pkg)
        if (bounds == null) bounds = eventSourceBounds(event)
        if (bounds != null || retries <= 0) {
            if (bounds == null && pkg != lastBoundsDiagnosticPackage) {
                lastBoundsDiagnosticPackage = pkg
                // Диагностика для реальных устройств: видно, доступны ли окна и source.
                val source = try {
                    event.source != null
                } catch (_: Exception) {
                    false
                }
                val count = try {
                    windows?.size ?: -1
                } catch (_: Exception) {
                    -1
                }
                Logx.w("Разделение экрана: нет границ окна для $pkg (id=${event.windowId}, source=$source, окон=$count)")
            }
            if (bounds != null && isSplitPaneWindow(bounds)) {
                SplitWindowTracker.onWindowChanged(this, pkg, bounds)
            }
        } else {
            main.postDelayed({ onWindowEvent(event, pkg, retries - 1) }, 250)
        }
    }

    /**
     * Берём границы из списка интерактивных окон. Если id события не совпал
     * (Samsung иногда отдаёт другой id), ищем активное окно того же пакета.
     */
    private fun windowBounds(event: AccessibilityEvent, pkg: String): Rect? = try {
        val list = windows ?: return null
        val win = list.firstOrNull { it.id == event.windowId }
            ?: list.firstOrNull { windowPackage(it) == pkg && it.isActive }
            ?: list.firstOrNull { windowPackage(it) == pkg }
        if (win == null) {
            null
        } else {
            val rect = Rect()
            win.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) rect else null
        }
    } catch (_: Exception) {
        null
    }

    private fun windowPackage(win: android.view.accessibility.AccessibilityWindowInfo): String? =
        try {
            win.root?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

    /**
     * Корень окна события обычно имеет границы всего окна приложения.
     * Поднимаемся к корню, потому что source иногда указывает на дочерний view.
     */
    private fun eventSourceBounds(event: AccessibilityEvent): Rect? = try {
        var node: AccessibilityNodeInfo? = event.source
        var root: AccessibilityNodeInfo? = node
        var depth = 0
        while (node != null && depth < 30) {
            val parent = node.parent
            if (parent == null) break
            root = parent
            node = parent
            depth++
        }
        val rect = Rect()
        root?.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) rect else null
    } catch (_: Exception) {
        null
    }

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x.coerceIn(0f, 100000f), y.coerceIn(0f, 100000f)) }
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build())
    }

    fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 550))
            .build())
    }

    fun down(x: Float, y: Float) {
        if (multiActive) endMulti()
        stroke = null
        lastX = x
        lastY = y
        lastMoveAt = 0L
        val path = Path().apply { moveTo(x, y) }
        val s = GestureDescription.StrokeDescription(path, 0, 1, true)
        stroke = s
        dispatch(build(s))
    }

    fun move(x: Float, y: Float) {
        val s = stroke ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMoveAt < 12) return
        lastMoveAt = now
        if (kotlin.math.abs(x - lastX) < 1f && kotlin.math.abs(y - lastY) < 1f) return
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        val next = try {
            s.continueStroke(path, 0, 1, true)
        } catch (e: Exception) {
            Logx.w("Не удалось продолжить жест: ${e.message}")
            return
        }
        lastX = x
        lastY = y
        stroke = next
        dispatch(build(next))
    }

    fun up(x: Float, y: Float) {
        val s = stroke ?: return
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        val next = try {
            s.continueStroke(path, 0, 1, false)
        } catch (_: Exception) {
            stroke = null
            return
        }
        stroke = null
        dispatch(build(next))
    }

    fun cancelStroke() {
        if (multiActive) endMulti()
        val s = stroke ?: return
        stroke = null
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(lastX, lastY)
        }
        try {
            val next = s.continueStroke(path, 0, 1, false)
            dispatch(build(next))
        } catch (e: Exception) {
            Logx.w("Не удалось завершить жест: ${e.message}")
        }
    }

    /**
     * Второй палец. Текущий одиночный жест корректно завершается, после чего
     * начинается новый двухпальцевый жест с текущих точек.
     */
    fun downTwo(x0: Float, y0: Float, x1: Float, y1: Float) {
        cancelStroke()
        startMulti(listOf(PointF(x0, y0), PointF(x1, y1)))
    }

    fun moveTwo(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!multiActive || multiStrokes.size != 2 || multiLast.size != 2) {
            downTwo(x0, y0, x1, y1)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastMultiMoveAt < 12) return
        val points = listOf(PointF(x0, y0), PointF(x1, y1))
        if (points.indices.all {
                kotlin.math.abs(points[it].x - multiLast[it].x) < 1f &&
                    kotlin.math.abs(points[it].y - multiLast[it].y) < 1f
            }
        ) return
        lastMultiMoveAt = now
        val next = ArrayList<GestureDescription.StrokeDescription>(2)
        try {
            for (i in 0..1) {
                val from = multiLast[i]
                val to = points[i]
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    lineTo(to.x, to.y)
                }
                next.add(multiStrokes[i].continueStroke(path, 0, 1, true))
            }
        } catch (e: Exception) {
            Logx.w("Не удалось продолжить двухпальцевый жест: ${e.message}")
            multiActive = false
            multiStrokes = emptyList()
            multiLast = emptyList()
            return
        }
        if (dispatch(buildMulti(next))) {
            multiStrokes = next
            multiLast = points
        } else {
            multiActive = false
            multiStrokes = emptyList()
            multiLast = emptyList()
        }
    }

    fun upTwo(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!multiActive) {
            up(x0, y0)
            return
        }
        val points = listOf(PointF(x0, y0), PointF(x1, y1))
        val next = ArrayList<GestureDescription.StrokeDescription>(2)
        try {
            for (i in 0..1) {
                val from = multiLast.getOrElse(i) { points[i] }
                val to = points[i]
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    lineTo(to.x, to.y)
                }
                next.add(multiStrokes[i].continueStroke(path, 0, 1, false))
            }
            dispatch(buildMulti(next))
        } catch (e: Exception) {
            Logx.w("Не удалось завершить двухпальцевый жест: ${e.message}")
        } finally {
            multiActive = false
            multiStrokes = emptyList()
            multiLast = emptyList()
            lastMultiMoveAt = 0L
        }
    }

    private fun startMulti(points: List<PointF>) {
        if (multiActive) endMulti()
        if (points.size != 2) return
        multiActive = true
        multiLast = points
        lastMultiMoveAt = 0L
        multiStrokes = points.map { p ->
            val path = Path().apply { moveTo(p.x, p.y) }
            GestureDescription.StrokeDescription(path, 0, 1, true)
        }
        if (!dispatch(buildMulti(multiStrokes))) {
            multiActive = false
            multiStrokes = emptyList()
            multiLast = emptyList()
        }
    }

    private fun endMulti() {
        if (!multiActive) return
        val next = ArrayList<GestureDescription.StrokeDescription>(2)
        try {
            for (i in multiStrokes.indices) {
                val p = multiLast.getOrElse(i) { PointF() }
                val path = Path().apply {
                    moveTo(p.x, p.y)
                    lineTo(p.x, p.y)
                }
                next.add(multiStrokes[i].continueStroke(path, 0, 1, false))
            }
            dispatch(buildMulti(next))
        } catch (e: Exception) {
            Logx.w("Не удалось отменить двухпальцевый жест: ${e.message}")
        } finally {
            multiActive = false
            multiStrokes = emptyList()
            multiLast = emptyList()
            lastMultiMoveAt = 0L
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Закрывает активный split-screen (или переключает его, если система
     * понимает действие иначе). Возвращает только факт отправки действия;
     * фактическое завершение перехода проверяем паузой в сервисе.
     */
    fun toggleSplitScreen(): Boolean = try {
        val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        Logx.i("Служба: переключение split-screen, dispatched=$ok")
        ok
    } catch (e: Exception) {
        Logx.w("Служба: не удалось переключить split-screen: ${e.message}")
        false
    }

    /**
     * Сбрасывает попытки выставления пропорции. Вызывается при создании нового
     * split или изменении положения ползунка.
     */
    fun resetSplitRatioApply() {
        splitRatioTarget = -1
        splitRatioDrags = 0
        splitRatioLastDragAt = 0L
        splitRatioStablePair = null
        splitRatioStableSince = 0L
        splitRatioDispatched = false
        splitRatioDispatchedAt = 0L
        splitRatioGaveUpLogged = false
    }

    /**
     * Best-effort выставление пропорции перетаскиванием системного разделителя.
     * Android не даёт официального API для пропорции split, поэтому используется
     * жест accessibility.
     *
     * Ждём, пока границы обеих панелей перестанут меняться после запуска
     * приложений, и делаем ровно один жест. Раньше каждый промежуточный кадр
     * анимации принимался за новую попытку, и разделитель дёргался несколько раз.
     */
    fun applySplitRatio(firstPercent: Int): Boolean {
        val target = firstPercent.coerceIn(30, 70)
        if (target != splitRatioTarget) {
            splitRatioTarget = target
            splitRatioDrags = 0
            splitRatioLastDragAt = 0L
            splitRatioStablePair = null
            splitRatioStableSince = 0L
            splitRatioDispatched = false
            splitRatioGaveUpLogged = false
        }
        if (splitRatioDispatched) {
            val verified = findSplitPair()
            if (verified != null &&
                kotlin.math.abs(verified.firstPercent - target) <= RATIO_TOLERANCE_PERCENT
            ) {
                if (splitRatioDrags > 0) {
                    val label = (verified.firstPercent + 0.5f).toInt()
                    Logx.i("Пропорция split: установлена $label% / ${100 - label}%")
                }
                return true
            }
            if (SystemClock.uptimeMillis() - splitRatioDispatchedAt < RATIO_VERIFY_MS) {
                return false
            }
            if (splitRatioDrags >= 3) {
                if (!splitRatioGaveUpLogged) {
                    splitRatioGaveUpLogged = true
                    Logx.w("Пропорция split: не удалось выставить $target%, оставляю как есть")
                }
                return true
            }
            splitRatioDispatched = false
        }

        val pair = findSplitPair() ?: return false
        val previous = splitRatioStablePair
        val now = SystemClock.uptimeMillis()
        val stable = previous != null &&
            previous.sideBySide == pair.sideBySide &&
            sameSplitBounds(previous.first, pair.first) &&
            sameSplitBounds(previous.second, pair.second)
        if (!stable) {
            splitRatioStablePair = pair
            splitRatioStableSince = now
            return false
        }
        if (now - splitRatioStableSince < RATIO_STABLE_MS) return false

        val current = pair.firstPercent
        val currentLabel = (current + 0.5f).toInt()
        if (kotlin.math.abs(current - target) <= RATIO_TOLERANCE_PERCENT) {
            splitRatioDispatched = true
            if (splitRatioDrags > 0) {
                Logx.i("Пропорция split: установлена $currentLabel% / ${100 - currentLabel}%")
            }
            return true
        }

        val targetCoord = if (pair.sideBySide) {
            pair.union.left + pair.union.width() * target / 100f
        } else {
            pair.union.top + pair.union.height() * target / 100f
        }
        val fromCoord = if (pair.sideBySide) {
            (pair.first.right + pair.second.left) / 2f
        } else {
            (pair.first.bottom + pair.second.top) / 2f
        }
        val cross = if (pair.sideBySide) {
            pair.union.exactCenterY().toFloat()
        } else {
            pair.union.exactCenterX().toFloat()
        }
        if (kotlin.math.abs(targetCoord - fromCoord) < 3f) {
            splitRatioDispatched = true
            return true
        }

        val dispatched = if (pair.sideBySide) {
            drag(fromCoord, cross, targetCoord, cross, RATIO_DRAG_DURATION_MS)
        } else {
            drag(cross, fromCoord, cross, targetCoord, RATIO_DRAG_DURATION_MS)
        }
        if (!dispatched) {
            Logx.w("Пропорция split: жест разделителя не отправлен")
            return false
        }
        splitRatioDispatched = true
        splitRatioDispatchedAt = now
        splitRatioDrags++
        splitRatioLastDragAt = now
        Logx.i(
            "Пропорция split: панели ${pair.first.width()}x${pair.first.height()} + " +
                "${pair.second.width()}x${pair.second.height()}, сейчас $currentLabel%, " +
                "цель $target%; тяну разделитель один раз " +
                "${fromCoord.toInt()} -> ${targetCoord.toInt()}"
        )
        return true
    }

    private fun sameSplitBounds(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= 2 &&
            kotlin.math.abs(a.top - b.top) <= 2 &&
            kotlin.math.abs(a.right - b.right) <= 2 &&
            kotlin.math.abs(a.bottom - b.bottom) <= 2

    private fun findSplitPair(): SplitPanePair? {
        val display = displayBounds() ?: return null
        val currentWindows = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return null
        val rects = ArrayList<Rect>()
        for (win in currentWindows) {
            try {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = windowPackage(win) ?: continue
                if (isIgnoredSplitPackage(pkg)) continue
                val rect = Rect()
                win.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue
                if (rect.width() < display.width() * 0.05f ||
                    rect.height() < display.height() * 0.05f
                ) {
                    continue
                }
                if (rects.none { it == rect }) rects.add(rect)
            } catch (_: Exception) {
                // Окно могло исчезнуть между получением списка и чтением границ.
            }
        }
        if (rects.size < 2) return null

        var best: SplitPanePair? = null
        var bestArea = -1L
        for (i in 0 until rects.size - 1) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val smallerArea = minOf(
                    a.width().toLong() * a.height(),
                    b.width().toLong() * b.height(),
                )
                if (smallerArea <= 0) continue
                if (overlapArea(a, b) > smallerArea / 50) continue

                val sideBySide =
                    kotlin.math.abs(a.centerX() - b.centerX()) >=
                        kotlin.math.abs(a.centerY() - b.centerY())
                val first = if (sideBySide) {
                    if (a.centerX() <= b.centerX()) a else b
                } else {
                    if (a.centerY() <= b.centerY()) a else b
                }
                val second = if (first === a) b else a
                val union = Rect(first).apply { union(second) }
                // Иногда Samsung на мгновение отдаёт окна со сдвигом за
                // пределы экрана (например, при сворачивании панели).
                // Такую пару не трогаем, иначе жест уйдёт в отрицательные
                // координаты.
                if (union.left < -1 || union.top < -1 ||
                    union.right > display.width() + 1 ||
                    union.bottom > display.height() + 1
                ) {
                    continue
                }
                val span = if (sideBySide) union.width() else union.height()
                val crossSpan = if (sideBySide) union.height() else union.width()
                val displaySpan = if (sideBySide) display.width() else display.height()
                val displayCross = if (sideBySide) display.height() else display.width()
                if (span < displaySpan * 0.70f || crossSpan < displayCross * 0.70f) continue
                val gap = if (sideBySide) {
                    second.left - first.right
                } else {
                    second.top - first.bottom
                }
                if (gap > displaySpan * 0.10f) continue

                val area = union.width().toLong() * union.height()
                if (area > bestArea) {
                    bestArea = area
                    best = SplitPanePair(first, second, sideBySide, union)
                }
            }
        }
        return best
    }

    /**
     * Событие окна учитывается для запоминания split-приложений только тогда,
     * когда окно действительно является одной из двух панелей текущего split.
     * Иначе обычное полноэкранное приложение, открытое из домашнего экрана,
     * ошибочно записывалось бы как первая панель.
     */
    private fun isSplitPaneWindow(bounds: Rect): Boolean {
        val pair = findSplitPair() ?: return false
        return sameBounds(bounds, pair.first) || sameBounds(bounds, pair.second)
    }

    private fun sameBounds(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= 2 &&
            kotlin.math.abs(a.top - b.top) <= 2 &&
            kotlin.math.abs(a.right - b.right) <= 2 &&
            kotlin.math.abs(a.bottom - b.bottom) <= 2

    private fun overlapArea(a: Rect, b: Rect): Long {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width > 0 && height > 0) width.toLong() * height else 0L
    }

    private fun isIgnoredSplitPackage(packageName: String): Boolean {
        if (packageName == "android") return true
        if (packageName == "com.android.systemui") return true
        if (packageName.contains("permissioncontroller", ignoreCase = true)) return true
        if (packageName.startsWith("com.android.settings")) return true
        if (packageName.startsWith("com.samsung.android.settings")) return true
        if (packageName.contains("inputmethod", ignoreCase = true)) return true
        if (packageName.endsWith(".ime")) return true
        return false
    }

    private fun displayBounds(): Rect? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    } catch (_: Exception) {
        null
    }

    private fun drag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean {
        if (!fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) {
            Logx.w("Пропорция split: некорректные координаты жеста")
            return false
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.left < 0f || bounds.top < 0f ||
            bounds.right < 0f || bounds.bottom < 0f
        ) {
            Logx.w(
                "Пропорция split: отрицательные границы жеста " +
                    "($fromX,$fromY) -> ($toX,$toY), bounds=$bounds"
            )
            return false
        }
        return try {
            dispatch(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            )
        } catch (e: Exception) {
            Logx.w(
                "Пропорция split: не удалось построить жест " +
                    "($fromX,$fromY) -> ($toX,$toY), bounds=$bounds: ${e.message}"
            )
            false
        }
    }

    private data class SplitPanePair(
        val first: Rect,
        val second: Rect,
        val sideBySide: Boolean,
        val union: Rect,
    ) {
        val firstPercent: Float
            get() = if (sideBySide) {
                first.width() * 100f / union.width().coerceAtLeast(1)
            } else {
                first.height() * 100f / union.height().coerceAtLeast(1)
            }

        val signature: String
            get() = buildString {
                append(first.left).append(',').append(first.top).append(',')
                append(first.right).append(',').append(first.bottom).append('|')
                append(second.left).append(',').append(second.top).append(',')
                append(second.right).append(',').append(second.bottom).append('|')
                append(sideBySide)
            }
    }

    private fun build(s: GestureDescription.StrokeDescription) =
        GestureDescription.Builder().addStroke(s).build()

    private fun buildMulti(strokes: List<GestureDescription.StrokeDescription>): GestureDescription {
        val b = GestureDescription.Builder()
        for (s in strokes) b.addStroke(s)
        return b.build()
    }

    private fun dispatch(g: GestureDescription): Boolean = try {
        dispatchGesture(g, null, main)
    } catch (e: Exception) {
        Logx.w("Жест не отправлен: ${e.message}")
        false
    }

    companion object {
        private const val RATIO_TOLERANCE_PERCENT = 2.0f
        private const val RATIO_STABLE_MS = 700L
        private const val RATIO_VERIFY_MS = 1_500L
        private const val RATIO_DRAG_DURATION_MS = 700L
        private const val SPLIT_APPS_POLL_MS = 700L

        @Volatile var instance: MirrorAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, MirrorAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            val list = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled && list.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
