package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "Breadcrumb"

/** How long soft-deleted (keep-threshold-filtered) tracks are retained before being purged. */
private const val DISCARDED_RETENTION_DAYS = 14

/** Thin wrapper around the DAO so callers don't touch Room directly. */
class TrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    private val dao = db.trackDao()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    fun observeEndpoints(): Flow<List<TrackEndpoints>> = dao.observeEndpoints()

    /** Keep-threshold-filtered (soft-deleted) tracks, for the debug "Discarded tracks" screen. */
    fun observeDiscardedSummaries(): Flow<List<TrackSummary>> = dao.observeDiscardedSummaries()

    suspend fun startTrack(activityType: ActivityType, startedAt: Long): Long =
        dao.insertTrack(Track(activityType = activityType.name, startedAt = startedAt))

    suspend fun addPoint(point: TrackPoint): Long = dao.insertPoint(point)

    suspend fun addPoints(points: List<TrackPoint>) = dao.insertPoints(points)

    class GpxImportCounts(val imported: Int, val duplicates: Int)

    /**
     * Inserts parsed GPX tracks. Keep thresholds do NOT apply — an explicit import is kept as-is.
     * A track whose exact time span already exists is skipped as a duplicate (re-importing the
     * same file, or importing our own export back). Each track inserts in its own transaction.
     */
    suspend fun importTracks(tracks: List<GpxParser.ImportableTrack>): GpxImportCounts {
        var imported = 0
        var duplicates = 0
        for (track in tracks) {
            if (dao.countTracksSpanning(track.startedAt, track.endedAt) > 0) {
                duplicates++
                continue
            }
            db.withTransaction {
                val trackId = dao.insertTrack(
                    Track(
                        activityType = track.activityTypeName,
                        startedAt = track.startedAt,
                        endedAt = track.endedAt,
                        distanceMeters = track.distanceMeters,
                    ),
                )
                dao.insertPoints(
                    track.points.map { p ->
                        TrackPoint(
                            trackId = trackId,
                            latitude = p.lat,
                            longitude = p.lon,
                            altitude = p.ele,
                            accuracy = null,
                            speed = null,
                            bearing = null,
                            timestamp = p.timeMs,
                            segmentStart = p.segmentStart,
                            provider = "import",
                        )
                    },
                )
            }
            imported++
        }
        if (imported > 0) DebugLog.i(TAG, "gpx import: $imported tracks added, $duplicates duplicates skipped")
        return GpxImportCounts(imported, duplicates)
    }

    suspend fun updateDistance(trackId: Long, distanceMeters: Double) =
        dao.updateDistance(trackId, distanceMeters)

    /** Reassign a finished track's activity (misdetected, or an imported GPX without a type). */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) =
        dao.setActivityType(trackId, activityType.name)

    /**
     * Whether a track meets the user's configured keep thresholds (duration / length / extent).
     * The rule itself lives in [KeepRule]; this loads the points/settings it needs. The extent is
     * passed lazily so its bounding-box pass runs only when the extent gate is enabled.
     */
    private suspend fun meetsKeepThresholds(track: Track, endedAt: Long): Boolean {
        val points = dao.pointsFor(track.id)
        val durationSec = (endedAt - track.startedAt) / 1000
        val thresholds = KeepRule.Thresholds(
            minDurationSec = Settings.minTrackDurationSec(appContext),
            minLengthM = Settings.minTrackLengthM(appContext),
            minExtentM = Settings.minTrackExtentM(appContext),
        )
        val keep = KeepRule.shouldKeep(
            pointCount = points.size,
            durationSec = durationSec,
            distanceMeters = track.distanceMeters,
            thresholds = thresholds,
            extent = { TrackQuality.boundingExtentMeters(points) },
        )
        DebugLog.i(
            TAG,
            "track ${track.id} (${track.activityType}): ${points.size} pts, " +
                "${track.distanceMeters.toInt()} m, ${durationSec}s vs min " +
                "${thresholds.minLengthM} m / ${thresholds.minDurationSec}s" +
                (if (thresholds.minExtentM > 0) " / extent ${thresholds.minExtentM} m" else "") +
                " -> ${if (keep) "keep" else "discard"}",
        )
        return keep
    }

    /**
     * Closes a track, soft-deleting it instead if it's too short to be meaningful. Discarded tracks
     * keep their rows/points (excluded from the UI, stats, stays, and export) so the keep-thresholds
     * can be tuned against real data rather than losing it.
     */
    private suspend fun closeOrDelete(track: Track, endedAt: Long) {
        if (meetsKeepThresholds(track, endedAt)) {
            dao.closeTrack(track.id, endedAt)
        } else {
            dao.discardTrack(track.id, endedAt = endedAt, discardedAt = endedAt)
        }
    }

    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        val track = dao.track(trackId) ?: return
        closeOrDelete(track, endedAt)
    }

    suspend fun deleteTrack(trackId: Long) = dao.deleteTrack(trackId)

    /**
     * Close the short stay between [earlierId] and [laterId] by building a NEW track that spans both
     * (their points copied in order, a segment break marking the join) and moving the two originals
     * to discarded — so the merge is a fresh track and the originals are preserved (reviewable in the
     * debug screen, auto-purged after the retention window) rather than destroyed. The derived stay
     * disappears because the discarded originals leave the timeline.
     */
    suspend fun mergeTracks(earlierId: Long, laterId: Long) {
        db.withTransaction {
            val earlier = dao.track(earlierId) ?: return@withTransaction
            val later = dao.track(laterId) ?: return@withTransaction
            val mergedId = dao.insertTrack(
                Track(
                    activityType = earlier.activityType, // == later's (the merge condition)
                    startedAt = earlier.startedAt,
                    endedAt = later.endedAt ?: later.startedAt,
                    distanceMeters = earlier.distanceMeters + later.distanceMeters,
                ),
            )
            dao.copyPointsInto(mergedId, earlierId)
            dao.copyPointsInto(mergedId, laterId)
            dao.firstPointAtOrAfter(mergedId, later.startedAt)?.let { dao.markSegmentStart(it) }
            val now = System.currentTimeMillis()
            dao.setDiscarded(earlierId, now)
            dao.setDiscarded(laterId, now)
        }
    }

    /**
     * Hard-delete soft-deleted tracks older than the retention window — discarded tracks are kept
     * only long enough to tune the keep-thresholds against, not forever. Called on app open.
     */
    suspend fun purgeOldDiscarded(retentionDays: Int = DISCARDED_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        val purged = dao.purgeDiscardedBefore(cutoff)
        if (purged > 0) DebugLog.i(TAG, "purged $purged discarded track(s) older than $retentionDays days")
    }

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

    /** Usable points inserted after [afterId] — the live preview's incremental reload. */
    suspend fun pointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        dao.pointsAfter(trackId, afterId)

    /** The ignored "bad fix" points, for marking them on the map. */
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint> = dao.ignoredPointsFor(trackId)

    /**
     * One-time backfill of [IgnoreReason] for ignored points recorded before DB v5 (which stored
     * only the flag). Replays [TrackQuality.badFixReason] over each track with the same baseline
     * walk the recorder used — the stored flags decide which points were good — and attributes
     * whatever the accuracy/jump rules don't explain to the GNSS cross-check, the recorder's only
     * other rejection path. Idempotent: it only touches ignored points whose reason is still null.
     */
    suspend fun backfillIgnoreReasons() {
        val maxAccuracyM = Settings.accuracyGateM(appContext).toFloat()
        for (trackId in dao.allTrackIds()) {
            val track = dao.track(trackId) ?: continue
            val activity = ActivityType.ofName(track.activityType) ?: ActivityType.UNKNOWN
            var lastGood: TrackPoint? = null
            val idsByReason = HashMap<IgnoreReason, MutableList<Long>>()
            for (point in dao.allPointsFor(trackId)) {
                val baseline = if (point.segmentStart) null else lastGood
                if (!point.ignored) {
                    lastGood = point
                    continue
                }
                if (point.ignoreReason != null) continue
                val reason = TrackQuality.badFixReason(baseline, point, activity, maxAccuracyM)
                    ?: IgnoreReason.NO_GNSS
                idsByReason.getOrPut(reason) { ArrayList() }.add(point.id)
            }
            for ((reason, ids) in idsByReason) dao.setIgnoreReason(ids, reason.code)
        }
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
        for (i in 0 until pointCount) {
            val lat = baseLat + i * 0.00012 + sin(i / 4.0) * 0.00008
            val lon = baseLon + i * 0.00018
            if (i > 0) distance += AndroidDistance.metres(prevLat, prevLon, lat, lon)
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
