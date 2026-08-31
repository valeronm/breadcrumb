package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.domain.ActivityType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses GPX 1.0/1.1 for import — [GpxExporter]'s inverse, foreign-file tolerant: unknown elements
 * (waypoints, routes, unrecognized extensions) skip, per-point speed reads from a `<speed>` element
 * or extension, `<type>` maps to an [ActivityType] via aliases, and points without a `<time>` drop
 * (the timeline can't place them). Pure and stream-based; Room insertion lives in TrackRepository.
 */
object GpxParser {

    class ParsedPoint(
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        val timeMs: Long?,
        val speed: Float?,
    )

    class ParsedTrack(val type: String?, val segments: List<List<ParsedPoint>>)

    /** One point ready for insertion; [segmentStart] mirrors the recorder's own segment breaks. */
    class ImportPoint(
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        val timeMs: Long,
        val speed: Float?,
        val segmentStart: Boolean,
    )

    /**
     * A parsed track reduced to what insertion needs. No distance: an imported track's aggregates
     * are computed from the points once they're stored, by the same walk every other track uses
     * (`TrackRepository.refreshStats`), rather than trusting — or duplicating — the file's own sum.
     */
    class ImportableTrack(
        val activityTypeName: String,
        val startedAt: Long,
        val endedAt: Long,
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
     * A parsed track made insertable, or null when fewer than two timed points survive. Untimed
     * points and repeats of the previous fix ([withoutRepeats]) drop; points sort by time within
     * each segment (and segments by first time), so a malformed file can't yield a backwards track.
     */
    fun toImportable(parsed: ParsedTrack): ImportableTrack? {
        val segments = parsed.segments
            .map { seg -> seg.filter { it.timeMs != null }.sortedBy { it.timeMs }.withoutRepeats() }
            .filter { it.isNotEmpty() }
            .sortedBy { it.first().timeMs }
        val total = segments.sumOf { it.size }
        if (total < 2) return null

        val points = ArrayList<ImportPoint>(total)
        for ((si, seg) in segments.withIndex()) {
            for ((pi, p) in seg.withIndex()) {
                points.add(
                    ImportPoint(
                        lat = p.lat, lon = p.lon, ele = p.ele, timeMs = p.timeMs!!,
                        speed = p.speed, segmentStart = si > 0 && pi == 0,
                    ),
                )
            }
        }
        return ImportableTrack(
            activityTypeName = activityTypeFor(parsed.type).name,
            startedAt = points.first().timeMs,
            endedAt = points.last().timeMs,
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
    private fun List<ParsedPoint>.withoutRepeats(): List<ParsedPoint> = filterIndexed { i, p ->
        i == 0 || this[i - 1].let { it.timeMs != p.timeMs || it.lat != p.lat || it.lon != p.lon }
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
        var speed: Float? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when {
                parser.name == "ele" -> ele = parser.nextText().toDoubleOrNull()
                parser.name == "time" -> timeMs = parseTime(parser.nextText())
                // GPX 1.0 puts <speed> (m/s) directly on the trkpt; 1.1 tucks it into
                // <extensions>, typically as gpxtpx:speed (our own exports included).
                isSpeedTag(parser.name) -> speed = parser.nextText().toFloatOrNull()
                parser.name == "extensions" -> speed = readExtensionsSpeed(parser) ?: speed
                else -> skip(parser)
            }
        }
        if (lat == null || lon == null) return null
        return ParsedPoint(lat, lon, ele, timeMs, speed)
    }

    /** `<speed>` with any (or no) namespace prefix — the parser runs without namespace processing. */
    private fun isSpeedTag(name: String): Boolean =
        name == "speed" || name.endsWith(":speed")

    /** Scans an `<extensions>` subtree for a speed element (m/s), consuming the whole subtree. */
    private fun readExtensionsSpeed(parser: XmlPullParser): Float? {
        var speed: Float? = null
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG ->
                    if (speed == null && isSpeedTag(parser.name)) {
                        speed = parser.nextText().toFloatOrNull()
                    } else {
                        depth++
                    }
                XmlPullParser.END_TAG -> depth--
            }
        }
        return speed
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
