package com.carlifemirror.app.navi

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Разбор текста навигационного уведомления без Android-зависимостей.
 *
 * Подсказки Google Maps, Яндекс.Навигатора и Waze отличаются формулировками,
 * поэтому манёвр определяется по ключевым словам, расстояние — по числу с
 * единицей измерения. Здесь нет хранения текста: результат сразу превращается
 * в числа и короткое название улицы.
 */
object NaviText {
    /** Коды манёвров CarLife (как turn_front/turn_left/... в Baidu CarLife). */
    object Maneuver {
        const val STRAIGHT = 1
        const val SLIGHT_RIGHT = 2
        const val RIGHT = 3
        const val SHARP_RIGHT = 4
        const val U_TURN = 5
        const val SHARP_LEFT = 6
        const val LEFT = 7
        const val SLIGHT_LEFT = 8
        const val ROUNDABOUT = 9
        const val ROUNDABOUT_EXIT = 10
        const val KEEP_LEFT = 11
        const val KEEP_RIGHT = 12
        const val MERGE_LEFT = 13
        const val MERGE_RIGHT = 15
        const val START = 24
        const val DESTINATION = 25
    }

    private const val MAX_ROAD_NAME = 80
    private const val MAX_ROAD_BYTES = 31
    private const val DIRECTION_PREFIX = "В сторону "

    /** "500 m", "1,2 км", "In 300 m", "2.5 km" -> метры. */
    private val distanceRe = Regex(
        "(\\d+(?:[.,]\\d+)?)[\\s\\u00A0]*(км|km|миль|mi|фут|ft|м|m)(?=[\\s\\u00A0]|$|[,.);:])",
        RegexOption.IGNORE_CASE,
    )

    /** "1 ч 20 мин", "1 hour 5 min", "2 h" -> часы и необязательные минуты. */
    private val hourMinuteRe = Regex(
        "(\\d+)\\s*(?:час\\w*|hour\\w*|hr\\w*|h|ч)(?:\\s*(\\d+)\\s*(?:мин(?:ут\\w*)?|min(?:ute)?s?))?",
        RegexOption.IGNORE_CASE,
    )

    /** "5 мин", "12 minutes" -> минуты. */
    private val minuteRe = Regex(
        "(\\d+)\\s*(?:мин(?:ут\\w*)?|min(?:ute)?s?)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Манёвр по тексту заголовка/описания уведомления.
     * Проверки идут от частного к общему: sharp/slight/keep/merge раньше left/right,
     * иначе "keep left" превратится в обычный поворот.
     */
    fun maneuverOf(text: String): Int {
        val t = text.lowercase(Locale.ROOT)
        fun has(vararg words: String) = words.any { t.contains(it) }

        return when {
            has("arrive", "destination reached", "прибыт", "вы прибыли", "прибытие") ->
                Maneuver.DESTINATION
            has("маршрут начат", "start route", "start navigation", "начните движение") ->
                Maneuver.START
            has("u-turn", "u turn", "разворот") -> Maneuver.U_TURN
            has("roundabout", "кольцев", "кругов") ->
                if (has("exit", "съезд", "выход")) Maneuver.ROUNDABOUT_EXIT else Maneuver.ROUNDABOUT
            has("sharp left", "резко налево", "резкий поворот налево") -> Maneuver.SHARP_LEFT
            has("slight left", "плавно налево", "плавный поворот налево", "немного левее") ->
                Maneuver.SLIGHT_LEFT
            has("keep left", "держитесь левее", "оставайтесь левее", "придержитесь левее") ->
                Maneuver.KEEP_LEFT
            has("merge left", "перестройтесь левее", "влейтесь левее") -> Maneuver.MERGE_LEFT
            has("left", "налево", "влево", "левее") -> Maneuver.LEFT
            has("sharp right", "резко направо", "резкий поворот направо") -> Maneuver.SHARP_RIGHT
            has("slight right", "плавно направо", "плавный поворот направо", "немного правее") ->
                Maneuver.SLIGHT_RIGHT
            has("keep right", "держитесь правее", "оставайтесь правее", "придержитесь правее") ->
                Maneuver.KEEP_RIGHT
            has("merge right", "перестройтесь правее", "влейтесь правее") -> Maneuver.MERGE_RIGHT
            has("right", "направо", "вправо", "правее") -> Maneuver.RIGHT
            has("straight", "continue", "прямо", "продолжайте") -> Maneuver.STRAIGHT
            else -> Maneuver.STRAIGHT
        }
    }

    /** Текстовая формулировка манёвра для текстовых HUD-полей. */
    fun maneuverText(nextTurn: Int): String = when (nextTurn) {
        Maneuver.SHARP_LEFT -> "Резко налево"
        Maneuver.SLIGHT_LEFT -> "Плавно налево"
        Maneuver.KEEP_LEFT -> "Держитесь левее"
        Maneuver.MERGE_LEFT -> "Перестройтесь левее"
        Maneuver.LEFT -> "Поверните налево"
        Maneuver.SHARP_RIGHT -> "Резко направо"
        Maneuver.SLIGHT_RIGHT -> "Плавно направо"
        Maneuver.KEEP_RIGHT -> "Держитесь правее"
        Maneuver.MERGE_RIGHT -> "Перестройтесь правее"
        Maneuver.RIGHT -> "Поверните направо"
        Maneuver.U_TURN -> "Разворот"
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_EXIT -> "Круговое движение"
        Maneuver.DESTINATION -> "Пункт назначения"
        Maneuver.START -> "Начало маршрута"
        else -> "Двигайтесь прямо"
    }

    /** Имя иконки в стиле Baidu CarLife для NAVI_INFO. */
    fun iconName(nextTurn: Int): String = when (nextTurn) {
        Maneuver.SLIGHT_RIGHT -> "turn_right_front.png"
        Maneuver.RIGHT -> "turn_right.png"
        Maneuver.SHARP_RIGHT -> "turn_right_back.png"
        Maneuver.U_TURN -> "turn_back.png"
        Maneuver.SHARP_LEFT -> "turn_left_back.png"
        Maneuver.LEFT -> "turn_left.png"
        Maneuver.SLIGHT_LEFT -> "turn_left_front.png"
        Maneuver.ROUNDABOUT -> "turn_ring.png"
        Maneuver.ROUNDABOUT_EXIT -> "turn_ring_out.png"
        Maneuver.KEEP_LEFT -> "turn_left_side.png"
        Maneuver.KEEP_RIGHT -> "turn_right_side.png"
        Maneuver.MERGE_LEFT -> "turn_left_side_main.png"
        Maneuver.MERGE_RIGHT -> "turn_right_side_main.png"
        Maneuver.DESTINATION -> "turn_dest.png"
        Maneuver.START -> "turn_start.png"
        else -> "turn_front.png"
    }

    /** "500 м" / "1,2 км" для текстового поля HUD. */
    fun distanceDesc(meters: Int): String {
        if (meters <= 0) return ""
        if (meters < 1000) return "$meters м"
        val km = meters / 1000.0
        val value = if (meters % 1000 == 0) {
            (meters / 1000).toString()
        } else {
            String.format(Locale.US, "%.1f", km).replace('.', ',')
        }
        return "$value км"
    }

    /** "5 мин", "1 ч 20 мин" для текстового поля HUD; 0 — пусто. */
    fun timeDesc(seconds: Int): String {
        if (seconds <= 0) return ""
        val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
        if (minutes < 60) return "$minutes мин"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h ч" else "$h ч $m мин"
    }

    /** Время до манёвра из текста уведомления: "5 min", "1 ч 20 мин", "12 minutes". */
    fun timeSeconds(text: String): Int? {
        val hours = hourMinuteRe.find(text)
        if (hours != null) {
            val h = hours.groupValues[1].toIntOrNull() ?: return null
            val m = hours.groupValues[2].toIntOrNull() ?: 0
            return (h * 3600 + m * 60).coerceIn(0, 24 * 3600)
        }
        val minutes = minuteRe.find(text) ?: return null
        return ((minutes.groupValues[1].toIntOrNull() ?: return null) * 60)
            .coerceIn(0, 24 * 3600)
    }

    /**
     * Короткая подпись манёвра для roadName, когда навигатор не дал улицу.
     * Держим её в пределах [MAX_ROAD_BYTES], чтобы HUD не отбросил строку.
     */
    fun shortManeuverText(nextTurn: Int): String = when (nextTurn) {
        Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT,
        Maneuver.MERGE_LEFT, Maneuver.LEFT,
        -> "Налево"
        Maneuver.SHARP_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT,
        Maneuver.MERGE_RIGHT, Maneuver.RIGHT,
        -> "Направо"
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_EXIT -> "Круг"
        Maneuver.U_TURN -> "Разворот"
        Maneuver.DESTINATION -> "Пункт назначения"
        Maneuver.START -> "Начало"
        else -> "Прямо"
    }

    /**
     * Приводит roadName к безопасному для HUD виду: убирает служебный префикс,
     * схлопывает пробелы, подставляет короткий манёвр вместо пустой улицы и
     * обрезает по границе UTF-8. Лимит 31 байт взят из проверенного ограничения
     * приборки Honda; для HUD это консервативный запас, а не украшение.
     */
    fun fitRoadName(road: String, nextTurn: Int): String {
        var text = normalizeRoad(road)
        if (text.startsWith(DIRECTION_PREFIX, ignoreCase = true)) {
            text = text.substring(DIRECTION_PREFIX.length).trimStart()
        }
        if (text.count { it.isLetter() } < 2) text = shortManeuverText(nextTurn)
        val cut = truncateUtf8(text, MAX_ROAD_BYTES).trimEnd()
        return if (cut.count { it.isLetter() } >= 2) cut else shortManeuverText(nextTurn)
    }

    private fun normalizeRoad(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) ->
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                Character.getType(cp) == Character.CONTROL.toInt() ||
                    Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString().trim()
    }

    /** Обрезка по кодовым точкам, чтобы не разорвать UTF-8 посередине символа. */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = 0
        var bytes = 0
        while (end < value.length) {
            val cp = value.codePointAt(end)
            val next = bytes + when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (next > maxBytes) break
            bytes = next
            end += Character.charCount(cp)
        }
        return value.substring(0, end)
    }

    /** Название улицы/направления из заголовка или текста уведомления. */
    fun roadNameOf(title: String, body: String = ""): String {
        val markers = arrayOf(
            " onto ", " on ", " toward ", " to ",
            " на ", " по ", " в сторону ", " к ",
        )
        for (raw in arrayOf(title, body)) {
            val src = raw.trim()
            if (src.isEmpty()) continue
            for (marker in markers) {
                val i = src.indexOf(marker, ignoreCase = true)
                if (i < 0) continue
                val candidate = src.substring(i + marker.length)
                    .trim()
                    .trim('.', ',', ';', ':', '"', '\'', '»', '«')
                if (candidate.count { it.isLetter() } >= 2) {
                    return candidate.take(MAX_ROAD_NAME)
                }
            }
        }
        return title.ifBlank { body }.trim().take(MAX_ROAD_NAME)
    }

    /** Расстояние до манёвра в метрах; null, если в тексте нет числа с единицей. */
    fun distanceMeters(text: String): Int? {
        val m = distanceRe.find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val meters = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "км", "km" -> value * 1000.0
            "mi", "миль" -> value * 1609.0
            "ft", "фут" -> value * 0.3048
            else -> value
        }
        return meters.roundToInt().coerceIn(0, 10_000_000)
    }

    /**
     * Оставшийся маршрут, если приложение положило его в infoText/subText.
     * Меньше расстояния до манёвра не берём: это наверняка не весь маршрут.
     */
    fun routeMeters(info: String, sub: String, remainMeters: Int): Int {
        val infoMeters = distanceMeters(info)
        if (infoMeters != null && infoMeters > remainMeters) return infoMeters
        val subMeters = distanceMeters(sub)
        if (subMeters != null && subMeters > remainMeters) return subMeters
        return 0
    }

    /** Навигация закончилась (прибытие), а не просто обновилось расстояние. */
    fun isArrived(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return t.contains("arrive") || t.contains("прибыт") || t.contains("вы прибыли")
    }
}
