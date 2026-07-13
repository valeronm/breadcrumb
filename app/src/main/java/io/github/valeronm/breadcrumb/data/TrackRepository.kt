package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow

private const val TAG = "Breadcrumb"

/** How long soft-deleted tracks stay restorable in Recently deleted before being purged. */
const val DISCARDED_RETENTION_DAYS = 14

/** Safety bound on leading-stray removal per track (real runs are 1, rarely 2). */
private const val MAX_LEADING_REPAIRS = 5

/** Thin wrapper around the DAO so callers don't touch Room directly. */
class TrackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    private val dao = db.trackDao()

    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries()

    fun observeEndpoints(): Flow<List<TrackEndpoints>> = dao.observeEndpoints()

    /** Soft-deleted tracks with when/why, for the Recently deleted screen. */
    fun observeDiscardedSummaries(): Flow<List<DiscardedSummary>> = dao.observeDiscardedSummaries()

    suspend fun startTrack(activityType: ActivityType, startedAt: Long): Long =
        dao.insertTrack(Track(activityType = activityType.name, startedAt = startedAt))

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
            val trackId = db.withTransaction {
                val id = dao.insertTrack(
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
                            trackId = id,
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
                id
            }
            // Imports bypass live ingest filtering, so drop a drive-start stray up front.
            repairLeadingPoints(trackId)
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
        val pointCount = dao.countGoodPoints(track.id)
        val durationSec = (endedAt - track.startedAt) / 1000
        val thresholds = KeepRule.Thresholds(
            minDurationSec = Settings.minTrackDurationSec(appContext),
            minLengthM = Settings.minTrackLengthM(appContext),
            minExtentM = Settings.minTrackExtentM(appContext),
        )
        // Extent = the diagonal of the lat/lon bounding box: distinguishes a real trip from a
        // stationary blob — unlike accumulated length, GPS jitter can't inflate it. Computed via
        // SQL aggregates instead of loading every point row; the bounds query runs only when the
        // extent gate is enabled (mirroring KeepRule's lazy extent).
        val extentM = if (thresholds.minExtentM > 0 && pointCount >= 2) {
            val b = dao.goodPointBounds(track.id)
            if (b?.minLat == null) 0.0
            else AndroidDistance.metres(b.minLat, b.minLon!!, b.maxLat!!, b.maxLon!!)
        } else {
            0.0
        }
        val keep = KeepRule.shouldKeep(
            pointCount = pointCount,
            durationSec = durationSec,
            distanceMeters = track.distanceMeters,
            thresholds = thresholds,
            extent = { extentM },
        )
        DebugLog.i(
            TAG,
            "track ${track.id} (${track.activityType}): $pointCount pts, " +
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
            dao.discardTrack(track.id, endedAt = endedAt, discardedAt = endedAt, reason = Track.REASON_FILTERED)
        }
    }

    suspend fun finishTrack(trackId: Long, endedAt: Long) {
        val track = dao.track(trackId) ?: return
        closeOrDelete(track, endedAt)
    }

    /**
     * User-initiated delete is a soft delete: the track moves to Recently deleted (restorable)
     * and is only hard-deleted by [purgeOldDiscarded] after the retention window.
     */
    suspend fun deleteTrack(trackId: Long) {
        dao.setDiscarded(trackId, System.currentTimeMillis(), Track.REASON_DELETED)
    }

    /** Bring a discarded track back to the timeline (undoes a delete/discard within retention). */
    suspend fun restoreTrack(trackId: Long) = dao.restoreTrack(trackId)

    /** Hard-delete everything in Recently deleted right now (the user's "clear all"). */
    suspend fun purgeAllDiscarded() {
        val purged = dao.purgeAllDiscarded()
        if (purged > 0) DebugLog.i(TAG, "cleared $purged track(s) from Recently deleted")
    }

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
            dao.setDiscarded(earlierId, now, Track.REASON_MERGED)
            dao.setDiscarded(laterId, now, Track.REASON_MERGED)
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
     * Repairs a track's stray leading point(s) ([TrackQuality.leadingPointIsJump] — the drive-start
     * cold-start artifact imports let through): marks each as an ignored JUMP fix and recomputes the
     * stored distance over the remaining good points. Loops in case more than one stray leads the
     * track. The track's time span is deliberately left unchanged so the exact-span duplicate check
     * still recognises a re-import of the same file. Returns how many points were dropped.
     */
    suspend fun repairLeadingPoints(trackId: Long): Int {
        var dropped = 0
        // Bounded: each pass ignores one leading point, so a handful covers any real run of strays.
        repeat(MAX_LEADING_REPAIRS) {
            // The stray check only needs the leading prefix; load the full track only on a repair.
            val head = dao.firstPointsFor(trackId, TrackQuality.LEADING_CHECK_POINT_COUNT)
            if (!TrackQuality.leadingPointIsJump(head)) return dropped
            db.withTransaction {
                val points = dao.pointsFor(trackId)
                dao.setIgnored(points.first().id, IgnoreReason.JUMP.code)
                // Same distance walk as the recorder: segment starts detach from the previous point.
                var distance = 0.0
                var prev: TrackPoint? = null
                for (p in points.drop(1)) {
                    val baseline = if (p.segmentStart) null else prev
                    if (baseline != null) distance += TrackQuality.distanceMeters(baseline, p)
                    prev = p
                }
                dao.updateDistance(trackId, distance)
            }
            dropped++
        }
        return dropped
    }

    /**
     * One-time backfill: repairs stray leading points across every track. Imports before this
     * shipped (and any imported before the auto-repair on import) kept their drive-start artifact;
     * this cleans the existing history. Idempotent — a track with no stray is left untouched.
     */
    suspend fun repairAllLeadingPoints(): Int {
        var total = 0
        for (trackId in dao.allTrackIds()) total += repairLeadingPoints(trackId)
        if (total > 0) DebugLog.i(TAG, "repaired stray leading points on $total track-point(s)")
        return total
    }

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

}
