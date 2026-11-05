package com.carlifemirror.app.navi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.carlifemirror.app.util.Logx

/** Доступ к уведомлениям и переход в системные настройки. */
object NaviAccess {
    private const val SECURE_KEY = "enabled_notification_listeners"

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, NaviNotificationListener::class.java)
        val raw = android.provider.Settings.Secure.getString(
            context.contentResolver,
            SECURE_KEY,
        ) ?: return false
        return raw.split(':').any { part ->
            val c = ComponentName.unflattenFromString(part) ?: return@any false
            c.packageName == expected.packageName && c.className == expected.className
        }
    }

    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Logx.w("Навигация: не удалось открыть доступ к уведомлениям: ${e.message}")
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }
}
