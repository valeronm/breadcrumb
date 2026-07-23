package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import java.io.Reader
import java.util.zip.GZIPInputStream

/**
 * Reads a [BackupExporter] file back — the restore half of backup/restore. Streams the JSON
 * (one track's points in memory at a time, like the writer), maps point arrays by the file's own
 * `pointFields` header rather than by position, so a future export that appends fields still
 * restores, and hands rows to the caller for insertion. Pure and stream-based; the Room insertion
 * lives in the repositories. Restore is meant for an empty app (the UI only offers it there) —
 * nothing here merges or deduplicates.
 */
object BackupImporter {

    class Summary(val tracks: Int, val points: Int, val places: Int, val events: Int)

    /** Tracks per insert transaction: one commit (and one observed-query wake) per batch, not per track. */
    private const val INSERT_BATCH = 50

    /** Inflater buffer for the SAF stream — see [importFrom]. */
    private const val STREAM_BUFFER = 64 * 1024

    /**
     * Reads the backup at [uri] and inserts everything through the repositories, fresh ids
     * throughout. Returns the counts, or null if the stream couldn't be opened. Throws on a file
     * that isn't ours or is from a newer format version.
     */
    suspend fun importFrom(
        context: Context,
        repositories: BackupRepositories,
        uri: Uri,
        onProgress: (tracksDone: Int, tracksTotal: Int?) -> Unit,
    ): Summary? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        // The large inflater buffer keeps reads off the SAF stream from degrading into the
        // default 512-byte chunks — each one a Binder round-trip to the documents provider.
        return GZIPInputStream(input, STREAM_BUFFER).bufferedReader().use { reader ->
            restore(reader, repositories, onProgress)
        }
    }

    /**
     * The whole restore over an already-gunzipped document: parse, batch-insert through the
     * repositories, count. Separate from [importFrom] only so the round-trip test can drive the
     * production path without a content Uri.
     */
    internal suspend fun restore(
        reader: Reader,
        repositories: BackupRepositories,
        onProgress: (tracksDone: Int, tracksTotal: Int?) -> Unit = { _, _ -> },
    ): Summary {
        var tracks = 0
        var points = 0
        var places = 0
        var events = 0
        var total: Int? = null
        val batch = mutableListOf<Pair<Track, List<TrackPoint>>>()
        suspend fun flush() {
            if (batch.isEmpty()) return
            repositories.tracks.insertBackupTracks(batch)
            tracks += batch.size
            points += batch.sumOf { it.second.size }
            batch.clear()
            onProgress(tracks, total)
        }
        parse(
            reader,
            onTrack = { track, trackPoints, tracksTotal ->
                total = tracksTotal
                batch += track to trackPoints
                // Parsed counts as progress too — the first flush is 50 tracks in, and a count
                // stuck at 0 until then reads as a hang.
                onProgress(tracks + batch.size, total)
                if (batch.size >= INSERT_BATCH) flush()
            },
            onPlaces = {
                repositories.places.restorePlaces(it)
                places = it.size
            },
            onLiveness = {
                repositories.liveness.restoreEvents(it)
                events = it.size
            },
        )
        flush()
        return Summary(tracks, points, places, events)
    }

    /**
     * Streams the export document (already gunzipped) in [reader]: each track (with all its
     * points) goes to [onTrack] as it's read, places and liveness as whole lists — they're small.
     * Track and point ids in the callbacks are the file's; insertion re-keys them.
     */
    internal suspend fun parse(
        reader: Reader,
        onTrack: suspend (Track, List<TrackPoint>, tracksTotal: Int?) -> Unit,
        onPlaces: suspend (List<Place>) -> Unit,
        onLiveness: suspend (List<LivenessEvent>) -> Unit,
    ) {
        val json = JsonPullReader(reader)
        var formatSeen = false
        var trackCount: Int? = null
        var fields: PointFields? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "format" -> {
                    require(json.nextString() == BackupExporter.FORMAT) { "not a Breadcrumb export" }
                    formatSeen = true
                }
                "version" -> {
                    val version = json.nextNumber().toInt()
                    require(version in 1..BackupExporter.VERSION) {
                        "export version $version needs a newer app"
                    }
                }
                "trackCount" -> trackCount = json.nextNumber().toInt()
                "pointFields" -> {
                    val names = mutableListOf<String>()
                    json.beginArray()
                    while (json.hasNext()) names.add(json.nextString())
                    json.endArray()
                    fields = PointFields(names)
                }
                "tracks" -> {
                    require(formatSeen) { "not a Breadcrumb export" }
                    val index = requireNotNull(fields) { "tracks before pointFields" }
                    json.beginArray()
                    while (json.hasNext()) {
                        val (track, points) = readTrack(json, index)
                        onTrack(track, points, trackCount)
                    }
                    json.endArray()
                }
                "places" -> {
                    val places = mutableListOf<Place>()
                    json.beginArray()
                    while (json.hasNext()) places.add(readPlace(json))
                    json.endArray()
                    onPlaces(places)
                }
                "liveness" -> {
                    val eventList = mutableListOf<LivenessEvent>()
                    json.beginArray()
                    while (json.hasNext()) eventList.add(readLiveness(json))
                    json.endArray()
                    onLiveness(eventList)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        json.expectEnd()
        require(formatSeen) { "not a Breadcrumb export" }
    }

    /** The file's `pointFields` header resolved to positions once — not a map lookup per point. */
    private class PointFields(private val names: List<String>) {
        private fun at(name: String) = names.indexOf(name)
        val timestamp = at("timestamp")
        val lat = at("lat")
        val lon = at("lon")
        val alt = at("alt")
        val accuracy = at("accuracy")
        val speed = at("speed")
        val bearing = at("bearing")
        val verticalAccuracy = at("verticalAccuracy")
        val speedAccuracy = at("speedAccuracy")
        val bearingAccuracy = at("bearingAccuracy")
        val satellitesInFix = at("satellitesInFix")
        val cn0 = at("cn0")
        val ignored = at("ignored")
        val ignoreReason = at("ignoreReason")
        val segmentStart = at("segmentStart")
    }

    private fun readTrack(
        json: JsonPullReader,
        fields: PointFields,
    ): Pair<Track, List<TrackPoint>> {
        var id = 0L
        var activityType: String? = null
        var startedAt = 0L
        var endedAt: Long? = null
        var distanceMeters = 0.0
        var pointCount = 0
        var ignoredCount = 0
        var startLat: Double? = null
        var startLon: Double? = null
        var endLat: Double? = null
        var endLon: Double? = null
        val points = mutableListOf<TrackPoint>()
        val values = mutableListOf<Any?>() // one point's cells, reused across the whole track
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id" -> id = json.nextNumber().toLong()
                "activityType" -> activityType = json.nextString()
                "startedAt" -> startedAt = json.nextNumber().toLong()
                "endedAt" -> endedAt = json.nextNumberOrNull()?.toLong()
                "distanceMeters" -> distanceMeters = json.nextNumber().toDouble()
                "pointCount" -> pointCount = json.nextNumber().toInt()
                "ignoredCount" -> ignoredCount = json.nextNumber().toInt()
                "startLat" -> startLat = json.nextNumberOrNull()?.toDouble()
                "startLon" -> startLon = json.nextNumberOrNull()?.toDouble()
                "endLat" -> endLat = json.nextNumberOrNull()?.toDouble()
                "endLon" -> endLon = json.nextNumberOrNull()?.toDouble()
                "points" -> {
                    json.beginArray()
                    while (json.hasNext()) points.add(readPoint(json, fields, id, values))
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        val track = Track(
            id = id,
            activityType = requireNotNull(activityType) { "track without activityType" },
            startedAt = startedAt,
            endedAt = endedAt,
            distanceMeters = distanceMeters,
            pointCount = pointCount,
            ignoredCount = ignoredCount,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
        )
        return track to points
    }

    private fun readPoint(
        json: JsonPullReader,
        fields: PointFields,
        trackId: Long,
        values: MutableList<Any?>,
    ): TrackPoint {
        values.clear()
        json.beginArray()
        while (json.hasNext()) values.add(json.nextPrimitive())
        json.endArray()

        fun raw(at: Int): Any? = values.getOrNull(at)
        fun num(at: Int): Number? = raw(at) as Number?
        fun flag(at: Int): Boolean = (num(at)?.toInt() ?: 0) != 0
        return TrackPoint(
            trackId = trackId,
            latitude = requireNotNull(num(fields.lat)) { "point without lat" }.toDouble(),
            longitude = requireNotNull(num(fields.lon)) { "point without lon" }.toDouble(),
            altitude = num(fields.alt)?.toDouble(),
            accuracy = num(fields.accuracy)?.toFloat(),
            speed = num(fields.speed)?.toFloat(),
            bearing = num(fields.bearing)?.toFloat(),
            timestamp = requireNotNull(num(fields.timestamp)) { "point without timestamp" }.toLong(),
            verticalAccuracy = num(fields.verticalAccuracy)?.toFloat(),
            speedAccuracy = num(fields.speedAccuracy)?.toFloat(),
            bearingAccuracy = num(fields.bearingAccuracy)?.toFloat(),
            satellitesInFix = num(fields.satellitesInFix)?.toInt(),
            cn0 = num(fields.cn0)?.toFloat(),
            ignored = flag(fields.ignored),
            ignoreReason = raw(fields.ignoreReason) as String?,
            segmentStart = flag(fields.segmentStart),
        )
    }

    private fun readPlace(json: JsonPullReader): Place {
        var id = 0L
        var label = ""
        var lat = 0.0
        var lon = 0.0
        var createdAt = 0L
        var radiusM = Place.DEFAULT_RADIUS_M
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id" -> id = json.nextNumber().toLong()
                "label" -> label = json.nextString()
                "lat" -> lat = json.nextNumber().toDouble()
                "lon" -> lon = json.nextNumber().toDouble()
                "createdAt" -> createdAt = json.nextNumber().toLong()
                "radiusM" -> radiusM = json.nextNumber().toDouble()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Place(id = id, label = label, lat = lat, lon = lon, createdAt = createdAt, radiusM = radiusM)
    }

    private fun readLiveness(json: JsonPullReader): LivenessEvent {
        var type = ""
        var at = 0L
        var until: Long? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> type = json.nextString()
                "at" -> at = json.nextNumber().toLong()
                "until" -> until = json.nextNumberOrNull()?.toLong()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return LivenessEvent(type = type, at = at, until = until)
    }
}

/**
 * A strict, minimal streaming JSON pull-reader — [android.util.JsonReader]'s shape, but pure
 * Kotlin so the import parses (and tests) on any JVM. Numbers come back as Long when integral,
 * Double otherwise. Buffers its own block of input and reuses one token buffer: at millions of
 * points the per-char `Reader.read()` calls and per-token builders would dominate the restore.
 */
internal class JsonPullReader(private val reader: Reader) {

    private val buf = CharArray(8 * 1024)
    private var len = 0
    private var pos = 0
    private var eof = false
    private val token = StringBuilder()

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    /** True while the current object/array has another element; consumes the separating comma. */
    fun hasNext(): Boolean {
        when (peekChar()) {
            '}', ']' -> return false
            ',' -> pos++
            else -> {}
        }
        return true
    }

    fun nextName(): String {
        val name = nextString()
        expect(':')
        return name
    }

    fun nextString(): String {
        check(peekChar() == '"') { "expected string" }
        pos++
        token.setLength(0)
        while (true) {
            when (val c = advance()) {
                '"' -> return token.toString()
                '\\' -> when (val e = advance()) {
                    '"', '\\', '/' -> token.append(e)
                    'n' -> token.append('\n')
                    'r' -> token.append('\r')
                    't' -> token.append('\t')
                    'b' -> token.append('\b')
                    'f' -> token.append('\u000C')
                    'u' -> {
                        val hex = String(CharArray(4) { advance() })
                        token.append(hex.toInt(16).toChar())
                    }
                    else -> error("bad escape '\\$e'")
                }
                else -> {
                    check(c >= ' ') { "raw control char in string" }
                    token.append(c)
                }
            }
        }
    }

    fun nextNumber(): Number = checkNotNull(nextNumberOrNull()) { "expected number, got null" }

    fun nextNumberOrNull(): Number? = when (val v = nextPrimitive()) {
        null -> null
        is Number -> v
        else -> error("expected number, got $v")
    }

    /** A scalar: String, Long, Double, Boolean, or null. */
    fun nextPrimitive(): Any? = when (peekChar()) {
        '"' -> nextString()
        't' -> literal("true", true)
        'f' -> literal("false", false)
        'n' -> literal("null", null)
        else -> number()
    }

    /** Skips one whole value of any shape — an unknown key's payload. */
    fun skipValue() {
        when (peekChar()) {
            '{' -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            '[' -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            else -> nextPrimitive()
        }
    }

    /** The next significant character, whitespace skipped, without consuming it. */
    fun peekChar(): Char {
        skipWs()
        check(fill()) { "unexpected end of input" }
        return buf[pos]
    }

    /** Asserts the input is exhausted — a parsed document may not trail extra content. */
    fun expectEnd() {
        skipWs()
        check(!fill()) { "trailing content after document" }
    }

    private fun number(): Number {
        token.setLength(0)
        while (fill() && buf[pos] in "0123456789+-.eE") token.append(buf[pos++])
        val text = token.toString()
        check(text.isNotEmpty()) { "expected value" }
        return if (text.none { it in ".eE" }) text.toLong() else text.toDouble()
    }

    private fun <T> literal(text: String, value: T): T {
        for (c in text) check(advance() == c) { "bad literal" }
        return value
    }

    private fun expect(c: Char) {
        check(peekChar() == c) { "expected '$c', got '${buf[pos]}'" }
        pos++
    }

    private fun advance(): Char {
        check(fill()) { "unexpected end of input" }
        return buf[pos++]
    }

    /** Ensures at least one buffered char; false at end of input. */
    private fun fill(): Boolean {
        if (pos < len) return true
        if (eof) return false
        len = reader.read(buf, 0, buf.size)
        pos = 0
        if (len <= 0) {
            eof = true
            len = 0
            return false
        }
        return true
    }

    private fun skipWs() {
        while (fill() && buf[pos].isWhitespace()) pos++
    }
}
