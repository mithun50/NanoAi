package com.nanoai.llm.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AppLogger - Simple in-app logging utility with UI display support.
 */
object AppLogger {

    private const val MAX_LOGS = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val timeFormatted: String
            get() = dateFormat.format(Date(timestamp))

        val levelChar: Char
            get() = when (level) {
                Level.DEBUG -> 'D'
                Level.INFO -> 'I'
                Level.WARN -> 'W'
                Level.ERROR -> 'E'
            }
    }

    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        logQueue.add(entry)

        // Trim old logs
        while (logQueue.size > MAX_LOGS) {
            logQueue.poll()
        }

        // Update flow
        _logs.value = logQueue.toList()
    }

    fun clear() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    fun getLogsAsText(): String {
        return logQueue.joinToString("\n") { entry ->
            val errorInfo = entry.throwable?.let { "\n  ${it.stackTraceToString()}" } ?: ""
            "${entry.timeFormatted} ${entry.levelChar}/${entry.tag}: ${entry.message}$errorInfo"
        }
    }
}
