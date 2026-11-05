package com.carlifemirror.app.system

import android.content.Context
import android.content.res.Configuration
import com.carlifemirror.app.AppTheme
import com.carlifemirror.app.Settings

/**
 * Применяет выбранную тему до создания Activity, чтобы нужные значения
 * resources (values / values-night) загрузились сразу — без AppCompat.
 */
object ThemeController {
    fun wrap(base: Context): Context {
        val mode = Settings(base).themeMode
        if (mode == AppTheme.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val nightMask = Configuration.UI_MODE_NIGHT_MASK
        val night = if (mode == AppTheme.DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and nightMask.inv()) or night
        return base.createConfigurationContext(config)
    }
}
