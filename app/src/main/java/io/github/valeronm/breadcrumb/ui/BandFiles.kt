package io.github.valeronm.breadcrumb.ui

import java.io.File
import java.io.Writer
import java.util.concurrent.Executor

/**
 * The files a cased line's zoom bands are handed to MapLibre through, written in [dir] and deleted by [deleter].
 *
 * A band's file stays until the band's next one replaces it ([hold]) or the bands close, since a source whose file is
 * gone when MapLibre reads it never loads.
 */
internal class BandFiles(private val dir: File, private val deleter: Executor) {
    private val held = arrayOfNulls<File>(CASED_ZOOMS.size)
    private val written = HashSet<File>()
    private var closed = false

    /**
     * A new file holding what [write] wrote, or null once the bands are closed. A write that fails deletes its file and
     * throws.
     */
    suspend fun write(write: suspend (Writer) -> Unit): File? {
        val file = File.createTempFile("band", ".geojson", clearedOnce(dir))
        var done = false
        try {
            file.bufferedWriter().use { write(it) }
            done = true
        } finally {
            if (!done) file.delete()
        }
        synchronized(this) {
            if (!closed) return file.also { written += it }
        }
        file.delete()
        return null
    }

    /** [band] reads [file] from now on, so the file it read before is deleted. */
    fun hold(band: Int, file: File) {
        held[band]?.let(::delete)
        held[band] = file
    }

    fun close() {
        val all = synchronized(this) {
            closed = true
            written.toList().also { written.clear() }
        }
        deleter.execute { all.forEach(File::delete) }
    }

    private fun delete(file: File) {
        synchronized(this) { written -= file }
        deleter.execute { file.delete() }
    }
}

private val clearedDirs = HashSet<File>()

/** [dir], emptied the first time this process asks for it, since a process killed with bands open leaves them there. */
private fun clearedOnce(dir: File): File = synchronized(clearedDirs) {
    if (clearedDirs.add(dir)) dir.deleteRecursively()
    dir.mkdirs()
    dir
}
