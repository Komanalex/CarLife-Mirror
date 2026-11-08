package com.carlifemirror.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.carlifemirror.app.util.Logx

/**
 * Глушит динамик телефона на время AudioPlaybackCapture, чтобы звук не шёл
 * одновременно из телефона и CarLife. Внешние маршруты (Bluetooth, наушники,
 * USB-гарнитура) не трогаем: там пользователь мог сам выбрать вывод.
 */
object AudioOutputMute {
    private var manager: AudioManager? = null
    private var mutedByUs = false

    @Synchronized
    fun muteIfPhoneSpeaker(context: Context) {
        if (mutedByUs) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (hasExternalOutput(am)) {
            Logx.i("Звук: внешний аудиовыход подключён, динамик телефона не глушу")
            return
        }
        try {
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                Logx.i("Звук: музыка уже была заглушена пользователем")
                return
            }
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            manager = am
            mutedByUs = true
            Logx.i("Звук: динамик телефона заглушён на время захвата")
        } catch (e: Exception) {
            Logx.w("Звук: не удалось заглушить динамик: ${e.message}")
        }
    }

    @Synchronized
    fun restore() {
        if (!mutedByUs) return
        try {
            manager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Logx.i("Звук: динамик телефона снова включён")
        } catch (e: Exception) {
            Logx.w("Звук: не удалось вернуть звук динамика: ${e.message}")
        } finally {
            mutedByUs = false
            manager = null
        }
    }

    private fun hasExternalOutput(am: AudioManager): Boolean {
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    -> true
                    else -> false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
