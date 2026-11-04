package io.github.valeronm.breadcrumb.data

import android.util.LruCache
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.GreatCircle
import io.github.valeronm.breadcrumb.domain.SegmentBreaks
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * One track's path as a many-track map draws it: flat lon/lat pairs per watched stretch, every good
 * fix kept. A journey spans many tracks, so the full [TrackPoint] rows are read once and only their
 * positions are held.
 */
internal class JourneyLine(
    val trackId: Long,
    val activityType: String,
    val greatCircle: Boolean,
    private val segments: List<DoubleArray>,
    /** [TrackOrigin.impliesMotion] of the track's writer, which a row keeps for its life. */
    val unmeasured: Boolean,
    val startedAt: Long,
) {
    val fixCount: Int get() = segments.sumOf { it.size } / 2

    /**
     * The south-west and north-east corners of the box the fixes fill, null with no fix. Computed when the line is
     * built, because a journey's lines hold hundreds of thousands of fixes.
     */
    val corners: Pair<Coordinate, Coordinate>? = cornersOf(segments)

    /** Each watched stretch's fixes in order. */
    fun stretches(): List<List<Coordinate>> = segments.map { segment -> List(segment.size / 2) { Coordinate(segment[2 * it + 1], segment[2 * it]) } }

    /** Calls [action] with each watched stretch's fixes in order, and whether the fix opens its stretch. */
    fun forEachFix(action: (lat: Double, lon: Double, opensStretch: Boolean) -> Unit) {
        for (segment in segments) for (i in segment.indices step 2) action(segment[i + 1], segment[i], i == 0)
    }
}

private fun cornersOf(segments: List<DoubleArray>): Pair<Coordinate, Coordinate>? {
    if (segments.all { it.isEmpty() }) return null
    var south = Double.POSITIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    for (segment in segments) {
        for (i in segment.indices step 2) {
            west = minOf(west, segment[i])
            east = maxOf(east, segment[i])
            south = minOf(south, segment[i + 1])
            north = maxOf(north, segment[i + 1])
        }
    }
    return Coordinate(south, west) to Coordinate(north, east)
}

/** The keys [JourneyPolylines] caches each track's line under. */
internal fun lineKeys(tracks: List<TrackSummary>): List<LineKey> = tracks.map { LineKey(it) }

internal data class LineKey(val id: Long, val activityType: String, val startedAt: Long, val endedAt: Long?) {
    constructor(track: TrackSummary) : this(track.id, track.activityType, track.startedAt, track.endedAt)
    constructor(track: Track) : this(track.id, track.activityType, track.startedAt, track.endedAt)
}

/**
 * Loads and keeps [JourneyLine]s — a read model over the point rows, like [DerivationStore] is
 * over the derivation. Reads are one-shot suspend loads, never an observation — a flow on
 * `track_points` re-runs on every fix while recording (see `TrackDao`) — and each entry is keyed
 * on the row's type and bounds too ([LineKey]), so a track retyped or rewritten in place misses its stale line.
 */
internal class JourneyPolylines(private val repository: TrackRepository) {

    /**
     * The lines for [tracks], in their order, emitted progressively — partial lists while the
     * loads run, always the complete one last. Emissions are batched rather than per track: each
     * one costs the collector a list copy and (on the journey map) a rebuild of the bands in view, so a
     * cache-served reopen collapses to a handful instead of one per track.
     */
    fun linesFor(tracks: List<TrackSummary>): Flow<List<JourneyLine>> = flow {
        val loaded = ArrayList<JourneyLine>(tracks.size)
        for (track in tracks) {
            loaded += lineFor(track)
            if (loaded.size < tracks.size && loaded.size % PUBLISH_EVERY == 0) emit(ArrayList(loaded))
        }
        emit(loaded)
    }

    private val cache = object : LruCache<LineKey, JourneyLine>(MAX_FIXES) {
        override fun sizeOf(key: LineKey, value: JourneyLine) = value.fixCount
    }

    suspend fun lineFor(track: TrackSummary): JourneyLine = lineFor(LineKey(track), track.pointCount, track.source)

    suspend fun lineFor(track: Track): JourneyLine = lineFor(LineKey(track), track.pointCount, track.source)

    private suspend fun lineFor(key: LineKey, pointCount: Int, source: String?): JourneyLine {
        cache[key]?.let { return it }
        val points = repository.pointsFor(key.id)
        val line = withContext(Dispatchers.Default) {
            JourneyLine(
                trackId = key.id,
                activityType = key.activityType,
                greatCircle = GreatCircle.drawsPath(pointCount, ActivityType.ofName(key.activityType)),
                segments = SegmentBreaks.split(points).map(::segmentOf),
                unmeasured = TrackOrigin.impliesMotion(source),
                startedAt = key.startedAt,
            )
        }
        cache.put(key, line)
        return line
    }

    private fun segmentOf(points: List<TrackPoint>): DoubleArray {
        val coords = DoubleArray(points.size * 2)
        for (i in points.indices) {
            coords[i * 2] = points[i].longitude
            coords[i * 2 + 1] = points[i].latitude
        }
        return coords
    }

    private companion object {
        /** 16 MB of lines at 16 bytes a fix. */
        const val MAX_FIXES = 1_000_000

        /** Tracks per progressive emission — often enough to watch the map fill, rare enough
         *  that the per-emission costs stay negligible. */
        const val PUBLISH_EVERY = 8
    }
}
