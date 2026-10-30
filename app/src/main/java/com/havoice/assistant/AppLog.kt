package com.havoice.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's activity log. Lines are written to a file on the phone and queued until
 * [LogSync] copies them into Home Assistant's system log.
 */
object AppLog {
    enum class Level(val haLevel: String) { INFO("info"), WARNING("warning"), ERROR("error") }

    data class Entry(val time: Long, val level: Level, val message: String)

    private const val TAG = "VoiceCustom"
    private const val MAX_LOG_BYTES = 256 * 1024
    private const val MAX_PENDING = 500

    private lateinit var logFile: File
    private lateinit var pendingFile: File
    private val pending = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        logFile = File(context.filesDir, "log.txt")
        pendingFile = File(context.filesDir, "log_pending.txt")
        pending.clear()
        if (pendingFile.exists()) {
            pendingFile.readLines().mapNotNullTo(pending) { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNullTo null
                val level = Level.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNullTo null
                parts[0].toLongOrNull()?.let { Entry(it, level, parts[2]) }
            }
        }
    }

    fun i(message: String) = add(Level.INFO, message)
    fun w(message: String) = add(Level.WARNING, message)
    fun e(message: String, error: Throwable? = null) =
        add(Level.ERROR, if (error == null) message else "$message: ${error.stackTraceToString()}")

    @Synchronized
    fun timestamp(entry: Entry): String = timeFormat.format(Date(entry.time))

    private fun format(entry: Entry): String = "${timestamp(entry)} ${entry.level.name.first()} ${entry.message}"

    /** The newest lines of the on-phone log, oldest first. */
    @Synchronized
    fun recent(count: Int): List<String> =
        if (::logFile.isInitialized && logFile.exists()) logFile.readLines().takeLast(count) else emptyList()

    @Synchronized
    fun pendingCount(): Int = pending.size

    /** Oldest unsent entries, left queued until [markSent] confirms them. */
    @Synchronized
    fun peekPending(max: Int): List<Entry> = pending.take(max)

    @Synchronized
    fun markSent(count: Int) {
        repeat(minOf(count, pending.size)) { pending.removeFirst() }
        savePending()
    }

    @Synchronized
    private fun add(level: Level, message: String) {
        when (level) {
            Level.INFO -> Log.i(TAG, message)
            Level.WARNING -> Log.w(TAG, message)
            Level.ERROR -> Log.e(TAG, message)
        }
        if (!::logFile.isInitialized) return
        // One line per entry keeps the files simple to read back.
        val entry = Entry(System.currentTimeMillis(), level, message.replace('\n', '⏎').replace('\t', ' '))
        if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
            logFile.writeText(logFile.readLines().takeLast(500).joinToString("\n", postfix = "\n"))
        }
        logFile.appendText(format(entry) + "\n")
        pending.addLast(entry)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        savePending()
    }

    private fun savePending() {
        pendingFile.writeText(pending.joinToString("") { "${it.time}\t${it.level.name}\t${it.message}\n" })
    }
}
