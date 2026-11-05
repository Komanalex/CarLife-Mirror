package com.carlifemirror.app.navi

/**
 * Один шаг навигации для отправки на ГУ.
 *
 * Содержит только то, что нужно turn-by-turn: манёвр, улицу, расстояния и
 * PNG-иконку. Текст уведомления и название улицы не сохраняются на диск.
 */
data class NaviTurn(
    val version: Long,
    val nextTurn: Int,
    val roadName: String,
    val remainMeters: Int,
    val totalMeters: Int,
    val iconPng: ByteArray?,
    /** Время до манёвра в секундах; 0 — навигатор его не дал. */
    val timeSeconds: Int = 0,
)
