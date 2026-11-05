package com.carlifemirror.app.navi

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.io.ByteArrayOutputStream

/**
 * Достаёт из навигационного уведомления манёвр, улицу, расстояния и иконку.
 *
 * Работает только с уведомлениями навигации: категория CATEGORY_NAVIGATION
 * либо известный навигационный пакет с расстоянием и признаком ongoing.
 * Остальные уведомления игнорируются и нигде не сохраняются.
 */
object NaviTurnParser {
    private val NAVI_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "ru.yandex.yandexnavi",
        "ru.yandex.maps",
        "ru.dublgis.dgismobile",
        "com.here.app.maps",
        "com.tomtom.gplay.navapp",
        "com.sygic.aura",
        "com.baidu.BaiduMap",
        "com.autonavi.minimap",
        "com.tencent.map",
    )

    /** Как в Baidu CarLife: иконка не больше 30 КиБ (0x7800). */
    private const val MAX_ICON_BYTES = 0x7800
    private const val ICON_SIZE = 96

    private var iconCacheKey: String? = null
    private var iconCache: ByteArray? = null
    private val fallbackIcons = HashMap<Int, ByteArray>()

    fun isNavi(context: Context, sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == context.packageName) return false
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        if (sbn.packageName !in NAVI_PACKAGES) return false
        // У Maps бывают и ненавигационные ongoing-уведомления. Навигацию
        // выдаёт расстояние до манёвра, которого в них нет.
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        val text = listOf(
            text(n.extras, Notification.EXTRA_TITLE),
            text(n.extras, Notification.EXTRA_TEXT),
            text(n.extras, Notification.EXTRA_SUB_TEXT),
            text(n.extras, Notification.EXTRA_INFO_TEXT),
        ).joinToString(" ")
        return NaviText.distanceMeters(text) != null
    }

    @Synchronized
    fun parse(context: Context, sbn: StatusBarNotification): NaviTurn? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val title = text(extras, Notification.EXTRA_TITLE)
        val body = text(extras, Notification.EXTRA_TEXT)
        val sub = text(extras, Notification.EXTRA_SUB_TEXT)
        val info = text(extras, Notification.EXTRA_INFO_TEXT)
        val all = listOf(title, body, sub, info).filter { it.isNotBlank() }.joinToString(" ")
        if (all.isBlank()) return null

        val remain = NaviText.distanceMeters(body)
            ?: NaviText.distanceMeters(title)
            ?: NaviText.distanceMeters(sub)
            ?: NaviText.distanceMeters(info)
            ?: 0
        // Иконка меняется только вместе с заголовком, а уведомление обновляется
        // каждую секунду из-за расстояния. Без кэша PNG пережимался бы зря.
        val nextTurn = NaviText.maneuverOf(all)
        val iconKey = "${sbn.key}|$title|$nextTurn"
        val icon = if (iconKey == iconCacheKey) {
            iconCache
        } else {
            iconPng(context, extras, nextTurn)?.also {
                iconCacheKey = iconKey
                iconCache = it
            }
        }
        return NaviTurn(
            version = 0,
            nextTurn = nextTurn,
            roadName = NaviText.fitRoadName(NaviText.roadNameOf(title, body), nextTurn),
            remainMeters = remain,
            totalMeters = NaviText.routeMeters(info, sub, remain),
            iconPng = icon,
            timeSeconds = NaviText.timeSeconds(sub)
                ?: NaviText.timeSeconds(info)
                ?: NaviText.timeSeconds(body)
                ?: NaviText.timeSeconds(title)
                ?: 0,
        )
    }

    private fun text(extras: Bundle?, key: String): String =
        extras?.getCharSequence(key)?.toString()
            ?.replace('\u00A0', ' ')
            ?.trim()
            .orEmpty()

    private fun largeIcon(context: Context, extras: Bundle): Bitmap? {
        bitmap(extras, Notification.EXTRA_LARGE_ICON_BIG)?.let { return it }
        bitmap(extras, Notification.EXTRA_LARGE_ICON)?.let { return it }
        // Некоторые приложения кладут Icon вместо Bitmap.
        val any = extras.get(Notification.EXTRA_LARGE_ICON)
        if (any is Icon) return drawableToBitmap(context, any)
        return null
    }

    @Suppress("DEPRECATION")
    private fun bitmap(extras: Bundle, key: String): Bitmap? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(key, Bitmap::class.java)
        } else {
            extras.getParcelable(key)
        }

    private fun drawableToBitmap(context: Context, icon: Icon): Bitmap? {
        val drawable = icon.loadDrawable(context) ?: return null
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return out
    }

    private fun iconPng(context: Context, extras: Bundle, nextTurn: Int): ByteArray? {
        val src = largeIcon(context, extras)
        if (src != null) {
            var max = ICON_SIZE
            repeat(3) {
                val scaled = scale(src, max)
                val bytes = compress(scaled)
                if (scaled !== src) scaled.recycle()
                if (bytes != null && bytes.size <= MAX_ICON_BYTES) return bytes
                max /= 2
            }
        }
        return fallbackIcon(nextTurn)
    }

    /**
     * Если навигатор не дал bitmap-иконку, HUD всё равно должен получить
     * стрелку. Рисуем простой белый символ манёвра на прозрачном фоне.
     */
    private fun fallbackIcon(nextTurn: Int): ByteArray? {
        fallbackIcons[nextTurn]?.let { return it }
        val bitmap = try {
            drawFallbackIcon(nextTurn)
        } catch (_: Exception) {
            return null
        }
        val bytes = compress(bitmap)
        bitmap.recycle()
        if (bytes == null || bytes.size > MAX_ICON_BYTES) return null
        fallbackIcons[nextTurn] = bytes
        return bytes
    }

    private fun drawFallbackIcon(nextTurn: Int): Bitmap {
        val size = ICON_SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = size / 2f
        val bottom = size - 14f
        val top = 14f
        val mid = size / 2f

        fun arrow(x: Float, y: Float, dx: Float, dy: Float) {
            val len = 15f
            val nx = -dy
            val ny = dx
            canvas.drawLine(
                x, y,
                x - dx * len + nx * len * 0.62f,
                y - dy * len + ny * len * 0.62f,
                paint,
            )
            canvas.drawLine(
                x, y,
                x - dx * len - nx * len * 0.62f,
                y - dy * len - ny * len * 0.62f,
                paint,
            )
        }

        when (nextTurn) {
            NaviText.Maneuver.U_TURN -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(cx - 18f, bottom)
                        lineTo(cx - 18f, mid - 10f)
                        quadTo(cx - 18f, top, cx + 2f, top)
                        quadTo(cx + 18f, top, cx + 18f, mid - 10f)
                        lineTo(cx + 18f, bottom - 24f)
                    },
                    paint,
                )
                arrow(cx + 18f, bottom - 24f, 0f, 1f)
            }
            NaviText.Maneuver.ROUNDABOUT, NaviText.Maneuver.ROUNDABOUT_EXIT -> {
                canvas.drawCircle(cx, mid, 25f, paint)
                arrow(cx + 25f, mid, 1f, 0f)
            }
            NaviText.Maneuver.DESTINATION -> {
                canvas.drawLine(cx - 10f, bottom, cx - 10f, top, paint)
                val filled = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawPath(
                    Path().apply {
                        moveTo(cx - 10f, top)
                        lineTo(cx + 24f, top + 12f)
                        lineTo(cx - 10f, top + 24f)
                        close()
                    },
                    filled,
                )
            }
            NaviText.Maneuver.LEFT,
            NaviText.Maneuver.SHARP_LEFT,
            NaviText.Maneuver.SLIGHT_LEFT,
            NaviText.Maneuver.KEEP_LEFT,
            NaviText.Maneuver.MERGE_LEFT,
            -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(cx + 16f, bottom)
                        lineTo(cx + 16f, mid)
                        lineTo(cx - 20f, mid)
                    },
                    paint,
                )
                arrow(cx - 20f, mid, -1f, 0f)
            }
            NaviText.Maneuver.RIGHT,
            NaviText.Maneuver.SHARP_RIGHT,
            NaviText.Maneuver.SLIGHT_RIGHT,
            NaviText.Maneuver.KEEP_RIGHT,
            NaviText.Maneuver.MERGE_RIGHT,
            -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(cx - 16f, bottom)
                        lineTo(cx - 16f, mid)
                        lineTo(cx + 20f, mid)
                    },
                    paint,
                )
                arrow(cx + 20f, mid, 1f, 0f)
            }
            else -> {
                canvas.drawLine(cx, bottom, cx, top, paint)
                arrow(cx, top, 0f, -1f)
            }
        }
        return bitmap
    }

    private fun scale(src: Bitmap, max: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (w <= max && h <= max) return src
        val k = minOf(max.toFloat() / w, max.toFloat() / h)
        val nw = (w * k).toInt().coerceAtLeast(1)
        val nh = (h * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            src,
            null,
            RectF(0f, 0f, nw.toFloat(), nh.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun compress(bitmap: Bitmap): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    } catch (_: Exception) {
        null
    }
}
