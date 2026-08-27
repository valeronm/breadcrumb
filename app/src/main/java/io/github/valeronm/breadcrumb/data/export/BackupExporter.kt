package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.util.DebugLog
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.Writer
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

private const val TAG = "Breadcrumb"

/**
 * Writes the whole recorded history as one gzipped JSON document — the web companion's data
 * source. Unlike GPX this keeps everything the viewer can use: ignored points with their reasons,
 * fix-quality metadata, and named places with their categories; discarded tracks and a
 * still-open recording are excluded, matching the rest of the app. Points are per-point arrays in [POINT_FIELDS] order (echoed in the document header
 * as `pointFields`), not objects — at millions of points the field names would dominate the file
 * and the parse — and tracks stream one at a time, so memory stays at one track's points.
 *
 * **A coordinate is written on a grid, wherever it appears** — a fix, a track's endpoints, a place's
 * pin — and so is every quality figure a fix carries, to the grids [COORD_DECIMALS] and its
 * siblings state. What the app computed or the user set (a distance, a capture radius, the counts
 * and the clocks) is written as it is held: those are answers, not measurements, and rounding one
 * would only disagree with the row it came from.
 */
object BackupExporter {

    const val MIME_TYPE = "application/gzip"
    const val FORMAT = "breadcrumb-export"

    /** Unchanged by *added* fields: the reader skips keys it doesn't know and defaults the ones a
     *  file predates, so both directions stay readable. A change in a value's *precision* is
     *  neither — the schema is unmoved and every reader takes a JSON number — so nothing in a file
     *  says which grid wrote it, and a reader must not assume one. Bump only on a breaking change:
     *  the importer refuses anything newer than it understands. */
    const val VERSION = 1

    /** Field order of each per-point array in `tracks[].points`. Append-only across versions. */
    internal val POINT_FIELDS = listOf(
        "timestamp", "lat", "lon", "alt", "accuracy", "speed", "bearing",
        "verticalAccuracy", "speedAccuracy", "bearingAccuracy", "satellitesInFix", "cn0",
        "ignored", "ignoreReason", "segmentStart",
    )

    /**
     * The grids every number in the document is rounded to — a coordinate to ~1 cm
     * ([COORD_DECIMALS]), a centimetre of accuracy and of speed per second ([FINE_DECIMALS]), a
     * tenth of a metre, of a degree and of a dB-Hz ([COARSE_DECIMALS]).
     *
     * The digits below these are the platform's arithmetic rather than anything an instrument
     * measured, and they are charged for twice — once as text, and again in the deflate, noise
     * being the one thing that will not compress. Rounding costs the app nothing it states: a
     * coordinate on this grid moves a track's total distance by centimetres at worst. Which field
     * takes which grid is written once, at the call that writes it.
     */
    private const val COORD_DECIMALS = 7
    private const val FINE_DECIMALS = 2
    private const val COARSE_DECIMALS = 1

    /**
     * Buffer between a SAF stream and the (de)compressor, export and restore alike. Without one the
     * provider sees a Binder round-trip per deflate cycle: the gzip stream's own buffer caps how
     * much a write may carry, and never fills one, so the chunks that reach it stay small.
     */
    internal const val STREAM_BUFFER = 64 * 1024

    /** The quoted form of every reason the domain names, so an ignored fix costs no [str] call.
     *  A code from outside that set still goes the long way rather than being written raw. */
    private val QUOTED_IGNORE_REASONS: Map<String, String> =
        IgnoreReason.entries.associate { it.code to str(it.code) }

    fun fileName(now: Long): String = "breadcrumb-${exportFileStamp(now)}.json.gz"

    /** Everything one backup document contains; points stream per track via [pointsFor]. */
    internal class Content(
        val tracks: List<Track>,
        val pointsFor: suspend (Long) -> List<TrackPoint>,
        val places: List<Place>,
    )

    /**
     * Loads everything and streams it gzipped to [uri] (from the system create-document picker).
     * Returns the number of tracks written, or null if the stream couldn't be opened.
     */
    suspend fun exportTo(
        context: Context,
        repositories: BackupRepositories,
        uri: Uri,
        exportedAt: Long,
        onProgress: (tracksDone: Int, tracksTotal: Int) -> Unit,
    ): Int? {
        val tracks = repositories.tracks.exportTracks()
        val out = context.contentResolver.openOutputStream(uri) ?: return null
        // An export costs two things that move independently — pulling the fixes out of Room, and
        // turning them into compressed bytes — and from outside only their sum is visible. The read
        // is timed on its own so a slow export can say which half was slow. Nanoseconds because a
        // single track's read rounds to nothing on a millisecond clock, and there are thousands.
        var points = 0L
        var readNanos = 0L
        val counted = CountingOutputStream(out)
        // The outer use owns the raw stream so it closes even if the gzip wrapper's constructor
        // (which writes the header) or the export body throws before the inner use takes over.
        counted.use { raw ->
            // Plain writer, not bufferedWriter(): [CellWriter] batches whole cells of its own, and
            // a buffer in front of it would be a second copy of the same characters behind a second
            // lock, taken once per number.
            FastGzipOutputStream(BufferedOutputStream(raw, STREAM_BUFFER)).writer().use { writer ->
                writeJson(
                    writer,
                    exportedAt,
                    Content(
                        tracks = tracks,
                        pointsFor = { id ->
                            val startedAt = System.nanoTime()
                            repositories.tracks.allPointsFor(id).also {
                                readNanos += System.nanoTime() - startedAt
                                points += it.size
                            }
                        },
                        places = repositories.places.allPlaces(),
                    ),
                    onTrackWritten = { done -> onProgress(done, tracks.size) },
                )
            }
        }
        DebugLog.i(
            TAG,
            "backup export: ${tracks.size} tracks, $points points, " +
                "${counted.bytes / 1024} kB, ${readNanos / 1_000_000} ms reading them",
        )
        return tracks.size
    }

    internal suspend fun writeJson(
        out: Writer,
        exportedAt: Long,
        content: Content,
        onTrackWritten: (done: Int) -> Unit = {},
    ) {
        val cells = CellWriter(out)
        cells.text("""{"format":${str(FORMAT)},"version":$VERSION,"exportedAt":$exportedAt""")
        cells.text(""","trackCount":${content.tracks.size}""")
        cells.text(""","pointFields":[${POINT_FIELDS.joinToString(",") { str(it) }}]""")

        cells.text(""","tracks":[""")
        for (i in content.tracks.indices) {
            if (i > 0) cells.char(',')
            val track = content.tracks[i]
            writeTrackHeader(cells, track)
            // Indexed, not withIndex(): the latter allocates an IndexedValue per step, which over a
            // history's points is the largest allocation left in a writer built to make none.
            val points = content.pointsFor(track.id)
            for (j in points.indices) {
                if (j > 0) cells.char(',')
                writePoint(cells, points[j])
            }
            cells.text("]}")
            onTrackWritten(i + 1)
        }
        cells.char(']')

        cells.text(""","places":[""")
        for (i in content.places.indices) {
            if (i > 0) cells.char(',')
            writePlace(cells, content.places[i])
        }
        cells.text("]}")
        cells.flush()
    }

    /**
     * The track object, opened up to and including `"points":[` — the point stream follows. The
     * endpoints are copies of the first and last good fix, so they round exactly as those do;
     * `distanceMeters` stays as it was measured, being what the fixes said before any of this.
     */
    private fun writeTrackHeader(cells: CellWriter, t: Track) {
        cells.text("""{"id":${t.id},"activityType":${str(t.activityType)}""")
        cells.text(""","startedAt":${t.startedAt},"endedAt":${t.endedAt}""")
        cells.text(""","source":${strOrNull(t.source)}""")
        cells.text(""","distanceMeters":${t.distanceMeters}""")
        cells.text(""","pointCount":${t.pointCount},"ignoredCount":${t.ignoredCount}""")
        cells.coordinate(""","startLat":""", t.startLat)
        cells.coordinate(""","startLon":""", t.startLon)
        cells.coordinate(""","endLat":""", t.endLat)
        cells.coordinate(""","endLon":""", t.endLon)
        cells.text(""","points":[""")
    }

    // Written cell by cell, no per-point buildString: at millions of points the intermediate
    // builder+string per point would be the export's dominant allocation. One line per
    // [POINT_FIELDS] entry, in its order — the two lists are checked against each other by eye.
    private fun writePoint(cells: CellWriter, p: TrackPoint) {
        cells.char('[')
        cells.long(p.timestamp)
        cells.char(',')
        cells.decimal(p.latitude, COORD_DECIMALS)
        cells.char(',')
        cells.decimal(p.longitude, COORD_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.altitude, COARSE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.accuracy, FINE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.speed, FINE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.bearing, COARSE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.verticalAccuracy, COARSE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.speedAccuracy, FINE_DECIMALS)
        cells.char(',')
        cells.decimalOrNull(p.bearingAccuracy, COARSE_DECIMALS)
        cells.char(',')
        cells.longOrNull(p.satellitesInFix)
        cells.char(',')
        cells.decimalOrNull(p.cn0, COARSE_DECIMALS)
        cells.char(',')
        cells.char(if (p.ignored) '1' else '0')
        cells.char(',')
        cells.text(quotedIgnoreReason(p.ignoreReason))
        cells.char(',')
        cells.char(if (p.segmentStart) '1' else '0')
        cells.char(']')
    }

    // An untagged place writes no `category` key at all, so a history with no categories exports
    // exactly as it did before the column existed.
    private fun writePlace(cells: CellWriter, p: Place) {
        cells.text("""{"id":${p.id},"label":${str(p.label)}""")
        cells.coordinate(""","lat":""", p.lat)
        cells.coordinate(""","lon":""", p.lon)
        cells.text(""","createdAt":${p.createdAt},"radiusM":${p.radiusM}""")
        p.category?.let { cells.text(""","category":${str(it)}""") }
        cells.char('}')
    }

    /** A `,"name":` key, given whole so no call assembles one, and its value on the coordinate grid. */
    private fun CellWriter.coordinate(keyJson: String, value: Double?) {
        text(keyJson)
        decimalOrNull(value, COORD_DECIMALS)
    }

    private fun quotedIgnoreReason(code: String?): String =
        if (code == null) "null" else QUOTED_IGNORE_REASONS[code] ?: str(code)

    /** A nullable field: the string literal, or the JSON `null` a reader defaults from. */
    private fun strOrNull(s: String?): String = s?.let { str(it) } ?: "null"

    /**
     * Writes JSON cells to [out], allocating nothing per cell. `Double.toString` would allocate a
     * String for each of the dozen numbers of every point — tens of millions per export — and spell
     * out digits no instrument measured. Cells accumulate in [buf] and reach [out] a bufferful at a
     * time, so a number costs no call of its own; the caller owes a final [flush]. One instance per
     * document, and not thread-safe.
     */
    private class CellWriter(private val out: Writer) {
        private val buf = CharArray(BUFFER_CHARS)
        private var end = 0

        fun text(s: String) {
            if (s.length > buf.size - end) flush()
            if (s.length > buf.size) {
                out.write(s)
            } else {
                s.toCharArray(buf, end)
                end += s.length
            }
        }

        fun char(c: Char) {
            if (end == buf.size) flush()
            buf[end++] = c
        }

        fun long(value: Long) {
            reserve()
            if (value < 0) buf[end++] = '-'
            end = writeDigits(abs(value), end)
        }

        fun longOrNull(value: Int?) = if (value == null) text("null") else long(value.toLong())

        fun decimalOrNull(value: Double?, decimals: Int) =
            if (value == null) text("null") else decimal(value, decimals)

        fun decimalOrNull(value: Float?, decimals: Int) =
            if (value == null) text("null") else decimal(value.toDouble(), decimals)

        /**
         * Half-up at [decimals], with trailing zeros of the fraction dropped so the text is the
         * shortest that says the rounded value. A `NaN` scales to zero rather than reaching the
         * document spelled out, that being no JSON number and enough to strand every reader at
         * exactly that point in the file.
         */
        fun decimal(value: Double, decimals: Int) {
            reserve()
            val scale = POWERS_OF_TEN[decimals]
            val scaled = (abs(value) * scale + 0.5).toLong()
            val whole = scaled / scale
            var fraction = scaled - whole * scale
            if (value < 0 && scaled != 0L) buf[end++] = '-'
            end = writeDigits(whole, end)
            if (fraction == 0L) return
            // The fraction is written into a field exactly [decimals] wide, so the slots the digits
            // don't reach are its leading zeros — no count of them is needed, and the trailing ones
            // come off by looking at characters rather than by dividing them away.
            buf[end] = '.'
            val point = end + 1
            var at = point + decimals
            end = at
            while (at > point) {
                buf[--at] = '0' + (fraction % 10).toInt()
                fraction /= 10
            }
            while (buf[end - 1] == '0') end--
        }

        fun flush() {
            if (end > 0) {
                out.write(buf, 0, end)
                end = 0
            }
        }

        /** Makes room for the longest cell, so the writers below need no bounds check of their own. */
        private fun reserve() {
            if (buf.size - end < MAX_CELL_CHARS) flush()
        }

        /** Digits of [value] into [buf] at [at], returning the index just past them. */
        private fun writeDigits(value: Long, at: Int): Int {
            val stop = at + countDigits(value)
            var rest = value
            var index = stop
            while (index > at) {
                buf[--index] = '0' + (rest % 10).toInt()
                rest /= 10
            }
            return stop
        }

        private fun countDigits(value: Long): Int {
            var rest = value
            var digits = 1
            while (rest >= 10) {
                rest /= 10
                digits++
            }
            return digits
        }

        private companion object {
            /** A sign, every digit a Long can hold, the point, and a fraction at [COORD_DECIMALS]. */
            const val MAX_CELL_CHARS = 32
            const val BUFFER_CHARS = 16 * 1024
            val POWERS_OF_TEN = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)
        }
    }

    /** Counts what reached the document, so an export can state its own size rather than asking
     *  the provider to stat a file whose bytes it may still be holding. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var bytes = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytes += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }

    /** Gzip at [Deflater.BEST_SPEED] — the deflate is where an export spends nearly all of its
     *  time, and the rounding above takes more off the file than the weaker level puts back. A
     *  subclass because the deflater it has to be set on is protected. */
    private class FastGzipOutputStream(out: OutputStream) : GZIPOutputStream(out, STREAM_BUFFER) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }

    /** JSON string literal with the mandatory escapes. */
    private fun str(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
