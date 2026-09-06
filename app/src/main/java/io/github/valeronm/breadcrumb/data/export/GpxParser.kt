package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses GPX 1.0/1.1 for import — [GpxExporter]'s inverse, foreign-file tolerant: unknown elements
 * (waypoints, routes, unrecognized extensions) skip, a point's readings come from GPX 1.0's own
 * elements or a 1.1 extension alike, `<type>` maps to an [ActivityType] via aliases, and points
 * without a `<time>` drop (the timeline can't place them). Pure and stream-based; Room insertion
 * lives in TrackRepository.
 */
object GpxParser {

    /** The points as rows with no track to belong to yet ([NO_TRACK]), one list per `<trkseg>`. */
    class ParsedTrack(val type: String?, val segments: List<List<TrackPoint>>)

    /**
     * A parsed track reduced to what insertion needs: the rows as they will be stored, bar the
     * `trackId` the insert assigns. No distance: an imported track's aggregates are computed from
     * the points once they're stored, by the same walk every other track uses
     * (`TrackRepository.refreshStats`), rather than trusting — or duplicating — the file's own sum.
     */
    class ImportableTrack(
        val activityTypeName: String,
        val startedAt: Long,
        val endedAt: Long,
        val points: List<TrackPoint>,
    )

    fun parse(input: InputStream): List<ParsedTrack> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        val tracks = mutableListOf<ParsedTrack>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "gpx" -> Unit // descend
                "trk" -> tracks.add(readTrack(parser))
                else -> skip(parser)
            }
        }
        return tracks
    }

    /**
     * A parsed track made insertable, or null when fewer than two points survive. Repeats of the
     * previous fix ([withoutRepeats]) drop; points sort by time within each segment (and segments
     * by first time), so a malformed file can't yield a backwards track. The first point of every
     * segment after the first carries the segment break, as the recorder's own do.
     */
    fun toImportable(parsed: ParsedTrack): ImportableTrack? {
        val segments = parsed.segments
            .map { seg -> seg.sortedBy { it.timestamp }.withoutRepeats() }
            .filter { it.isNotEmpty() }
            .sortedBy { it.first().timestamp }
        val points = segments.flatMapIndexed { si, seg ->
            if (si == 0) seg else seg.mapIndexed { pi, p -> if (pi == 0) p.copy(segmentStart = true) else p }
        }
        if (points.size < 2) return null
        return ImportableTrack(
            activityTypeName = activityTypeFor(parsed.type).name,
            startedAt = points.first().timestamp,
            endedAt = points.last().timestamp,
            points = points,
        )
    }

    /**
     * Drops each fix repeating the previous one's instant *and* position — the same fix listed
     * twice. Files in the wild do this over long stretches, and with no reported speed the derived
     * one has a zero-length gap to divide by on every second sample, so steady driving renders as
     * a sawtooth between the real speed and the floor. [TrackQuality] carries the last speed
     * across such a gap rather than calling it a stop, but a fix that says nothing is better not
     * stored: it inflates the point count and every walk over the track pays for it. Only exact
     * repeats go: same-instant fixes at *different* positions contradict each other and picking a
     * winner would be a guess — they stay, the speed carry-forward covering them. Per segment, so
     * a segment break landing on the same instant survives; imports only — the recorder's sampling
     * gate needs the clock to advance, and history-wide it never has produced one.
     */
    private fun List<TrackPoint>.withoutRepeats(): List<TrackPoint> = filterIndexed { i, p ->
        i == 0 ||
            this[i - 1].let {
                it.timestamp != p.timestamp || it.latitude != p.latitude || it.longitude != p.longitude
            }
    }

    /**
     * Our own exports round-trip via the enum name; common foreign type strings map loosely.
     * Missing or unrecognized types default to DRIVING — imported archives are overwhelmingly
     * car trips, and the track page can reassign the odd exception.
     */
    private fun activityTypeFor(type: String?): ActivityType {
        val t = type?.trim()?.uppercase() ?: return ActivityType.DRIVING
        ActivityType.ofName(t)?.let { return it }
        return when {
            "WALK" in t || "HIK" in t -> ActivityType.WALKING
            "RUN" in t || "JOG" in t -> ActivityType.RUNNING
            "CYCL" in t || "BIK" in t -> ActivityType.CYCLING
            "FERR" in t || "BOAT" in t -> ActivityType.FERRY
            else -> ActivityType.DRIVING
        }
    }

    private fun readTrack(parser: XmlPullParser): ParsedTrack {
        var type: String? = null
        val segments = mutableListOf<List<TrackPoint>>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "type" -> type = parser.nextText()
                "trkseg" -> segments.add(readSegment(parser))
                else -> skip(parser)
            }
        }
        return ParsedTrack(type, segments)
    }

    private fun readSegment(parser: XmlPullParser): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "trkpt" -> readPoint(parser)?.let { points.add(it) }
                else -> skip(parser)
            }
        }
        return points
    }

    /**
     * Walks the whole `<trkpt>` subtree at any depth and under any namespace prefix, since GPX 1.0
     * puts `<speed>` (m/s) and `<course>` (degrees) directly on the point and 1.1 tucks them into
     * `<extensions>`, typically as gpxtpx:speed / gpxtpx:course (our own exports included). The
     * last occurrence wins. Null for a point missing its position or its time.
     */
    private fun readPoint(parser: XmlPullParser): TrackPoint? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        var ele: Double? = null
        var timeMs: Long? = null
        var speed: Float? = null
        var bearing: Float? = null
        var satellitesInFix: Int? = null
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfterLast(':')) {
                    "ele" -> ele = parser.nextText().toDoubleOrNull()
                    "time" -> timeMs = parseTime(parser.nextText())
                    "sat" -> satellitesInFix = parser.nextText().toIntOrNull()
                    "speed" -> speed = parser.nextText().toFloatOrNull()
                    "course" -> bearing = parser.nextText().toFloatOrNull()
                    else -> depth++
                }
                XmlPullParser.END_TAG -> depth--
            }
        }
        if (lat == null || lon == null || timeMs == null) return null
        return TrackPoint(
            trackId = NO_TRACK,
            latitude = lat,
            longitude = lon,
            altitude = ele,
            accuracy = null,
            speed = speed,
            bearing = bearing,
            timestamp = timeMs,
            satellitesInFix = satellitesInFix,
        )
    }

    /** ISO-8601 with offset/Z (the GPX norm); a bare local datetime is read as UTC. */
    private fun parseTime(text: String): Long? {
        val trimmed = text.trim()
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }

    /** Skips the current element and everything inside it. */
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }
}
