package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import java.io.Reader
import java.util.zip.GZIPInputStream

/**
 * Reads a [BackupExporter] file back — the restore half. Streams one track's points at a time (like
 * the writer), mapping point arrays by the file's own `pointFields` header, not position, so an
 * export that appends fields still restores. Pure and stream-based — the Room insertion lives in
 * the repositories. For an empty app (the UI offers it only there): nothing merges or deduplicates.
 */
object BackupImporter {

    class Summary(val tracks: Int, val points: Int, val places: Int)

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
        // The outer use owns the raw stream: the gzip wrapper's constructor eagerly reads and
        // validates the header, so on a wrong-file pick it throws before the inner use exists.
        return input.use { raw ->
            // The large inflater buffer keeps reads off the SAF stream from degrading into the
            // default 512-byte chunks — each one a Binder round-trip to the documents provider.
            GZIPInputStream(raw, STREAM_BUFFER).bufferedReader().use { reader ->
                restore(reader, repositories, onProgress)
            }
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
        )
        flush()
        // Once, at the end: the restored places become the seeds, and the derivation over every
        // track the file carried is one pass rather than a repair per batch — the tracks arrive in
        // the file's order, which a repair around one of them has no way to assume anything about.
        repositories.derivation.reconcile(stale = true)
        return Summary(tracks, points, places)
    }

    /**
     * Streams the export document (already gunzipped) in [reader]: each track (with all its
     * points) goes to [onTrack] as it's read, places as a whole list — it's small. A `liveness`
     * array in an older file falls to the unknown-key skip below. Track and point ids in the
     * callbacks are the file's; insertion re-keys them.
     */
    internal suspend fun parse(
        reader: Reader,
        onTrack: suspend (Track, List<TrackPoint>, tracksTotal: Int?) -> Unit,
        onPlaces: suspend (List<Place>) -> Unit,
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
        var source: String? = null
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
                "source" -> source = json.nextStringOrNull()
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
            // A file written before tracks declared a writer has none to restore, so the fixes are
            // read for it — the same reconstruction the column's own migration ran.
            source = source ?: TrackOrigin.inferFrom(points)?.code,
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
        var radiusM = PlaceClusterer.DEFAULT_RADIUS_M
        // Kept as the raw code: a category this build doesn't know reads as untagged but survives
        // the restore, so a file written by a later version isn't quietly stripped by this one.
        var category: String? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id" -> id = json.nextNumber().toLong()
                "label" -> label = json.nextString()
                "lat" -> lat = json.nextNumber().toDouble()
                "lon" -> lon = json.nextNumber().toDouble()
                "createdAt" -> createdAt = json.nextNumber().toLong()
                "radiusM" -> radiusM = json.nextNumber().toDouble()
                // Read as a primitive, not a string: this exporter omits the key when untagged, but a
                // writer that spells it `"category":null` must not abort the whole restore — the same
                // tolerance nextNumberOrNull gives every other optional field.
                "category" -> category = json.nextPrimitive() as? String
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Place(
            id = id, label = label, lat = lat, lon = lon, createdAt = createdAt,
            radiusM = radiusM, category = category,
        )
    }
}
