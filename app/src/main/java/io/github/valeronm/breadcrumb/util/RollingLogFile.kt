package io.github.valeronm.breadcrumb.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * Append-only line log persisted as one active file plus one rolled predecessor — the disk half
 * of [DebugLog], whose KDoc carries why a line must outlive the process; here is only how.
 *
 * **Flush, not fsync**: a flushed line sits in the kernel's page cache, which outlives the app
 * however it dies; only power loss can take the tail, and a diagnostic log is not worth a sync per
 * line. **Single-threaded by contract** — every call must come from one thread (DebugLog's writer
 * executor); nothing here locks.
 *
 * Rotation is by size, not date: the volume is event-driven (a no-GNSS burst writes a line a
 * second, a quiet day a handful), so a byte cap is the only bound that means anything. Two files
 * of [maxFileBytes] hold weeks at the observed line rate.
 */
class RollingLogFile(dir: File, private val maxFileBytes: Long = MAX_FILE_BYTES) {

    private val active = File(dir, ACTIVE_NAME)
    private val rolled = File(dir, ROLLED_NAME)
    private var writer: Writer? = null

    fun append(line: String) {
        val out = writer ?: open()
        out.write(line)
        out.write("\n")
        out.flush()
        // The file is the authority: a second count of what the writer's encoding emits would be an
        // agreement nothing checks, and it would have to survive a process restart to mean anything.
        if (active.length() > maxFileBytes) roll()
    }

    /**
     * Write the whole history — rolled first, then active, oldest line first — into [target],
     * replacing whatever it held. The share path's snapshot: one file, the order a reader reads.
     */
    fun snapshotTo(target: File) {
        target.outputStream().use { out ->
            listOf(rolled, active)
                .filter(File::exists)
                .forEach { file -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    /** Drop the history — both files, and the writer with them; the next [append] starts fresh. */
    fun clear() {
        writer?.close()
        writer = null
        active.delete()
        rolled.delete()
    }

    private fun open(): Writer {
        active.parentFile?.mkdirs()
        val out = OutputStreamWriter(FileOutputStream(active, true), Charsets.UTF_8)
        writer = out
        return out
    }

    private fun roll() {
        writer?.close()
        writer = null
        rolled.delete()
        active.renameTo(rolled)
    }

    companion object {
        /**
         * Cap per file, two files on disk. At the observed ~60 bytes a line this is weeks of
         * history, and small enough that the share snapshot stays a text file a chat will take.
         */
        const val MAX_FILE_BYTES = 4_000_000L

        const val ACTIVE_NAME = "recorder.log"
        const val ROLLED_NAME = "recorder.log.1"
    }
}
