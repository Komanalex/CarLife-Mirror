package com.carlifemirror.app.navi

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.carlifemirror.app.util.Logx

/**
 * Читает только навигационные уведомления (Google Maps, Яндекс.Навигатор,
 * Waze и т.п.) и складывает последний манёвр в память процесса.
 *
 * Служба не сохраняет текст уведомлений, не пишет их в журнал и не передаёт
 * ничего, кроме манёвра, улицы, расстояний и иконки на CarLife-магнитолу.
 */
class NaviNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!NaviTurnParser.isNavi(this, sbn)) {
            // Приложение заменило навигационное уведомление обычным.
            if (NaviTurnStore.isTracked(sbn.key)) NaviTurnStore.remove(sbn.key)
            return
        }
        val turn = NaviTurnParser.parse(this, sbn) ?: return
        val before = NaviTurnStore.snapshot()
        NaviTurnStore.update(
            key = sbn.key,
            nextTurn = turn.nextTurn,
            roadName = turn.roadName,
            remainMeters = turn.remainMeters,
            totalMeters = turn.totalMeters,
            iconPng = turn.iconPng,
        )
        if (!before.active || before.turn?.nextTurn != turn.nextTurn) {
            Logx.i(
                "Навигация: манёвр ${turn.nextTurn} · ${turn.remainMeters} м · " +
                    "иконка ${turn.iconPng?.size ?: 0} Б"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NaviTurnStore.remove(it.key) }
    }

    override fun onListenerConnected() {
        Logx.i("Навигация: доступ к уведомлениям подключён")
    }

    override fun onListenerDisconnected() {
        NaviTurnStore.clear()
        Logx.i("Навигация: доступ к уведомлениям отключён")
    }
}
