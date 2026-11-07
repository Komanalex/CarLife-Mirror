package com.carlifemirror.app

import android.annotation.SuppressLint
import android.content.Context
import com.carlifemirror.app.carlife.Handshake
import com.carlifemirror.app.navi.NaviField6

/**
 * Размер кадра, который приложение предлагает магнитоле.
 * AUTO — использовать размер, запрошенный ГУ.
 */
enum class Resolution(val width: Int, val height: Int) {
    AUTO(0, 0),
    P720(1280, 720),
    P1080(1920, 1080),
    P2K(2560, 1440);

    companion object {
        fun fromName(value: String?): Resolution =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromName(value: String?): AppTheme =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Как телефон соединяется с магнитолой. USB остаётся режимом по умолчанию. */
enum class ConnectMode {
    USB,
    WIRELESS;

    companion object {
        fun fromName(value: String?): ConnectMode =
            entries.firstOrNull { it.name == value } ?: USB
    }
}

@SuppressLint("ApplySharedPref")
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("carlife_mirror", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    var resolution: Resolution
        get() = Resolution.fromName(sp.getString("resolution", Resolution.AUTO.name))
        set(value) = sp.edit().putString("resolution", value.name).apply()

    var themeMode: AppTheme
        get() = AppTheme.fromName(sp.getString("theme_mode", AppTheme.SYSTEM.name))
        set(value) = sp.edit().putString("theme_mode", value.name).apply()

    /**
     * Режим подключения. Новые установки и обновления с прежних версий
     * начинают с USB, поэтому привычное поведение не меняется.
     */
    var connectionMode: ConnectMode
        get() = ConnectMode.fromName(sp.getString("connection_mode", ConnectMode.USB.name))
        set(value) = sp.edit().putString("connection_mode", value.name).apply()

    /**
     * По умолчанию используется автоматический размер, запрошенный ГУ.
     * Пользователь может включить свой размер отдельно.
     */
    var customResolution: Boolean
        get() = sp.getBoolean("custom_resolution", false)
        set(value) = sp.edit().putBoolean("custom_resolution", value).apply()

    var customWidth: Int
        get() = sp.getInt("custom_width", DEFAULT_CUSTOM_W)
        set(value) = sp.edit().putInt("custom_width", value).apply()

    var customHeight: Int
        get() = sp.getInt("custom_height", DEFAULT_CUSTOM_H)
        set(value) = sp.edit().putInt("custom_height", value).apply()

    /** Целевой размер кадра; 0x0 = автоматически по запросу магнитолы. */
    val targetWidth: Int get() = if (customResolution) customWidth else resolution.width
    val targetHeight: Int get() = if (customResolution) customHeight else resolution.height

    var touchEnabled: Boolean
        get() = sp.getBoolean("touch", true)
        set(value) = sp.edit().putBoolean("touch", value).apply()

    /**
     * Гасить экран во время трансляции: экран затемняется до
     * [brightnessDimPercent]. Выключено — яркость не меняется.
     *
     * Новый ключ `dim_screen`; если его ещё нет, берём прежнюю настройку
     * «Не гасить экран» с обратным знаком. Так обновление сохраняет текущее
     * поведение: старый `keep_awake=true` — экран не гаснет, новый
     * `dim_screen=false`; старый `keep_awake=false` — гаснет, новый `true`.
     * На чистой установке затемнение выключено.
     */
    var dimScreen: Boolean
        get() = if (sp.contains("dim_screen")) {
            sp.getBoolean("dim_screen", false)
        } else {
            !sp.getBoolean("keep_awake", true)
        }
        set(value) = sp.edit().putBoolean("dim_screen", value).apply()

    /** Передавать звук приложений на магнитолу по USB (CarLife MEDIA). */
    var usbAudio: Boolean
        get() = sp.getBoolean("usb_audio", true)
        set(value) = sp.edit().putBoolean("usb_audio", value).apply()

    /**
     * Не захватывать звук в CarLife, а отдать его Bluetooth-аудио магнитолы.
     * В ГУ при этом нужно выбрать источник Bluetooth.
     */
    var audioViaBluetooth: Boolean
        get() = sp.getBoolean("audio_via_bluetooth", false)
        set(value) = sp.edit().putBoolean("audio_via_bluetooth", value).apply()

    /**
     * HUD отключён: CNS3 не принимает turn-by-turn ни в одном режиме.
     * Оставляем поля для чтения старых настроек, но не отправляем сообщения.
     */
    var naviHud: Boolean
        get() = false
        set(value) = sp.edit().putBoolean("navi_hud", false).apply()

    var naviInfoHud: Boolean
        get() = false
        set(value) = sp.edit().putBoolean("navi_info_hud", false).apply()

    /**
     * Поле 6 стандартного NAVI_NEXT_TURN_INFO: PNG-иконка, время до манёвра
     * или оба. По умолчанию иконка — как было до появления выбора.
     */
    var naviField6: NaviField6
        get() = NaviField6.fromName(sp.getString("navi_field6", NaviField6.ICON.name))
        set(value) = sp.edit().putString("navi_field6", value.name).apply()

    /**
     * Фиксировать системный ландшафт, пока идёт трансляция.
     * Требует специального разрешения «Изменение системных настроек».
     */
    var systemLandscape: Boolean
        get() = sp.getBoolean("system_landscape", true)
        set(value) = sp.edit().putBoolean("system_landscape", value).apply()

    /** Автоматически открывать выбранное приложение вторым окном после старта видео. */
    var splitAuto: Boolean
        get() = sp.getBoolean("split_auto", false)
        set(value) = sp.edit().putBoolean("split_auto", value).apply()

    /**
     * Ширина первой (левой/верхней) панели split в процентах.
     * Значение хранится шагом 10% в диапазоне 30..70: Samsung не даёт
     * надёжно выставить крайние положения, ниже 30% панель сворачивается.
     */
    var splitRatioFirst: Int
        get() = sp.getInt("split_ratio_first", 50).coerceIn(30, 70) / 10 * 10
        set(value) {
            val normalized = value.coerceIn(30, 70) / 10 * 10
            sp.edit().putInt("split_ratio_first", normalized).apply()
        }

    /** Сразу открывать системный запрос захвата экрана при запуске приложения. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(value) = sp.edit().putBoolean("auto_start", value).apply()

    /** ComponentName.flattenToString() приложения для второго окна; пусто — не выбрано. */
    var splitApp: String
        get() = sp.getString("split_app", "").orEmpty()
        set(value) = sp.edit().putString("split_app", value).apply()

    /** ComponentName.flattenToString() первого окна; пусто — сам CarLife Mirror. */
    var splitAppFirst: String
        get() = sp.getString("split_app_first", "").orEmpty()
        set(value) = sp.edit().putString("split_app_first", value).apply()

    /** Приложение, которое реально открывалось первым окном в текущем сеансе. */
    var lastSplitFirstApp: String
        get() = sp.getString("last_split_first_app", "").orEmpty()
        set(value) = sp.edit().putString("last_split_first_app", value).apply()

    /** Приложение, которое реально открывалось вторым окном в текущем сеансе. */
    var lastSplitApp: String
        get() = sp.getString("last_split_app", "").orEmpty()
        set(value) = sp.edit().putString("last_split_app", value).apply()

    /** Не сбрасывать последнее второе приложение после остановки трансляции. */
    var rememberSplitApps: Boolean
        get() = sp.getBoolean("remember_split_apps", false)
        set(value) = sp.edit().putBoolean("remember_split_apps", value).apply()

    /** Активна ли текущая split-сессия; используется трекером окон. */
    var splitSessionActive: Boolean
        get() = sp.getBoolean("split_session_active", false)
        set(value) = sp.edit().putBoolean("split_session_active", value).apply()

    /**
     * Автоделение уже сработало или пользователь открыл приложение из шторки.
     * До конца текущей трансляции повторно не запускаем split автоматически.
     */
    var autoSplitSuppressedForSession: Boolean
        get() = sp.getBoolean("auto_split_suppressed_session", false)
        set(value) = sp.edit().putBoolean("auto_split_suppressed_session", value).apply()

    /**
     * Для текущей трансляции уже решено, уходить ли на рабочий стол после
     * старта. Флаг переживает пересоздание Activity и сбрасывается только
     * при новом запуске проекции.
     */
    var autoHomeDoneForSession: Boolean
        get() = sp.getBoolean("auto_home_done_session", false)
        set(value) = sp.edit().putBoolean("auto_home_done_session", value).apply()

    /**
     * Снимок системной яркости до затемнения. Нужен, чтобы пережить смерть
     * процесса и восстановить яркость при следующем запуске.
     */
    var brightnessDimSaved: Boolean
        get() = sp.getBoolean("brightness_dim_saved", false)
        set(value) { sp.edit().putBoolean("brightness_dim_saved", value).commit() }

    var brightnessOriginalMode: Int
        get() = sp.getInt("brightness_original_mode", -1)
        set(value) { sp.edit().putInt("brightness_original_mode", value).commit() }

    var brightnessOriginalValue: Int
        get() = sp.getInt("brightness_original_value", -1)
        set(value) { sp.edit().putInt("brightness_original_value", value).commit() }

    /**
     * До какой яркости затемнять экран во время трансляции.
     * 0% — полностью чёрный экран, 1% — прежнее поведение.
     */
    var brightnessDimPercent: Int
        get() = sp.getInt("brightness_dim_percent", 1).coerceIn(0, 100)
        set(value) = sp.edit().putInt("brightness_dim_percent", value.coerceIn(0, 100)).apply()

    /** Снимок окон прошлой сессии для следующего запуска при включённом запоминании. */
    var rememberedSplitFirstApp: String
        get() = sp.getString("remembered_split_first_app", "").orEmpty()
        set(value) = sp.edit().putString("remembered_split_first_app", value).apply()

    var rememberedSplitSecondApp: String
        get() = sp.getString("remembered_split_second_app", "").orEmpty()
        set(value) = sp.edit().putString("remembered_split_second_app", value).apply()

    var hasRememberedSplit: Boolean
        get() = sp.getBoolean("has_remembered_split", false)
        set(value) = sp.edit().putBoolean("has_remembered_split", value).apply()

    /** Поддерживаемые варианты: 15, 30 или 60 кадров в секунду. */
    var fps: Int
        get() = sp.getInt("fps", Handshake.WORKING_FPS).let {
            if (it >= 60) 60 else if (it <= 15) 15 else 30
        }
        set(value) = sp.edit().putInt("fps", when {
            value >= 60 -> 60
            value <= 15 -> 15
            else -> 30
        }).apply()

    /**
     * Начальные настройки нового установленного приложения:
     * AUTO, без своего размера, 30 FPS. Уже существующие настройки
     * пользователя не перезаписываются.
     */
    private fun migrate() {
        val version = sp.getInt(KEY_SETTINGS_VERSION, 0)
        if (version >= SETTINGS_VERSION) return
        sp.edit()
            .putBoolean("custom_resolution", false)
            .putInt("custom_width", DEFAULT_CUSTOM_W)
            .putInt("custom_height", DEFAULT_CUSTOM_H)
            .putInt("fps", Handshake.WORKING_FPS)
            .putString("resolution", Resolution.AUTO.name)
            .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
            .apply()
    }

    companion object {
        const val DEFAULT_CUSTOM_W = Handshake.WORKING_WIDTH
        const val DEFAULT_CUSTOM_H = Handshake.WORKING_HEIGHT
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val SETTINGS_VERSION = 2
    }
}
