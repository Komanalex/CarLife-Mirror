package com.carlifemirror.app.navi

/**
 * Что класть в поле 6 NAVI_NEXT_TURN_INFO.
 *
 * В CarLife 8.6.7 поле 6 — bytes PNG-иконки; в apollo/DuerOS и части ГУ —
 * int32 время до манёвра в секундах. Protobuf разрешает повторять поле с
 * разным wire-type: парсер, ждущий один вариант, уносит второй в unknown.
 * Поэтому для ГУ, которым нужен только один вариант, режим можно сузить.
 */
enum class NaviField6(val icon: Boolean, val time: Boolean) {
    ICON(icon = true, time = false),
    TIME(icon = false, time = true),
    BOTH(icon = true, time = true),
    NONE(icon = false, time = false);

    companion object {
        fun fromName(value: String?): NaviField6 =
            entries.firstOrNull { it.name == value } ?: ICON
    }
}
