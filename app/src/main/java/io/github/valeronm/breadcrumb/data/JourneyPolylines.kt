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
 * One track's path as a many-track map draws it, every good fix kept. A journey spans many tracks, so the full
 * [TrackPoint] rows are read once and only their positions are held.
 */
internal class JourneyLine(
    val trackId: Long,
    val activityType: String,
    val greatCircle: Boolean,
    segments: List<DoubleArray>,
    /** [TrackOrigin.impliesMotion] of the track's writer, which a row keeps for its life. */
    val unmeasured: Boolean,
    val startedAt: Long,
) {
    /**
     * Every fix's longitude and latitude, interleaved, stretch after stretch. A [CasedLine] shares it, so it is never
     * written.
     */
    val lonLat: DoubleArray = DoubleArray(segments.sumOf { it.size }).also { flat ->
        var at = 0
        for (segment in segments) {
            segment.copyInto(flat, at)
            at += segment.size
        }
    }

    /** Each watched stretch's fixes, as inclusive ranges of [lonLat]'s fixes. */
    val stretchFixes: List<IntRange> = ArrayList<IntRange>(segments.size).also { ranges ->
        var first = 0
        for (segment in segments) {
            if (segment.isEmpty()) continue
            ranges += first until first + segment.size / 2
            first += segment.size / 2
        }
    }

    val fixCount: Int get() = lonLat.size / 2

    /**
     * The south-west and north-east corners of the box the fixes fill, null with no fix. Computed when the line is
     * built, because a journey's lines hold hundreds of thousands of fixes.
     */
    val corners: Pair<Coordinate, Coordinate>? = cornersOf(lonLat)
}

private fun cornersOf(lonLat: DoubleArray): Pair<Coordinate, Coordinate>? {
    if (lonLat.isEmpty()) return null
    var south = Double.POSITIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    for (i in lonLat.indices step 2) {
        west = minOf(west, lonLat[i])
        east = maxOf(east, lonLat[i])
        south = minOf(south, lonLat[i + 1])
        north = maxOf(north, lonLat[i + 1])
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
internal class JourneyPolylines(private val repository: TrackRepository, maxFixes: Int = MAX_FIXES) {

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

    private val cache = object : LruCache<LineKey, JourneyLine>(maxFixes) {
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
