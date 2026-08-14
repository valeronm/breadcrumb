package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.PolylineSimplifier
import io.github.valeronm.breadcrumb.domain.SegmentBreaks
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One track's path as a many-track map draws it: flat lon/lat pairs per watched stretch, already
 * simplified to that map's fidelity. Holding this rather than [TrackPoint]s is the point — a
 * journey spans many tracks, and the full rows are read once, reduced, and let go.
 */
internal class JourneyLine(
    val trackId: Long,
    val activityType: String,
    /** Typed endpoints rather than travelled fixes — drawn along their great circle. */
    val manual: Boolean,
    val segments: List<DoubleArray>,
)

/**
 * Loads and keeps [JourneyLine]s — a read model over the point rows, like [DerivationStore] is
 * over the derivation. Reads are one-shot suspend loads, never an observation — a flow on
 * `track_points` re-runs on every fix while recording (see `TrackDao`) — and each entry is keyed
 * on the row's `endedAt` too, so a manual track rewritten in place misses its stale line.
 */
internal class JourneyPolylines(private val repository: TrackRepository) {

    /**
     * The lines for [tracks], in their order, emitted progressively — partial lists while the
     * loads run, always the complete one last. Emissions are batched rather than per track: each
     * one costs the collector a list copy and (on the journey map) a full GeoJSON rebuild, so a
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

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Pair<Long, Long?>, JourneyLine>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long?>, JourneyLine>) =
            size > MAX_LINES
    }

    private suspend fun lineFor(track: TrackSummary): JourneyLine {
        val key = track.id to track.endedAt
        mutex.withLock { cache[key] }?.let { return it }
        val points = repository.pointsFor(track.id)
        val line = withContext(Dispatchers.Default) {
            JourneyLine(
                trackId = track.id,
                activityType = track.activityType,
                manual = track.source == TrackOrigin.MANUAL.code,
                segments = SegmentBreaks.split(points).map(::segmentOf),
            )
        }
        mutex.withLock { cache[key] = line }
        return line
    }

    private fun segmentOf(points: List<TrackPoint>): DoubleArray {
        val coords = DoubleArray(points.size * 2)
        for (i in points.indices) {
            coords[i * 2] = points[i].longitude
            coords[i * 2 + 1] = points[i].latitude
        }
        return PolylineSimplifier.simplify(coords, TOLERANCE_DEG)
    }

    private companion object {
        /**
         * ~2 m of latitude — sub-pixel until around zoom 18, which is closer than a many-track
         * map is read at, while the radial pass still discards the bulk of 1 Hz fixes (walking
         * lays one down every ~1.5 m). Deliberately tighter than the web overview's 1e-4: that
         * map never zooms past a whole history, this one frames a single day, where a 10 m corner
         * cut is a visible few pixels. Full fidelity stays the track detail's job.
         */
        const val TOLERANCE_DEG = 2e-5

        /** Roughly a long journey's worth of tracks; a simplified line is a few KB. */
        const val MAX_LINES = 300

        /** Tracks per progressive emission — often enough to watch the map fill, rare enough
         *  that the per-emission costs stay negligible. */
        const val PUBLISH_EVERY = 8
    }
}
