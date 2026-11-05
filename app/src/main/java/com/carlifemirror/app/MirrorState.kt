package com.carlifemirror.app

import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { IDLE, WAITING_USB, WAITING_WIFI, CONNECTING, STREAMING, PAUSED, ERROR }

data class MirrorSnapshot(
    val phase: Phase = Phase.IDLE,
    val title: String = "Готово к запуску",
    val detail: String = "Подключите телефон к магнитоле USB-кабелем и разрешите захват экрана.",
    val hu: String = "",
    val video: String = "",
    val frames: Long = 0,
    val dropped: Long = 0,
    val usbAttached: Boolean = false,
    val projectionReady: Boolean = false,
    val touchEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val mainInMultiWindow: Boolean = false,
    val connection: ConnectMode = ConnectMode.USB,
    val linkState: String = "",
    val error: Boolean = false,
)

/**
 * Состояние приложения для UI. Обновляется из сервиса и читается Activity.
 * Никаких персональных данных здесь не хранится.
 */
object MirrorState {
    @Volatile var value = MirrorSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(MirrorSnapshot) -> Unit>()

    fun addListener(l: (MirrorSnapshot) -> Unit) {
        listeners.addIfAbsent(l)
        l(value)
    }

    fun removeListener(l: (MirrorSnapshot) -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun update(block: (MirrorSnapshot) -> MirrorSnapshot) {
        val next = block(value)
        value = next
        for (l in listeners) {
            try { l(next) } catch (_: Throwable) {}
        }
    }

    fun reset() = update { MirrorSnapshot() }
}
