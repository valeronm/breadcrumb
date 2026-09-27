package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.domain.Coordinate
import java.io.Reader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal class GoogleTimelineActivity(
    val startMs: Long,
    val endMs: Long,
    val start: Coordinate,
    val end: Coordinate,
    /** Google's activity name (`WALKING`, `IN_BUS`, …), or null where the segment gave none. */
    val type: String?,
)

internal class GoogleTimelineVisit(
    val startMs: Long,
    val endMs: Long,
    val placeId: String,
    val semanticType: String?,
    val at: Coordinate,
    /** False for a part of the visit enclosing it (a shop in a mall). */
    val topLevel: Boolean,
)

/** Path samples in file order, held in primitive arrays: a multi-year export carries hundreds of
 *  thousands, and a boxed object per sample would multiply the import's memory several times. */
internal class PathSamples {
    private var times = LongArray(INITIAL)
    private var lats = DoubleArray(INITIAL)
    private var lons = DoubleArray(INITIAL)
    var size = 0
        private set

    fun add(timeMs: Long, lat: Double, lon: Double) {
        if (size == times.size) {
            times = times.copyOf(size * 2)
            lats = lats.copyOf(size * 2)
            lons = lons.copyOf(size * 2)
        }
        times[size] = timeMs
        lats[size] = lat
        lons[size] = lon
        size++
    }

    /** The export's path buckets overlap, so the same minute arrives more than once. */
    fun sortedUnique(): SortedPath {
        val order = (0 until size).sortedBy { times[it] }
        val t = LongArray(size)
        val la = DoubleArray(size)
        val lo = DoubleArray(size)
        var n = 0
        for (i in order) {
            if (n > 0 && t[n - 1] == times[i]) continue
            t[n] = times[i]
            la[n] = lats[i]
            lo[n] = lons[i]
            n++
        }
        return if (n == size) SortedPath(t, la, lo) else SortedPath(t.copyOf(n), la.copyOf(n), lo.copyOf(n))
    }

    private companion object {
        const val INITIAL = 1024
    }
}

internal class SortedPath(val times: LongArray, val lats: DoubleArray, val lons: DoubleArray) {
    /** The index of the first sample later than [timeMs], or [times]' size when there is none. */
    fun firstAfter(timeMs: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

internal class GoogleTimelineExport(
    val activities: List<GoogleTimelineActivity>,
    val visits: List<GoogleTimelineVisit>,
    val path: SortedPath,
    /** Activity and visit segments dropped for a field that could not be read. */
    val skipped: Int,
)

/**
 * Reads Google's on-device Timeline export (`Timeline.json`, its `semanticSegments` format). A
 * segment whose fields cannot be read is counted in [GoogleTimelineExport.skipped] rather than
 * failing the file, which fails only when it is not this format at all.
 */
internal object GoogleTimelineParser {

    fun parse(reader: Reader): GoogleTimelineExport {
        val json = JsonPullReader(reader)
        val activities = mutableListOf<GoogleTimelineActivity>()
        val visits = mutableListOf<GoogleTimelineVisit>()
        val path = PathSamples()
        var skipped = 0
        var segmentsSeen = false
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "semanticSegments") {
                json.skipValue()
                continue
            }
            segmentsSeen = true
            json.beginArray()
            while (json.hasNext()) {
                if (!readSegment(json, activities, visits, path)) skipped++
            }
            json.endArray()
        }
        json.endObject()
        json.expectEnd()
        require(segmentsSeen) { "not a Google Timeline export" }
        return GoogleTimelineExport(activities, visits, path.sortedUnique(), skipped)
    }

    private class RawActivity(val start: Coordinate?, val end: Coordinate?, val type: String?)

    private data class RawVisit(
        val placeId: String?,
        val semanticType: String?,
        val at: Coordinate?,
        val topLevel: Boolean = true,
    )

    /**
     * Hands each field name of the object at the cursor to [onField], which must consume its value.
     * Any other shape is skipped whole and answers false — the export's fields are all optional, so
     * a value of the wrong shape counts as a missing one.
     */
    private inline fun JsonPullReader.forEachField(onField: (String) -> Unit): Boolean {
        if (peekChar() != '{') {
            skipValue()
            return false
        }
        beginObject()
        while (hasNext()) onField(nextName())
        endObject()
        return true
    }

    /** False when the segment was an activity or a visit that could not be read. */
    private fun readSegment(
        json: JsonPullReader,
        activities: MutableList<GoogleTimelineActivity>,
        visits: MutableList<GoogleTimelineVisit>,
        path: PathSamples,
    ): Boolean {
        var start: String? = null
        var end: String? = null
        var activity: RawActivity? = null
        var visit: RawVisit? = null
        val isObject = json.forEachField { name ->
            when (name) {
                "startTime" -> start = stringOrSkip(json)
                "endTime" -> end = stringOrSkip(json)
                "activity" -> activity = readActivity(json)
                "visit" -> visit = readVisit(json)
                "timelinePath" -> readPath(json, path)
                else -> json.skipValue()
            }
        }
        if (!isObject) return false
        activity?.let { activities += activityOf(start, end, it) ?: return false }
        visit?.let { visits += visitOf(start, end, it) ?: return false }
        return true
    }

    private fun activityOf(start: String?, end: String?, raw: RawActivity): GoogleTimelineActivity? {
        val startMs = instantOf(start) ?: return null
        val endMs = instantOf(end) ?: return null
        if (raw.start == null || raw.end == null || endMs < startMs) return null
        return GoogleTimelineActivity(startMs, endMs, raw.start, raw.end, raw.type)
    }

    private fun visitOf(start: String?, end: String?, raw: RawVisit): GoogleTimelineVisit? {
        val startMs = instantOf(start) ?: return null
        val endMs = instantOf(end) ?: return null
        val placeId = raw.placeId ?: return null
        val at = raw.at ?: return null
        return GoogleTimelineVisit(startMs, endMs, placeId, raw.semanticType, at, raw.topLevel)
    }

    private fun readActivity(json: JsonPullReader): RawActivity {
        var start: Coordinate? = null
        var end: Coordinate? = null
        var type: String? = null
        json.forEachField { name ->
            when (name) {
                "start" -> start = latLngOf(stringField(json, "latLng"))
                "end" -> end = latLngOf(stringField(json, "latLng"))
                "topCandidate" -> type = stringField(json, "type")
                else -> json.skipValue()
            }
        }
        return RawActivity(start, end, type)
    }

    private fun readVisit(json: JsonPullReader): RawVisit {
        var visit = RawVisit(null, null, null)
        var level = 0
        json.forEachField { name ->
            when (name) {
                "topCandidate" -> visit = readVisitCandidate(json)
                "hierarchyLevel" -> level = (json.nextPrimitive() as? Number)?.toInt() ?: 0
                else -> json.skipValue()
            }
        }
        return visit.copy(topLevel = level == 0)
    }

    private fun readVisitCandidate(json: JsonPullReader): RawVisit {
        var placeId: String? = null
        var semanticType: String? = null
        var at: Coordinate? = null
        json.forEachField { name ->
            when (name) {
                "placeId" -> placeId = stringOrSkip(json)
                "semanticType" -> semanticType = stringOrSkip(json)
                "placeLocation" -> at = latLngOf(stringField(json, "latLng"))
                else -> json.skipValue()
            }
        }
        return RawVisit(placeId, semanticType, at)
    }

    private fun readPath(json: JsonPullReader, path: PathSamples) {
        if (json.peekChar() != '[') {
            json.skipValue()
            return
        }
        json.beginArray()
        while (json.hasNext()) {
            var point: String? = null
            var time: String? = null
            json.forEachField { name ->
                when (name) {
                    "point" -> point = stringOrSkip(json)
                    "time" -> time = stringOrSkip(json)
                    else -> json.skipValue()
                }
            }
            val at = instantOf(time) ?: continue
            val where = latLngOf(point) ?: continue
            path.add(at, where.lat, where.lon)
        }
        json.endArray()
    }

    private fun stringOrSkip(json: JsonPullReader): String? =
        if (json.peekChar() == '"') {
            json.nextString()
        } else {
            json.skipValue()
            null
        }

    /** The string under [key] in the object at the cursor; null for any other shape. */
    private fun stringField(json: JsonPullReader, key: String): String? {
        var value: String? = null
        json.forEachField { name -> if (name == key) value = stringOrSkip(json) else json.skipValue() }
        return value
    }

    /** The export writes each time with the exporting phone's offset rather than the local one, so
     *  only the instant is meaningful. */
    private fun instantOf(text: String?): Long? = try {
        text?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }

    /** A `"<lat>°, <lon>°"` coordinate. */
    private fun latLngOf(text: String?): Coordinate? {
        val parts = text?.split(',') ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().removeSuffix("°").toDoubleOrNull() ?: return null
        val lon = parts[1].trim().removeSuffix("°").toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Coordinate(lat, lon)
    }
}
