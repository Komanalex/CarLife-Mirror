package com.carlifemirror.app.navi

/**
 * Последнее состояние навигации в памяти процесса.
 *
 * NotificationListenerService обновляет его при каждом навигационном
 * уведомлении, а CarLifeMirrorSession периодически читает и отправляет на ГУ.
 * Ничего не пишется в SharedPreferences или на диск: после завершения
 * навигации и закрытия процесса данных не остаётся.
 *
 * Активность определяется наличием ключа навигационного уведомления, а не
 * временем последнего обновления. Навигатор может не обновлять уведомление
 * на стоянке, но маршрут при этом остаётся активным; ключ снимается в
 * onNotificationRemoved, когда уведомление действительно исчезло.
 */
object NaviTurnStore {
    data class Snapshot(val active: Boolean, val turn: NaviTurn?)

    private val activeKeys = HashSet<String>()
    private var version = 0L
    private var latest: NaviTurn? = null

    @Synchronized
    fun update(
        key: String,
        nextTurn: Int,
        roadName: String,
        remainMeters: Int,
        totalMeters: Int,
        iconPng: ByteArray?,
    ) {
        activeKeys.add(key)
        version++
        latest = NaviTurn(
            version = version,
            nextTurn = nextTurn,
            roadName = roadName,
            remainMeters = remainMeters,
            totalMeters = totalMeters,
            iconPng = iconPng,
        )
    }

    @Synchronized
    fun remove(key: String) {
        activeKeys.remove(key)
    }

    @Synchronized
    fun isTracked(key: String): Boolean = activeKeys.contains(key)

    @Synchronized
    fun clear() {
        activeKeys.clear()
        latest = null
        version++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(active = activeKeys.isNotEmpty() && latest != null, turn = latest)
}
