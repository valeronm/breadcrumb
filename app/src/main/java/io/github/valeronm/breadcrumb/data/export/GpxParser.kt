package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.DistanceFn
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses GPX 1.0/1.1 track data for import — the inverse of [GpxExporter], but tolerant of
 * foreign files: unknown elements (extensions, waypoints, routes) are skipped, `<type>` maps to
 * an [ActivityType] through a few aliases, and points without a `<time>` are dropped (the
 * timeline can't place them). Pure and stream-based; the Room insertion lives in TrackRepository.
 */
object GpxParser {

    class ParsedPoint(val lat: Double, val lon: Double, val ele: Double?, val timeMs: Long?)

    class ParsedTrack(val type: String?, val segments: List<List<ParsedPoint>>)

    /** One point ready for insertion; [segmentStart] mirrors the recorder's auto-pause breaks. */
    class ImportPoint(
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        val timeMs: Long,
        val segmentStart: Boolean,
    )

    /** A parsed track reduced to what insertion needs; distance is summed within segments. */
    class ImportableTrack(
        val activityTypeName: String,
        val startedAt: Long,
        val endedAt: Long,
        val distanceMeters: Double,
        val points: List<ImportPoint>,
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
     * Converts a parsed track to an insertable one, or null when fewer than two timed points
     * survive. Untimed points are dropped; points are ordered by time within each segment (and
     * segments by their first time) so malformed files can't produce a backwards track.
     */
    fun toImportable(parsed: ParsedTrack, distance: DistanceFn): ImportableTrack? {
        val segments = parsed.segments
            .map { seg -> seg.filter { it.timeMs != null }.sortedBy { it.timeMs } }
            .filter { it.isNotEmpty() }
            .sortedBy { it.first().timeMs }
        val total = segments.sumOf { it.size }
        if (total < 2) return null

        var distanceMeters = 0.0
        val points = ArrayList<ImportPoint>(total)
        for ((si, seg) in segments.withIndex()) {
            for ((pi, p) in seg.withIndex()) {
                if (pi > 0) {
                    val prev = seg[pi - 1]
                    distanceMeters += distance.metres(prev.lat, prev.lon, p.lat, p.lon)
                }
                points.add(
                    ImportPoint(
                        lat = p.lat, lon = p.lon, ele = p.ele, timeMs = p.timeMs!!,
                        segmentStart = si > 0 && pi == 0,
                    ),
                )
            }
        }
        return ImportableTrack(
            activityTypeName = activityTypeFor(parsed.type).name,
            startedAt = points.first().timeMs,
            endedAt = points.last().timeMs,
            distanceMeters = distanceMeters,
            points = points,
        )
    }

    /**
     * Our own exports round-trip via the enum name; common foreign type strings map loosely.
     * Missing or unrecognised types default to DRIVING — imported archives are overwhelmingly
     * car trips, and the track page can reassign the odd exception.
     */
    private fun activityTypeFor(type: String?): ActivityType {
        val t = type?.trim()?.uppercase() ?: return ActivityType.DRIVING
        ActivityType.ofName(t)?.let { return it }
        return when {
            "WALK" in t || "HIK" in t -> ActivityType.WALKING
            "RUN" in t || "JOG" in t -> ActivityType.RUNNING
            "CYCL" in t || "BIK" in t -> ActivityType.CYCLING
            else -> ActivityType.DRIVING
        }
    }

    private fun readTrack(parser: XmlPullParser): ParsedTrack {
        var type: String? = null
        val segments = mutableListOf<List<ParsedPoint>>()
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

    private fun readSegment(parser: XmlPullParser): List<ParsedPoint> {
        val points = mutableListOf<ParsedPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "trkpt" -> readPoint(parser)?.let { points.add(it) }
                else -> skip(parser)
            }
        }
        return points
    }

    private fun readPoint(parser: XmlPullParser): ParsedPoint? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        var ele: Double? = null
        var timeMs: Long? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "ele" -> ele = parser.nextText().toDoubleOrNull()
                "time" -> timeMs = parseTime(parser.nextText())
                else -> skip(parser)
            }
        }
        if (lat == null || lon == null) return null
        return ParsedPoint(lat, lon, ele, timeMs)
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
