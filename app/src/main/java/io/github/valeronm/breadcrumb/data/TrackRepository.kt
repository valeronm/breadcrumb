package io.github.valeronm.breadcrumb.data

import android.content.Context
import android.location.Location
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlin.math.sin
import kotlin.random.Random

/** Thin wrapper around the DAO so callers don't touch Room directly. */
class TrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).trackDao()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    suspend fun startTrack(activityType: ActivityType, startedAt: Long): Long =
        dao.insertTrack(Track(activityType = activityType.name, startedAt = startedAt))

    suspend fun addPoint(point: TrackPoint): Long = dao.insertPoint(point)

    suspend fun updateDistance(trackId: Long, distanceMeters: Double) =
        dao.updateDistance(trackId, distanceMeters)

    /** Whether a track meets the user's configured keep thresholds (duration / length). */
    private suspend fun meetsKeepThresholds(track: Track, endedAt: Long): Boolean {
        // Hard floor: a track needs at least two points to be a line with any length.
        // This is a sanity check, not a user setting — empty/single-point tracks are never useful.
        if (dao.pointCount(track.id) < 2) return false
        val durationSec = (endedAt - track.startedAt) / 1000
        if (durationSec < Settings.minTrackDurationSec(appContext)) return false
        if (track.distanceMeters < Settings.minTrackLengthM(appContext)) return false
        return true
    }

    /** Closes a track, deleting it instead if it's too short to be meaningful. */
    private suspend fun closeOrDelete(track: Track, endedAt: Long) {
        if (meetsKeepThresholds(track, endedAt)) {
            dao.closeTrack(track.id, endedAt)
        } else {
            dao.deleteTrack(track.id)
        }
    }

    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        val track = dao.track(trackId) ?: return
        closeOrDelete(track, endedAt)
    }

    suspend fun deleteTrack(trackId: Long) = dao.deleteTrack(trackId)

    /**
     * Closes tracks left open by a crash/kill (endedAt == null), using their last recorded point
     * as the end time, or deleting them if too short. [exceptTrackId] is the track currently being
     * recorded, which must be left untouched.
     */
    suspend fun finalizeDangling(exceptTrackId: Long?) {
        for (track in dao.openTracks()) {
            if (track.id == exceptTrackId) continue
            val endedAt = dao.lastPointTime(track.id) ?: track.startedAt
            closeOrDelete(track, endedAt)
        }
    }

    suspend fun track(trackId: Long): Track? = dao.track(trackId)

    suspend fun allTrackIds(): List<Long> = dao.allTrackIds()

    suspend fun pointsFor(trackId: Long): List<TrackPoint> = dao.pointsFor(trackId)

    /** The ignored "bad fix" points, for marking them on the map. */
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint> = dao.ignoredPointsFor(trackId)

    /**
     * Re-evaluates every stored track against [TrackQuality], flagging bad fixes and recomputing
     * each track's distance from the remaining good points. Idempotent: it resets flags first, so
     * re-running yields the same result. Used as a one-time backfill when the bad-fix flag is
     * introduced (see [io.github.valeronm.breadcrumb.data.db.AppDatabase] migration 1→2).
     */
    suspend fun reprocessAllTracks() {
        for (trackId in dao.allTrackIds()) {
            val track = dao.track(trackId) ?: continue
            val activity = runCatching { ActivityType.valueOf(track.activityType) }
                .getOrDefault(ActivityType.UNKNOWN)
            val points = dao.rawPointsFor(trackId)
            var lastGood: TrackPoint? = null
            var distance = 0.0
            val badIds = ArrayList<Long>()
            for (point in points) {
                // A segment boundary disconnects from the previous segment: don't jump-check or
                // count distance across the gap.
                val baseline = if (point.segmentStart) null else lastGood
                if (TrackQuality.isBadFix(baseline, point, activity)) {
                    badIds.add(point.id)
                } else {
                    if (baseline != null) distance += TrackQuality.distanceMeters(baseline, point)
                    lastGood = point
                }
            }
            dao.clearIgnored(trackId)
            if (badIds.isNotEmpty()) dao.markIgnored(badIds)
            dao.updateDistance(trackId, distance)
        }
    }

    /**
     * Retroactively applies the auto-pause/stitch rule to existing tracks: walks them oldest-first
     * and merges each track into the previous one when it's the same activity, resumes within
     * [windowSec], and starts within [distanceM] of where the previous left off. The merged-in
     * track's points are re-parented onto the survivor and its first point is flagged a segment
     * start, so the original fragment boundaries are preserved as GPX `<trkseg>` breaks. Distances
     * are recomputed afterwards. One-time; runs after the bad-fix backfill.
     */
    suspend fun mergeStitchableTracks(windowSec: Int, distanceM: Int) {
        if (windowSec <= 0) return
        var baseId: Long? = null
        var baseActivity: String? = null
        var baseLastGood: TrackPoint? = null
        for (track in dao.tracksByStart()) {
            val first = dao.firstGoodPoint(track.id)
            val last = dao.lastGoodPoint(track.id)
            if (first == null || last == null) {
                baseId = null; baseActivity = null; baseLastGood = null
                continue
            }
            val anchor = baseLastGood
            if (baseId != null && anchor != null && track.activityType == baseActivity) {
                val gapSec = (first.timestamp - anchor.timestamp) / 1000.0
                val gapDist = TrackQuality.distanceMeters(anchor, first)
                if (gapSec in 0.0..windowSec.toDouble() && gapDist <= distanceM) {
                    dao.markSegmentStart(first.id)
                    dao.reparentPoints(track.id, baseId)
                    dao.deleteTrack(track.id)
                    dao.closeTrack(baseId, track.endedAt ?: last.timestamp)
                    baseLastGood = last
                    continue
                }
            }
            baseId = track.id; baseActivity = track.activityType; baseLastGood = last
        }
        // Recompute distances (segment-aware) now that points have been re-parented.
        reprocessAllTracks()
    }

    /**
     * Inserts a synthetic, already-finished track so the list / map / swipe-to-delete / share flows
     * can be exercised without real movement. Intended for debug builds only; bypasses the keep
     * thresholds. The path is a short wander at a randomised location so repeated seeds don't overlap.
     */
    suspend fun seedSampleTrack(): Long {
        val activity = ActivityType.entries.filter { it.recording }.random()
        val pointCount = 40
        val stepSec = 15L
        val now = System.currentTimeMillis()
        val startedAt = now - (pointCount - 1) * stepSec * 1000

        val baseLat = 37.7749 + Random.nextDouble(-0.05, 0.05)
        val baseLon = -122.4194 + Random.nextDouble(-0.05, 0.05)

        val trackId = dao.insertTrack(Track(activityType = activity.name, startedAt = startedAt))
        var prevLat = baseLat
        var prevLon = baseLon
        var distance = 0.0
        val seg = FloatArray(1)
        for (i in 0 until pointCount) {
            val lat = baseLat + i * 0.00012 + sin(i / 4.0) * 0.00008
            val lon = baseLon + i * 0.00018
            if (i > 0) {
                Location.distanceBetween(prevLat, prevLon, lat, lon, seg)
                distance += seg[0]
            }
            dao.insertPoint(
                TrackPoint(
                    trackId = trackId,
                    latitude = lat,
                    longitude = lon,
                    altitude = 30.0,
                    accuracy = 5f,
                    speed = 1.4f,
                    bearing = 45f,
                    timestamp = startedAt + i * stepSec * 1000,
                ),
            )
            prevLat = lat
            prevLon = lon
        }
        dao.updateDistance(trackId, distance)
        dao.closeTrack(trackId, startedAt + (pointCount - 1) * stepSec * 1000)
        return trackId
    }
}
