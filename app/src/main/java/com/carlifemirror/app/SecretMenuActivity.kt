package com.carlifemirror.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.carlifemirror.app.car.CarDataStore
import com.carlifemirror.app.car.CarDataParser
import com.carlifemirror.app.system.ThemeController

/**
 * Секретная диагностика: все автомобильные подписки CarLife и разобранные
 * ответы ГУ. Открывается 10 нажатиями на версию в настройках.
 */
class SecretMenuActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var data: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var binding = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_menu)
        settings = Settings(this)
        applyTopInset()

        status = findViewById(R.id.secretStatus)
        sub = findViewById(R.id.secretSub)
        data = findViewById(R.id.secretData)
        autoSwitch = findViewById(R.id.secretAutoSwitch)
        findViewById<TextView>(R.id.secretVersion).text = versionName()
        findViewById<ImageButton>(R.id.secretBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.secretRequest).setOnClickListener {
            settings.carDataProbe = true
            CarDataStore.requestAll()
            bindAuto(true)
            Toast.makeText(this, R.string.secret_request, Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.secretStop).setOnClickListener {
            settings.carDataProbe = false
            CarDataStore.requestStop()
            bindAuto(false)
            render()
        }

        bindAuto(settings.carDataProbe)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            settings.carDataProbe = checked
            if (checked) {
                CarDataStore.requestAll()
            } else {
                CarDataStore.requestStop()
            }
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun bindAuto(value: Boolean) {
        binding = true
        autoSwitch.isChecked = value
        binding = false
    }

    private fun applyTopInset() {
        val root = findViewById<View>(R.id.secretRoot)
        val header = findViewById<View>(R.id.secretHeader)
        val baseTop = header.paddingTop
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            header.setPadding(
                header.paddingLeft,
                baseTop + top + dp(8),
                header.paddingRight,
                header.paddingBottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun render() {
        val snapshot = CarDataStore.snapshot()
        val session = snapshot.session
        status.text = getString(
            if (session?.active == true) R.string.secret_status_active else R.string.secret_status_none
        )
        sub.text = if (session == null) {
            getString(R.string.secret_sub)
        } else {
            buildString {
                append(session.transport)
                if (session.huName != "—") append(" · ").append(session.huName)
                if (session.channel.isNotBlank()) append(" · канал ").append(session.channel)
                if (session.protocol != "—") append(" · CarLife ").append(session.protocol)
                if (session.auth != "—") append(" · ").append(session.auth)
            }
        }
        data.text = buildString {
            appendLine("Статус: ${snapshot.status}")
            appendLine("Автозапрос: ${if (settings.carDataProbe) "вкл" else "выкл"}")
            appendLine("Поддержано: ${CarDataParser.moduleListText(snapshot.supported.map { it.moduleID })}")
            appendLine("Запрошено: ${CarDataParser.moduleListText(snapshot.requested)}")
            appendLine("Запущено: ${CarDataParser.moduleListText(snapshot.started)}")
            if (snapshot.supported.isNotEmpty()) {
                appendLine()
                appendLine("Ответ CAR_DATA_SUBSCRIBE:")
                for (m in snapshot.supported) {
                    appendLine(
                        "  ${m.moduleID} ${CarDataParser.moduleName(m.moduleID)}" +
                            " · flag=${m.flag} · freq=${m.frequency} · support=${m.supportFlag}"
                    )
                }
            }
            appendLine()
            appendLine("Счётчики:")
            if (snapshot.counts.isEmpty()) {
                appendLine("  —")
            } else {
                for ((kind, count) in snapshot.counts) appendLine("  $kind: $count")
            }
            appendLine()
            appendLine("Последние значения:")
            if (snapshot.values.isEmpty()) {
                appendLine("  —")
            } else {
                val grouped = snapshot.values.entries.groupBy { it.key.substringBefore('.') }
                for ((kind, entries) in grouped) {
                    appendLine("  $kind:")
                    for ((key, value) in entries) {
                        appendLine("    ${key.substringAfter('.')}: $value")
                    }
                }
            }
            if (snapshot.raw.isNotEmpty()) {
                appendLine()
                appendLine("Неизвестные CMD (последние):")
                for (line in snapshot.raw) appendLine("  $line")
            }
        }
    }
}
