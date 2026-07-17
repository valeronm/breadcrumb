package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import java.util.zip.GZIPOutputStream

/**
 * Writes the whole recorded history as one gzipped JSON document — the web companion's data
 * source. Unlike GPX this keeps everything the viewer can use: ignored points with their
 * reasons, fix-quality metadata, named places, and the liveness events stay derivation needs.
 * Discarded tracks and a still-open recording are excluded, matching the rest of the app.
 *
 * Points are per-point arrays in [POINT_FIELDS] order (echoed in the document header as
 * `pointFields`) rather than objects — at millions of points the field names would dominate
 * the file and the parse. Tracks stream one at a time, so memory stays at one track's points.
 */
object BackupExporter {

    const val MIME_TYPE = "application/gzip"
    const val FORMAT = "breadcrumb-export"
    const val VERSION = 1

    /** Field order of each per-point array in `tracks[].points`. Append-only across versions. */
    internal val POINT_FIELDS = listOf(
        "timestamp", "lat", "lon", "alt", "accuracy", "speed", "bearing",
        "verticalAccuracy", "speedAccuracy", "bearingAccuracy", "satellitesInFix", "cn0",
        "ignored", "ignoreReason", "segmentStart",
    )

    fun fileName(now: Long): String = "breadcrumb-${exportFileStamp(now)}.json.gz"

    /**
     * Loads everything and streams it gzipped to [uri] (from the system create-document picker).
     * Returns the number of tracks written, or null if the stream couldn't be opened.
     */
    suspend fun exportTo(
        context: Context,
        trackRepository: TrackRepository,
        placeRepository: PlaceRepository,
        livenessRepository: LivenessRepository,
        uri: Uri,
        exportedAt: Long,
        onProgress: (tracksDone: Int, tracksTotal: Int) -> Unit,
    ): Int? {
        val out = context.contentResolver.openOutputStream(uri) ?: return null
        val tracks = trackRepository.exportTracks()
        // The large deflater buffer keeps writes to the SAF stream from degrading into the
        // default 512-byte chunks — each one a Binder round-trip to the documents provider.
        GZIPOutputStream(out, 64 * 1024).bufferedWriter().use { writer ->
            writeJson(
                writer,
                exportedAt,
                tracks,
                { trackRepository.allPointsFor(it) },
                placeRepository.allPlaces(),
                livenessRepository.allEvents(),
                onTrackWritten = { done -> onProgress(done, tracks.size) },
            )
        }
        return tracks.size
    }

    internal suspend fun writeJson(
        out: Appendable,
        exportedAt: Long,
        tracks: List<Track>,
        pointsFor: suspend (Long) -> List<TrackPoint>,
        places: List<Place>,
        liveness: List<LivenessEvent>,
        onTrackWritten: (done: Int) -> Unit = {},
    ) {
        out.append("""{"format":${str(FORMAT)},"version":$VERSION,"exportedAt":$exportedAt""")
        out.append(""","trackCount":${tracks.size}""")
        out.append(""","pointFields":[${POINT_FIELDS.joinToString(",") { str(it) }}]""")

        out.append(""","tracks":[""")
        for ((i, track) in tracks.withIndex()) {
            if (i > 0) out.append(',')
            out.append(trackHeader(track))
            for ((j, point) in pointsFor(track.id).withIndex()) {
                if (j > 0) out.append(',')
                appendPoint(out, point)
            }
            out.append("]}")
            onTrackWritten(i + 1)
        }
        out.append(']')

        out.append(""","places":[""")
        places.joinTo(out, ",") { placeObject(it) }
        out.append(']')

        out.append(""","liveness":[""")
        liveness.joinTo(out, ",") { livenessObject(it) }
        out.append("]}")
    }

    /** The track object, opened up to and including `"points":[` — the point stream follows. */
    private fun trackHeader(t: Track): String = buildString {
        append("""{"id":${t.id},"activityType":${str(t.activityType)}""")
        append(""","startedAt":${t.startedAt},"endedAt":${t.endedAt}""")
        append(""","distanceMeters":${t.distanceMeters}""")
        append(""","pointCount":${t.pointCount},"ignoredCount":${t.ignoredCount}""")
        append(""","startLat":${t.startLat},"startLon":${t.startLon}""")
        append(""","endLat":${t.endLat},"endLon":${t.endLon}""")
        append(""","points":[""")
    }

    // Appends straight to the writer, no per-point buildString: at millions of points the
    // intermediate builder+string per point would be the export's dominant allocation.
    private fun appendPoint(out: Appendable, p: TrackPoint) {
        out.append('[')
        out.append(p.timestamp.toString()).append(',')
        out.append(p.latitude.toString()).append(',')
        out.append(p.longitude.toString()).append(',')
        out.append(p.altitude.toString()).append(',')
        out.append(p.accuracy.toString()).append(',')
        out.append(p.speed.toString()).append(',')
        out.append(p.bearing.toString()).append(',')
        out.append(p.verticalAccuracy.toString()).append(',')
        out.append(p.speedAccuracy.toString()).append(',')
        out.append(p.bearingAccuracy.toString()).append(',')
        out.append(p.satellitesInFix.toString()).append(',')
        out.append(p.cn0.toString()).append(',')
        out.append(if (p.ignored) '1' else '0').append(',')
        out.append(p.ignoreReason?.let { str(it) } ?: "null").append(',')
        out.append(if (p.segmentStart) '1' else '0')
        out.append(']')
    }

    private fun placeObject(p: Place): String =
        """{"id":${p.id},"label":${str(p.label)},"lat":${p.lat},"lon":${p.lon}""" +
            ""","createdAt":${p.createdAt},"radiusM":${p.radiusM}}"""

    private fun livenessObject(e: LivenessEvent): String =
        """{"type":${str(e.type)},"at":${e.at},"until":${e.until}}"""

    /** JSON string literal with the mandatory escapes. */
    private fun str(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
