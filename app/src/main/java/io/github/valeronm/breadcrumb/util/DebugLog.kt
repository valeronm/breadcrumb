package io.github.valeronm.breadcrumb.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide in-memory ring buffer of diagnostic log lines, mirrored to logcat. Lets the debug
 * build show recent activity-recognition / recorder events on an in-app Logs page — handy for field
 * testing where adb isn't attached. The foreground service keeps the process (and this buffer) alive
 * for the whole armed session.
 */
object DebugLog {

    data class Entry(val timeMillis: Long, val level: Char, val message: String)

    private const val MAX_ENTRIES = 1000
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(level: Char, tag: String, message: String) {
        when (level) {
            'E' -> Log.e(tag, message)
            'W' -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
        buffer.addLast(Entry(System.currentTimeMillis(), level, message))
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    fun i(tag: String, message: String) = add('I', tag, message)
    fun w(tag: String, message: String) = add('W', tag, message)
    fun e(tag: String, message: String) = add('E', tag, message)

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    /** Whole buffer as plain text (with timestamps), for the Logs page's Share action. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") {
        "${timeFormat.format(Date(it.timeMillis))} ${it.level} ${it.message}"
    }
}
