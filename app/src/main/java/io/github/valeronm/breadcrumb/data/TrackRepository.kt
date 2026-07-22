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
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
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
            // repair ends by computing the fresh track's aggregates and its review mark either
            // way. Edge stays are left in place — trimming one is the user's call, from the
            // track screen.
            repairLeadingPoints(trackId)
            imported++
        }
        if (imported > 0) DebugLog.i(TAG, "gpx import: $imported tracks added, $duplicates duplicates skipped")
        return GpxImportCounts(imported, duplicates)
    }

    /**
     * Inserts a batch of backup tracks verbatim, points and all, under fresh ids — one
     * transaction for the whole batch, so a 3000-track restore commits (and wakes the observed
     * timeline queries) dozens of times, not thousands. The rows' aggregates come from the file —
     * they were written by [refreshStats] over these same points before the export, so re-walking
     * them here would only recompute the same numbers. No keep thresholds, no duplicate check:
     * restore targets an empty app (the UI only offers it there).
     */
    suspend fun insertBackupTracks(batch: List<Pair<Track, List<TrackPoint>>>) {
        db.withTransaction {
            for ((track, points) in batch) {
                // The mark is not a property of the track — it is this code's verdict about it,
                // and the rule lives here, not in the data. Restoring a stored verdict lets the
                // two drift apart in both directions: a marked track the detector no longer
                // flags, or an unmarked one it now would. So restore restores the data and the
                // logic runs on top, off the points already in memory. (The aggregates are the
                // opposite case — a fixed function of the points, so the file's copies stand.)
                val id = dao.insertTrack(track.copy(id = 0, needsReview = edgeStays(track.activityType, points).isNotEmpty()))
                dao.insertPoints(points.map { it.copy(id = 0, trackId = id) })
            }
        }
    }

    /** Reassign a finished track's activity (misdetected, or an imported GPX without a type). */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) =
        dao.setActivityType(trackId, activityType.name)

    /**
     * Recompute a track's aggregates from its points and store them on its row — the only writer of
     * the denormalized columns, so every path that changes a track's points (finish, merge, import,
     * repair) must end here or the timeline will show stale counts. Returns the stats it wrote.
     */
    private suspend fun refreshStats(trackId: Long): TrackStats.Stats =
        refreshStats(trackId, dao.allPointsFor(trackId))

    /** As above, from points already in memory — the trim paths hold them and must not re-read. */
    private suspend fun refreshStats(trackId: Long, points: List<TrackPoint>): TrackStats.Stats {
        val stats = TrackStats.of(points)
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
    private fun keepVerdict(track: Track, startedAt: Long, endedAt: Long, stats: TrackStats.Stats): KeepRule.Verdict {
        val durationSec = (endedAt - startedAt) / 1000
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
    private suspend fun closeOrDelete(track: Track, endedAt: Long) = db.withTransaction {
        // No edge trim here: splitting a stay off a track is a user decision now, taken on the
        // track screen against the overlay that shows what would be cut ([trimTrack]). What the
        // detector finds is recorded as a mark instead, so the timeline can point at it.
        // Finishing is where the track's aggregates are computed for the first time — the recorder
        // writes none of them while it records.
        val points = dao.allPointsFor(track.id)
        val stats = refreshStats(track.id, points)
        dao.setNeedsReview(track.id, edgeStays(track.activityType, points).isNotEmpty())
        when (keepVerdict(track, track.startedAt, endedAt, stats)) {
            KeepRule.Verdict.KEEP -> dao.closeTrack(track.id, endedAt)
            KeepRule.Verdict.DISCARD ->
                dao.discardTrack(track.id, endedAt = endedAt, discardedAt = endedAt, reason = Track.REASON_FILTERED)
            KeepRule.Verdict.PURGE -> dao.purgeTrack(track.id)
        }
    }

    /**
     * Split off a stay recorded onto the track's edge — Activity Recognition lagged the real
     * arrival (or departure), so recording ran on inside a venue ([EdgeStayDetector]; position
     * decides *whether*, speed collapse decides *where*). The stay's points move (not copy) to a
     * new track that is immediately soft-discarded with [Track.REASON_TRIMMED]: reviewable in
     * Recently deleted and restorable. Both tracks are bounded at the cut, so a restored stay
     * meets its track in a zero-length seam — which [StayDeriver]'s agreeing-endpoints rule
     * derives as a stay carrying the merge-back offer that undoes the trim.
     *
     * The stay tracks' rows are fully written here (bounds, stats, discard); the trimmed track's
     * bounds are written too, but its stats are the caller's to recompute from the points
     * returned — null when there was nothing to cut. Callers wrap the whole trim-and-recompute
     * sequence in one transaction.
     */
    private suspend fun trimEdgeStays(track: Track, endedAt: Long): List<TrackPoint>? {
        var cut = false
        var points = dao.allPointsFor(track.id)
        for (stay in edgeStays(track.activityType, points)) {
            val isEnd = stay.side == EdgeStayDetector.Side.END
            val (stayPoints, keptPoints) = points.partition { stay.movesOut(it.timestamp) }
            val stayTrackId = dao.insertTrack(
                Track(
                    activityType = track.activityType,
                    startedAt = if (isEnd) stay.boundaryTs else track.startedAt,
                    endedAt = if (isEnd) endedAt else stay.boundaryTs,
                ),
            )
            if (isEnd) {
                dao.movePointsFrom(track.id, stayTrackId, stay.boundaryTs)
                dao.closeTrack(track.id, stay.boundaryTs)
            } else {
                dao.movePointsBefore(track.id, stayTrackId, stay.boundaryTs)
                dao.setStartedAt(track.id, stay.boundaryTs)
            }
            cut = true
            refreshStats(stayTrackId, stayPoints)
            dao.setDiscarded(stayTrackId, System.currentTimeMillis(), Track.REASON_TRIMMED)
            points = keptPoints
            DebugLog.i(
                TAG,
                "track ${track.id}: trimmed ${stay.side.name.lowercase()} stay of " +
                    "${stay.stayMs / 60_000} min into discarded track $stayTrackId",
            )
        }
        return points.takeIf { cut }
    }

    /**
     * Split the edge stays off a finished track, on the user's say-so from the track screen —
     * nothing trims by itself. Returns false when there was nothing to cut. No keep re-verdict:
     * what is kept stays kept, only shorter.
     */
    suspend fun trimTrack(trackId: Long): Boolean = db.withTransaction {
        val track = dao.track(trackId) ?: return@withTransaction false
        val endedAt = track.endedAt ?: return@withTransaction false
        val remaining = trimEdgeStays(track, endedAt)
        remaining?.let { refreshStats(track.id, it) }
        // The decision has been taken either way: a cut that fired leaves nothing to review, and
        // one that found nothing was a question already answered.
        dao.setNeedsReview(track.id, false)
        remaining != null
    }

    /**
     * The cuts [trimTrack] would make on these points — and the track screen's preview of them,
     * which is why this is the *only* place the detector's params are built. The screen showing
     * one boundary while the trim used another is the failure this guards against, so neither
     * the params nor the detector call are reachable from the UI. Pure CPU over points the
     * caller already holds; no I/O.
     */
    fun edgeStays(activityTypeName: String, points: List<TrackPoint>): List<EdgeStayDetector.EdgeStay> {
        // A vehicle's floor for "not moving" is far above GPS drift; on foot there is no such
        // speed, so those tracks run without one. See EdgeStayDetector.VEHICLE.
        val params =
            if (ActivityType.ofName(activityTypeName)?.trackGroup == TrackGroup.VEHICLE) {
                EdgeStayDetector.VEHICLE
            } else {
                EdgeStayDetector.BRIEF_STOP
            }
        return EdgeStayDetector.detect(points, params, AndroidDistance)
    }

    /**
     * Re-derive every kept track's review mark against the current rule. Unlike the one-shot
     * backfills described in CLAUDE.md this is *standing* infrastructure, deliberately: the mark
     * is a verdict, [EdgeStayDetector.RULE_VERSION] says which rule produced it, and App.onCreate
     * runs this whenever the version last swept is behind. Don't delete it once it has run —
     * the next rule change needs it.
     *
     * Read-only as far as track data goes: it writes flags and nothing else, so a crash mid-pass
     * costs only a re-run (the version is stored after, so an interrupted sweep repeats). Points
     * are loaded one track at a time — the whole history is over a million rows and must never
     * be resident at once.
     */
    suspend fun sweepReviewMarks() {
        val tracks = dao.exportTracks()
        val toMark = mutableListOf<Long>()
        val toClear = mutableListOf<Long>()
        ReviewSweepStatus.start(tracks.size)
        try {
            for ((i, track) in tracks.withIndex()) {
                // Reported every 10 tracks: the point walk is the slow part, and a state emission
                // per track would recompose the banner far faster than it can be read.
                if (i % 10 == 0) ReviewSweepStatus.advance(i)
                val cuttable = edgeStays(track.activityType, dao.pointsFor(track.id)).isNotEmpty()
                // Only the verdicts that actually moved: the rows are in hand with their stored
                // value, and rewriting all of them would put the whole table through the WAL to
                // change a handful of flags.
                if (cuttable != track.needsReview) (if (cuttable) toMark else toClear) += track.id
            }
        } finally {
            ReviewSweepStatus.finish()
        }
        // Both directions, not just the marks to set: a rule that has moved leaves stale badges
        // on tracks it no longer flags, and a sweep that only ever set them would never clear
        // those. A track already trimmed under an older rule is flagged again if the new one
        // finds something — that is a new question, not a repeat of the answered one.
        //
        // One transaction for the lot: `tracks` is the observed table, so each committed chunk
        // would re-run the timeline's queries and the derivation behind them. Chunked because
        // SQLite binds at most 999 variables per statement.
        db.withTransaction {
            toMark.chunked(500).forEach { dao.setNeedsReview(it, true) }
            toClear.chunked(500).forEach { dao.setNeedsReview(it, false) }
        }
        DebugLog.i(
            TAG,
            "review sweep (rule v${EdgeStayDetector.RULE_VERSION}) over ${tracks.size} tracks: " +
                "marked ${toMark.size}, cleared ${toClear.size}",
        )
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

    /** Bring a discarded track back to the timeline (undoes a delete/discard within retention).
     *  A restored trimmed stay meets its origin track at the cut; the zero-length seam derives
     *  as a stay carrying the merge offer that stitches the pair back together. */
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

    /** Finished, kept tracks oldest-first — the backup export's track set. */
    suspend fun exportTracks(): List<Track> = dao.exportTracks()

    suspend fun pointsFor(trackId: Long): List<TrackPoint> = dao.pointsFor(trackId)

    /** Every point of a track, ignored ones included — the backup export's per-track load. */
    suspend fun allPointsFor(trackId: Long): List<TrackPoint> = dao.allPointsFor(trackId)

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
            // relies on this being the one point walk either way. The pending-cut mark is derived
            // from the same points, so it rides along rather than re-reading them.
            val points = dao.allPointsFor(trackId)
            refreshStats(trackId, points)
            dao.track(trackId)?.let {
                dao.setNeedsReview(trackId, edgeStays(it.activityType, points).isNotEmpty())
            }
        }
        return dropped
    }

}
