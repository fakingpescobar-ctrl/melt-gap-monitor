package com.meltgap.monitor.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One diagnostic record. */
data class LogEntry(
    val ts: Long,
    val level: DiagLog.Level,
    val tag: String,
    val msg: String
) {
    fun time(): String = formatTime(ts)

    fun line(): String {
        val mark = when (level) {
            DiagLog.Level.DEBUG -> "D"
            DiagLog.Level.INFO -> "I"
            DiagLog.Level.WARN -> "W"
            DiagLog.Level.ERROR -> "E"
        }
        return "${time()} $mark $tag: $msg"
    }
}

private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

private fun formatTime(ts: Long): String = TIME_FORMAT.get()!!.format(Date(ts))

/**
 * In-app diagnostics with three sinks:
 *  1. logcat            — when the phone is connected to a PC;
 *  2. in-memory ring     — drawn by OverlayView, works with zero tooling;
 *  3. file               — filesDir/logs/meltgap.log, rotated at 512 KB, survives restarts.
 *
 * Volume is deliberately low: state transitions plus one metrics line per second.
 */
object DiagLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private const val CAPACITY = 400
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val LOGCAT_TAG = "MeltGap"

    private val buffer = ArrayDeque<LogEntry>()
    private val lock = Any()
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val dir = File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        val file = File(dir, "meltgap.log")
        logFile = file
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val old = File(dir, "meltgap.1.log")
                old.delete()
                file.renameTo(old)
            }
        }
        i("DIAG", "log file: ${file.absolutePath}")
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        log(Level.ERROR, tag, "$msg: ${t.javaClass.simpleName}: ${t.message}")

    fun log(level: Level, tag: String, msg: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)

        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }

        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(LOGCAT_TAG, "[$tag] $msg")
                Level.INFO -> Log.i(LOGCAT_TAG, "[$tag] $msg")
                Level.WARN -> Log.w(LOGCAT_TAG, "[$tag] $msg")
                Level.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $msg")
            }
        }

        val file = logFile
        if (file != null) {
            fileExecutor.execute {
                runCatching { file.appendText(entry.line() + "\n") }
            }
        }
    }

    fun recent(limit: Int): List<LogEntry> = synchronized(lock) {
        if (buffer.isEmpty()) return emptyList()
        val from = (buffer.size - limit).coerceAtLeast(0)
        buffer.drop(from)
    }

    fun last(limit: Int = 15): String = recent(limit).joinToString("\n") { it.line() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        i("DIAG", "log buffer cleared")
    }

    fun logFilePath(): String? = logFile?.absolutePath
}
