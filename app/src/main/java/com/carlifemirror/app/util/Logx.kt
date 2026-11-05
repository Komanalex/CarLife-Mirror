package com.carlifemirror.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Журнал приложения: кольцо в памяти для экрана «Подробности» и файл сразу
 * в общедоступных Загрузках (CarLifeMirror). Внутренние копии не создаются.
 *
 * Файл пишется через MediaStore, поэтому не требует разрешения на доступ
 * ко всему хранилищу. При старте удаляются журналы старше суток.
 *
 * В журнал не попадают координаты касаний, тексты уведомлений и идентификаторы
 * устройства. Пишем только технические события CarLife и видео.
 */
object Logx {
    private const val TAG = "CarLifeMirror"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val LOG_MAX_AGE_SECONDS = 24L * 60L * 60L
    private const val LOG_CLOSE_IDLE_MS = 1_500L
    private const val LOG_CLOSE_MAX_LINES = 16

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val closeHandler = Handler(Looper.getMainLooper())
    private val closeAfterIdle = Runnable {
        synchronized(lock) { closeStreamLocked() }
    }

    private var appContext: Context? = null
    private var out: OutputStream? = null
    private var downloadUri: Uri? = null
    private var displayPath: String? = null
    private var fileBytes = 0L
    private var linesSinceClose = 0
    private var fileLimitWarned = false

    /** Вызывается один раз из Application; повторные вызовы игнорируются. */
    fun init(context: Context) {
        synchronized(lock) {
            if (downloadUri != null) return
            val app = context.applicationContext
            appContext = app
            cleanupLegacyLogs(app)
            cleanupDownloads(app)
            createDownloadLog(app)
        }
    }

    private fun createDownloadLog(app: Context) {
        val resolver = app.contentResolver
        val name = "carlife-mirror-${fileStamp.format(Date())}.log"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/CarLifeMirror"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/x-log")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать журнал в Загрузках", e)
            null
        } ?: return
        val actualName = try {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: name
        val stream = try {
            resolver.openOutputStream(uri, "w")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось открыть журнал в Загрузках", e)
            null
        }
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            return
        }
        try {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Журнал: не удалось сделать файл видимым: ${e.message}")
        }
        out = stream
        downloadUri = uri
        displayPath = "Загрузки/CarLifeMirror/$actualName"
        fileBytes = 0L
        linesSinceClose = 0
        appendLocked("I", "=== CarLife Mirror ${versionName(app)} ===")
        appendLocked("I", "Запуск: ${time.format(Date())}")
        appendLocked(
            "I",
            "Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
        )
        appendLocked("I", "Журнал: $displayPath")
    }

    /** Убирает старые внутренние журналы, оставшиеся от версий до 1.1.0. */
    private fun cleanupLegacyLogs(app: Context) {
        val dirs = listOfNotNull(app.getExternalFilesDir("logs"), File(app.filesDir, "logs"))
        dirs.forEach { dir ->
            dir.listFiles { f ->
                f.isFile && f.name.startsWith("carlife-mirror-") && f.name.endsWith(".log")
            }?.forEach { runCatching { it.delete() } }
            runCatching {
                if (dir.listFiles().isNullOrEmpty()) dir.delete()
            }
        }
    }

    /**
     * Удаляет старые копии из Downloads/CarLifeMirror. MediaStore отдаёт
     * DATE_ADDED/DATE_MODIFIED в секундах, но некоторые прошивки — в мс.
     */
    private fun cleanupDownloads(context: Context) {
        val resolver = context.contentResolver
        val cutoff = System.currentTimeMillis() / 1000L - LOG_MAX_AGE_SECONDS
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(
            "%${Environment.DIRECTORY_DOWNLOADS}/CarLifeMirror%",
            "carlife-mirror-%",
        )
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val added = c.getLong(1)
                    val modified = c.getLong(2)
                    val raw = if (added > 0) added else modified
                    if (raw <= 0) continue
                    val seconds = if (raw > 10_000_000_000L) raw / 1000L else raw
                    if (seconds < cutoff) {
                        runCatching {
                            resolver.delete(
                                ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    id,
                                ),
                                null,
                                null,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось очистить старые журналы: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun appendLocked(level: String, msg: String) {
        val line = "${time.format(Date())} $level $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()

        val stream = out ?: reopenLocked() ?: return
        if (fileBytes >= MAX_FILE_BYTES) {
            if (!fileLimitWarned) {
                fileLimitWarned = true
                try {
                    stream.write(
                        (
                            "${time.format(Date())} W Журнал достиг $MAX_FILE_BYTES байт; " +
                                "файл больше не растёт\n"
                            ).toByteArray(),
                    )
                    stream.flush()
                } catch (_: Exception) {
                }
            }
            return
        }
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.flush()
            fileBytes += bytes.size
            linesSinceClose++
            closeHandler.removeCallbacks(closeAfterIdle)
            if (linesSinceClose >= LOG_CLOSE_MAX_LINES) {
                // Закрываем файл, чтобы MediaStore обновил размер и дату.
                // Иначе Windows/MTP при копировании видит только начало
                // журнала, хотя на телефоне файл уже большой.
                closeStreamLocked()
            } else {
                closeHandler.postDelayed(closeAfterIdle, LOG_CLOSE_IDLE_MS)
            }
        } catch (e: Exception) {
            runCatching { stream.close() }
            out = null
            Log.e(TAG, "Запись журнала остановлена", e)
        }
    }

    private fun reopenLocked(): OutputStream? {
        val app = appContext ?: return null
        val uri = downloadUri ?: return null
        return try {
            app.contentResolver.openOutputStream(uri, "wa")?.also {
                out = it
                Log.i(TAG, "Журнал открыт для дозаписи")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось открыть журнал для дозаписи", e)
            null
        }
    }

    private fun closeStreamLocked() {
        closeHandler.removeCallbacks(closeAfterIdle)
        val stream = out ?: return
        out = null
        try {
            stream.flush()
            stream.close()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось закрыть журнал: ${e.message}")
        }
        linesSinceClose = 0
        finalizeMediaStoreLocked()
    }

    private fun finalizeMediaStoreLocked() {
        val app = appContext ?: return
        val uri = downloadUri ?: return
        try {
            app.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                },
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обновить дату журнала: ${e.message}")
        }
    }

    private fun add(level: String, msg: String) {
        synchronized(lock) { appendLocked(level, msg) }
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
    }

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)

    fun e(msg: String, t: Throwable? = null) {
        if (t == null) {
            add("E", msg)
            return
        }
        val detail = buildString {
            append(msg)
            append(": ")
            append(t.javaClass.simpleName)
            t.message?.let { append(": ").append(it) }
            append('\n')
            append(stackTrace(t))
        }
        synchronized(lock) { appendLocked("E", detail) }
        Log.e(TAG, msg, t)
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > 64_000) text.take(64_000) + "…" else text
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun currentFilePath(): String = displayPath ?: "не создан"

    /**
     * Журнал уже пишется прямо в Загрузки. Метод оставлен для кнопки
     * «Сохранить журнал» и автосохранения: возвращает актуальный путь.
     */
    fun exportToDownloads(context: Context): String? {
        synchronized(lock) {
            // Закрываем поток, чтобы MediaStore зафиксировал актуальный
            // размер файла перед копированием на компьютер.
            closeStreamLocked()
        }
        cleanupDownloads(context.applicationContext)
        return displayPath
    }
}
