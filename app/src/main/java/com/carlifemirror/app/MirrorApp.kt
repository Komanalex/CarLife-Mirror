package com.carlifemirror.app

import android.app.Application
import com.carlifemirror.app.system.BrightnessController
import com.carlifemirror.app.system.RotationController
import com.carlifemirror.app.util.CrashLog
import com.carlifemirror.app.util.Logx

/**
 * Инициализирует файловый журнал до открытия Activity/Service и ставит
 * перехватчик аварий. Файл журнала затем копируется в Загрузки при завершении
 * сеанса или по кнопке в интерфейсе.
 */
class MirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(this)
        CrashLog.install(this)
        try {
            RotationController.restoreIfStale(this)
        } catch (e: Exception) {
            Logx.e("Не удалось восстановить системный поворот", e)
        }
        try {
            BrightnessController.restoreIfStale(this)
        } catch (e: Exception) {
            Logx.e("Не удалось восстановить системную яркость", e)
        }
        Logx.i("Application создано; журнал: ${Logx.currentFilePath()}")
    }
}
