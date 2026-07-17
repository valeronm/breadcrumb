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

/**
 * Thin wrapper around the DAO so callers don't touch Room directly. [db] is a seam: production
 * passes nothing and gets the app's singleton database, tests pass an in-memory one.
 */
class TrackRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val appContext = context.applicationContext
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
                            speed = p.speed,
                            bearing = null,
                            timestamp = p.timeMs,
                            segmentStart = p.segmentStart,
                        )
                    },
                )
                // Aggregates come from the points we just stored, not from the GPX header: the two
                // agree for our own exports, and for a foreign file the points are the truth.
                id
            }
            // Imports bypass live ingest filtering, so drop a drive-start stray up front; the
            // repair ends by computing the fresh track's aggregates either way.
            repairLeadingPoints(trackId)
            imported++
        }
        if (imported > 0) DebugLog.i(TAG, "gpx import: $imported tracks added, $duplicates duplicates skipped")
        return GpxImportCounts(imported, duplicates)
    }

    /** Reassign a finished track's activity (misdetected, or an imported GPX without a type). */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) =
        dao.setActivityType(trackId, activityType.name)

    /**
     * Recompute a track's aggregates from its points and store them on its row — the only writer of
     * the denormalized columns, so every path that changes a track's points (finish, merge, import,
     * repair) must end here or the timeline will show stale counts. Returns the stats it wrote.
     */
    private suspend fun refreshStats(trackId: Long): TrackStats.Stats {
        val stats = TrackStats.of(dao.allPointsFor(trackId))
        dao.updateStats(
            trackId = trackId,
            distanceMeters = stats.distanceMeters,
            pointCount = stats.pointCount,
            ignoredCount = stats.ignoredCount,
            startLat = stats.startLat,
            startLon = stats.startLon,
            endLat = stats.endLat,
            endLon = stats.endLon,
        )
        return stats
    }

    /**
     * The keep/discard/purge decision for a finished track. The rule itself lives in [KeepRule];
     * [stats] is the freshly recomputed aggregate, not the row — an open track's stored distance is
     * stale by design (the recorder doesn't write it per fix), so judging a crash-recovered track
     * on the row would discard real tracks as zero-length.
     */
    private fun keepVerdict(track: Track, endedAt: Long, stats: TrackStats.Stats): KeepRule.Verdict {
        val durationSec = (endedAt - track.startedAt) / 1000
        val thresholds = KeepRule.Thresholds(
            minDurationSec = Settings.minTrackDurationSec(appContext),
            minLengthM = Settings.minTrackLengthM(appContext),
            minExtentM = Settings.minTrackExtentM(appContext),
        )
        val verdict = KeepRule.verdict(
            pointCount = stats.pointCount,
            ignoredCount = stats.ignoredCount,
            durationSec = durationSec,
            distanceMeters = stats.distanceMeters,
            thresholds = thresholds,
            extent = { stats.extentMeters },
        )
        DebugLog.i(
            TAG,
            "track ${track.id} (${track.activityType}): ${stats.pointCount} pts, " +
                "${stats.distanceMeters.toInt()} m, ${durationSec}s vs min " +
                "${thresholds.minLengthM} m / ${thresholds.minDurationSec}s" +
                (if (thresholds.minExtentM > 0) " / extent ${thresholds.minExtentM} m" else "") +
                " -> ${verdict.name.lowercase()}",
        )
        return verdict
    }

    /**
     * Closes a track, soft-deleting it instead if it's too short to be meaningful. Discarded tracks
     * keep their rows/points (excluded from the UI, stats, stays, and export) so the keep-thresholds
     * can be tuned against real data rather than losing it. The exception is a track of
     * [KeepRule.PURGE_MAX_POINTS] or fewer points in total (good + ignored), which is hard-deleted
     * outright — empty of information, so nothing to review in Recently deleted either.
     */
    private suspend fun closeOrDelete(track: Track, endedAt: Long) {
        // Finishing is where the track's aggregates are computed for the first time — the recorder
        // writes none of them while it records.
        val stats = refreshStats(track.id)
        when (keepVerdict(track, endedAt, stats)) {
            KeepRule.Verdict.KEEP -> dao.closeTrack(track.id, endedAt)
            KeepRule.Verdict.DISCARD ->
                dao.discardTrack(track.id, endedAt = endedAt, discardedAt = endedAt, reason = Track.REASON_FILTERED)
            KeepRule.Verdict.PURGE -> dao.purgeTrack(track.id)
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
     *
     * Returns the merged track's id (null if either original is gone), which [unmergeTracks] needs
     * to undo it.
     */
    suspend fun mergeTracks(earlierId: Long, laterId: Long): Long? {
        return db.withTransaction {
            val earlier = dao.track(earlierId) ?: return@withTransaction null
            val later = dao.track(laterId) ?: return@withTransaction null
            val mergedId = dao.insertTrack(
                Track(
                    activityType = earlier.activityType, // == later's (the merge condition)
                    startedAt = earlier.startedAt,
                    endedAt = later.endedAt ?: later.startedAt,
                ),
            )
            dao.copyPointsInto(mergedId, earlierId)
            dao.copyPointsInto(mergedId, laterId)
            dao.firstPointAtOrAfter(mergedId, later.startedAt)?.let { dao.markSegmentStart(it) }
            // Recomputed, not summed: the segment break at the join detaches the two halves, which
            // is exactly what the walk over the merged points does — and it keeps the one writer
            // of the denormalized columns in charge.
            refreshStats(mergedId)
            val now = System.currentTimeMillis()
            dao.setDiscarded(earlierId, now, Track.REASON_MERGED)
            dao.setDiscarded(laterId, now, Track.REASON_MERGED)
            mergedId
        }
    }

    /**
     * Undo a [mergeTracks]: drop the track it created (its points were copies) and bring the two
     * originals back to the timeline, which re-derives the stay between them.
     */
    suspend fun unmergeTracks(mergedId: Long, earlierId: Long, laterId: Long) {
        db.withTransaction {
            dao.purgeTrack(mergedId)
            dao.restoreTrack(earlierId)
            dao.restoreTrack(laterId)
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
        db.withTransaction {
            // Bounded: each pass ignores one leading point, so a handful covers any real run of
            // strays. The check reads only the leading prefix, so the loop is cheap.
            while (dropped < MAX_LEADING_REPAIRS) {
                val head = dao.firstPointsFor(trackId, TrackQuality.LEADING_CHECK_POINT_COUNT)
                if (!TrackQuality.leadingPointIsJump(head)) break
                dao.setIgnored(head.first().id, IgnoreReason.JUMP.code)
                dropped++
            }
            // Unconditional, and inside the transaction: the repair is a point-mutating path, so it
            // ends in a recompute — its caller (import, whose fresh rows have no aggregates yet)
            // relies on this being the one point walk either way.
            refreshStats(trackId)
        }
        return dropped
    }

}
