package com.carlifemirror.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carlifemirror.app.util.Logx

/** Кнопка «ОК» в уведомлении: просто закрывает его. */
class HuBtAudioDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HuBtAudioNotification.ACTION_DISMISS) return
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(HuBtAudioNotification.ID)
            Logx.i("Звук ГУ: уведомление закрыто кнопкой ОК")
        } catch (_: Exception) {}
    }
}
