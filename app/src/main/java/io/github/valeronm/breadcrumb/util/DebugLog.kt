package io.github.valeronm.breadcrumb.util

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

    // Bumped on every add/clear; the buffer is only copied into a list inside the collector's map,
    // so the (up to 1000-entry) snapshot cost is paid per UI collection, not per logged line.
    private val version = MutableStateFlow(0)
    val entries: Flow<List<Entry>> = version.map { snapshot() }

    @Synchronized
    private fun snapshot(): List<Entry> = buffer.toList()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(level: Char, tag: String, message: String, tr: Throwable?) {
        when (level) {
            'E' -> Log.e(tag, message, tr)
            'W' -> Log.w(tag, message, tr)
            else -> Log.i(tag, message, tr)
        }
        // The buffer line carries only the throwable's one-line form; the full stack goes to logcat.
        val line = if (tr == null) message else "$message: $tr"
        buffer.addLast(Entry(System.currentTimeMillis(), level, line))
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        version.value++
    }

    fun i(tag: String, message: String) = add('I', tag, message, null)
    fun w(tag: String, message: String) = add('W', tag, message, null)
    fun e(tag: String, message: String, tr: Throwable? = null) = add('E', tag, message, tr)

    @Synchronized
    fun clear() {
        buffer.clear()
        version.value++
    }

    /** Formats an entry timestamp for display. Synchronized because [SimpleDateFormat] isn't reentrant. */
    @Synchronized
    fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

    /** Whole buffer as plain text (with timestamps), for the Logs page's Share action. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") {
        "${formatTime(it.timeMillis)} ${it.level} ${it.message}"
    }
}
