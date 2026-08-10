package io.github.valeronm.breadcrumb.util

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide in-memory ring buffer of diagnostic log lines, mirrored to logcat and — once
 * [attach]ed — appended to a [RollingLogFile]. The ring is what the in-app Logs page shows live;
 * the file is what survives the process (a reinstall, a crash, Android reclaiming the recorder),
 * which is where a field session's evidence otherwise goes to die: logcat's ring rotates within
 * hours and the buffer dies with the process. The Logs page's Share reads the file, so an exported
 * log covers days and however many installs happened inside them.
 */
object DebugLog {

    data class Entry(val timeMillis: Long, val level: Char, val message: String)

    private const val MAX_ENTRIES = 1000
    private val buffer = ArrayDeque<Entry>()

    // The one attach flag; every file mutation and the snapshot ride [writerExecutor], which is
    // what lets RollingLogFile stay lock-free on a declared single-thread contract. Eager but
    // free: the executor spawns no thread until its first task, which only [attach] can cause.
    private var files: RollingLogFile? = null
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DebugLog-file").apply { isDaemon = true }
    }

    // The file line carries the date where the on-screen ring does not: the ring spans hours, the
    // file spans weeks, and "23:38" without a day is ambiguous the morning after.
    private val fileTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Start persisting to [dir], once per process (App.onCreate). Lines logged before this sit in
     * the ring and are written first — a fresh process's ring holds only this life's lines, so
     * nothing duplicates. All file I/O rides one background thread; logging itself never blocks on
     * the disk.
     */
    @Synchronized
    fun attach(dir: File) {
        if (files != null) return
        val log = RollingLogFile(dir)
        files = log
        val backlog = buffer.map(::fileLine)
        writerExecutor.execute { backlog.forEach(log::append) }
    }

    /**
     * Write the whole persisted history into [target], oldest line first, draining any pending
     * writes ahead of it. Blocks for the copy — call from a background dispatcher, after [attach]:
     * an unattached snapshot has nothing real to say and is a caller's wiring error, not a case.
     */
    fun snapshotTo(target: File) {
        val log = synchronized(this) { checkNotNull(files) { "DebugLog.attach must run first" } }
        writerExecutor.submit { log.snapshotTo(target) }.get()
    }

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
        val entry = Entry(System.currentTimeMillis(), level, line)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        version.value++
        // Formatted here (under the lock, where the format is safe) and captured by value, so the
        // writer thread touches no shared state but the file.
        val log = files ?: return
        val fileLine = fileLine(entry)
        writerExecutor.execute { log.append(fileLine) }
    }

    /** One persisted line, in the exported format. Callers hold the object lock ([fileTimeFormat]). */
    private fun fileLine(e: Entry) = "${fileTimeFormat.format(Date(e.timeMillis))} ${e.level} ${e.message}"

    fun i(tag: String, message: String) = add('I', tag, message, null)
    fun w(tag: String, message: String) = add('W', tag, message, null)
    fun e(tag: String, message: String, tr: Throwable? = null) = add('E', tag, message, tr)

    @Synchronized
    fun clear() {
        buffer.clear()
        version.value++
        val log = files ?: return
        writerExecutor.execute { log.clear() }
    }

    /** Formats an entry timestamp for display. Synchronized because [SimpleDateFormat] isn't reentrant. */
    @Synchronized
    fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
}
