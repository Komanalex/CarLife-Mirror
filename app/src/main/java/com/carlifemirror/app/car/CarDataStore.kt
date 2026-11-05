package com.carlifemirror.app.car

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Состояние диагностики автомобильных данных CarLife для секретного меню.
 * Живёт в памяти процесса; ничего не пишет на диск.
 */
object CarDataStore {
    data class SessionInfo(
        val active: Boolean,
        val transport: String,
        val huName: String,
        val channel: String,
        val protocol: String,
        val auth: String,
    )

    data class Snapshot(
        val session: SessionInfo?,
        val status: String,
        val supported: List<VehicleModule>,
        val requested: List<Int>,
        val started: List<Int>,
        val counts: Map<String, Long>,
        val values: Map<String, String>,
        val raw: List<String>,
        val requestSeq: Long,
        val stopSeq: Long,
    )

    private var session: SessionInfo? = null
    private var status = "Сессия не запущена"
    private var supported: List<VehicleModule> = emptyList()
    private var requested: List<Int> = emptyList()
    private val started = linkedSetOf<Int>()
    private val counts = LinkedHashMap<String, Long>()
    private val values = LinkedHashMap<String, String>()
    private val raw = ArrayDeque<String>()

    @Volatile
    var requestSeq: Long = 0L
        private set

    @Volatile
    var stopSeq: Long = 0L
        private set

    @Synchronized
    fun requestAll(): Long {
        requestSeq++
        requested = CarDataParser.allModules.sorted()
        status = "Запрошены все модули (${CarDataParser.moduleListText(requested)})"
        return requestSeq
    }

    @Synchronized
    fun requestStop(): Long {
        stopSeq++
        status = "Запрошена остановка подписки"
        return stopSeq
    }

    @Synchronized
    fun beginSession(transport: String) {
        session = SessionInfo(
            active = true,
            transport = transport,
            huName = "—",
            channel = "",
            protocol = "—",
            auth = "—",
        )
        status = "Сессия запущена, ждём данных"
        supported = emptyList()
        requested = emptyList()
        started.clear()
        counts.clear()
        values.clear()
        raw.clear()
    }

    @Synchronized
    fun updateSession(huName: String, channel: String, protocol: String, auth: String) {
        val current = session ?: return
        session = current.copy(
            huName = huName,
            channel = channel,
            protocol = protocol,
            auth = auth,
        )
    }

    @Synchronized
    fun endSession(reason: String) {
        val current = session ?: return
        session = current.copy(active = false)
        status = "Сессия завершена: $reason"
    }

    @Synchronized
    fun onRequested(modules: Collection<Int>) {
        requested = modules.sorted()
        status = "Запрошены: ${CarDataParser.moduleListText(modules)}"
    }

    @Synchronized
    fun onSupported(modules: List<VehicleModule>) {
        supported = modules
        status = if (modules.isEmpty()) {
            "ГУ не прислала список поддерживаемых модулей"
        } else {
            "ГУ поддержало: ${modules.joinToString(", ") { "${it.moduleID} ${CarDataParser.moduleName(it.moduleID)}" }}"
        }
    }

    @Synchronized
    fun onStarted(modules: Collection<Int>) {
        started.addAll(modules)
        status = "Запущено: ${CarDataParser.moduleListText(started)}"
    }

    @Synchronized
    fun onStopped() {
        started.clear()
        status = "Подписка остановлена"
    }

    @Synchronized
    fun onData(kind: String, fields: Map<String, String>, rawBody: String) {
        counts[kind] = (counts[kind] ?: 0L) + 1L
        for ((key, value) in fields) values["$kind.$key"] = value
        values["$kind._raw"] = rawBody
    }

    @Synchronized
    fun onUnknown(type: Int, body: ByteArray) {
        raw.addLast("CMD 0x%08X (%d Б): %s".format(type, body.size, CarDataParser.raw(body)))
        while (raw.size > 30) raw.removeFirst()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        session = session,
        status = status,
        supported = supported.toList(),
        requested = requested.toList(),
        started = started.toList(),
        counts = LinkedHashMap(counts),
        values = LinkedHashMap(values),
        raw = raw.toList(),
        requestSeq = requestSeq,
        stopSeq = stopSeq,
    )

    @Synchronized
    fun clear() {
        session = null
        status = "Сессия не запущена"
        supported = emptyList()
        requested = emptyList()
        started.clear()
        counts.clear()
        values.clear()
        raw.clear()
    }
}
