package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Row-shaped projection of [io.github.valeronm.breadcrumb.data.TrackStats.Stats] for
 * [TrackDao.updateStats]: the aggregate columns plus the key, nothing else touched.
 */
data class TrackStatsUpdate(
    val id: Long,
    val distanceMeters: Double,
    val pointCount: Int,
    val ignoredCount: Int,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
)

/** Excludes no row from the overlap checks — no track has this id ([TrackDao.countTracksSpanning]). */
const val NO_TRACK = 0L

/** Ids per `WHERE … IN (…)` statement: SQLite binds at most 999 variables per statement. */
const val IDS_PER_STATEMENT = 500

@Dao
interface TrackDao {

    @Insert
    suspend fun insertTrack(track: Track): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPoint>)

    @Query("UPDATE tracks SET endedAt = :endedAt WHERE id = :trackId")
    suspend fun closeTrack(trackId: Long, endedAt: Long)

    /**
     * Write a track's aggregates ([io.github.valeronm.breadcrumb.data.TrackStats]) onto its row —
     * on finish, merge, split, import, retype or overrun re-derivation, never per fix: the
     * observed queries below read `tracks`, so a per-fix write here would wake them all.
     */
    @Update(entity = Track::class)
    suspend fun updateStats(stats: TrackStatsUpdate)

    @Query("UPDATE tracks SET activityType = :activityType WHERE id = :trackId")
    suspend fun setActivityType(trackId: Long, activityType: String)

    /**
     * Restate a typed-in track: what it was and when it ran, in one write. Its points are replaced
     * in the same transaction ([deletePointsFor]) — the bounds and the two fixes are one statement
     * about a manual track, and a row whose bounds outran its points would be widened back by the
     * next edge-stay sweep.
     */
    @Query(
        "UPDATE tracks SET activityType = :activityType, startedAt = :startedAt, endedAt = :endedAt " +
            "WHERE id = :trackId",
    )
    suspend fun setManualTrack(trackId: Long, activityType: String, startedAt: Long, endedAt: Long)

    /** Drop every fix of a track, the row itself staying — a manual track's two points on the way
     *  to being replaced by the two the user just typed. */
    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deletePointsFor(trackId: Long)

    /** Soft-delete a keep-threshold-filtered track: finalise it and mark it discarded. */
    @Query(
        "UPDATE tracks SET endedAt = :endedAt, discardedAt = :discardedAt, discardReason = :reason " +
            "WHERE id = :trackId",
    )
    suspend fun discardTrack(trackId: Long, endedAt: Long, discardedAt: Long, reason: String)

    /** Bring a discarded track back to the timeline. */
    @Query("UPDATE tracks SET discardedAt = NULL, discardReason = NULL WHERE id = :trackId")
    suspend fun restoreTrack(trackId: Long)

    /** Hard-delete soft-deleted tracks discarded before [cutoff] (points cascade). Returns the count. */
    @Query("DELETE FROM tracks WHERE discardedAt IS NOT NULL AND discardedAt < :cutoff")
    suspend fun purgeDiscardedBefore(cutoff: Long): Int

    /** Hard-delete every soft-deleted track now — the Recently deleted screen's "clear all". */
    @Query("DELETE FROM tracks WHERE discardedAt IS NOT NULL")
    suspend fun purgeAllDiscarded(): Int

    // --- Track merge and split (one copies points onto a new track, the other rehomes them) -----

    /** Copy every point of [srcId] onto [newId] (the merged track keeps its own copy). */
    @Query(
        """
        INSERT INTO track_points
            (trackId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
             verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0,
             ignored, ignoreReason, segmentStart)
        SELECT :newId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
               verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0,
               ignored, ignoreReason, segmentStart
        FROM track_points WHERE trackId = :srcId
        """,
    )
    suspend fun copyPointsInto(newId: Long, srcId: Long)

    /**
     * Hand every point of [srcId] at or after [fromTs] to [newId] — how a split rehomes its second
     * half and, with [fromTs] at the bottom of the range, how undoing one hands it back. Rows are
     * *reassigned*, not copied — a fix keeps its id and changes owner — so a split duplicates
     * nothing and leaves nothing to purge; [copyPointsInto] copies because a merge's originals
     * stay reviewable in Recently deleted, while a split's first half *is* the original row.
     */
    @Query("UPDATE track_points SET trackId = :newId WHERE trackId = :srcId AND timestamp >= :fromTs")
    suspend fun movePointsFrom(newId: Long, srcId: Long, fromTs: Long)

    /** The merged track's first point at/after [timestamp] — marked as the segment break at the join. */
    @Query("SELECT id FROM track_points WHERE trackId = :trackId AND timestamp >= :timestamp ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun firstPointAtOrAfter(trackId: Long, timestamp: Long): Long?

    @Query("UPDATE track_points SET segmentStart = 1 WHERE id = :pointId")
    suspend fun markSegmentStart(pointId: Long)

    @Query("UPDATE tracks SET discardedAt = :discardedAt, discardReason = :reason WHERE id = :trackId")
    suspend fun setDiscarded(trackId: Long, discardedAt: Long, reason: String)

    @Query("UPDATE tracks SET startedAt = :startedAt WHERE id = :trackId")
    suspend fun setStartedAt(trackId: Long, startedAt: Long)

    /**
     * Hard-delete one track (points cascade). For rows with nothing to review — undoing a merge
     * drops the track the merge created, and a finish with too few points to render skips Recently
     * deleted. A user delete is the soft one ([setDiscarded]).
     */
    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun purgeTrack(trackId: Long)

    /** The first [limit] usable points — enough to check for a stray leading point. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun firstPointsFor(trackId: Long, limit: Int): List<TrackPoint>

    /** Every point of a track, ignored ones included — the input to a [TrackStats] recompute. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC, id ASC")
    suspend fun allPointsFor(trackId: Long): List<TrackPoint>

    /** Every point of several tracks at once, in track then time order. Callers must chunk by
     *  [IDS_PER_STATEMENT]. */
    @Query("SELECT * FROM track_points WHERE trackId IN (:trackIds) ORDER BY trackId, timestamp ASC, id ASC")
    suspend fun pointsForTracks(trackIds: List<Long>): List<TrackPoint>

    /** Usable points inserted after [afterId] — the live preview's incremental reload. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 AND id > :afterId ORDER BY timestamp ASC, id ASC")
    suspend fun pointsAfter(trackId: Long, afterId: Long): List<TrackPoint>

    /** Flag one point as an ignored bad fix, with the reason. */
    @Query("UPDATE track_points SET ignored = 1, ignoreReason = :reason WHERE id = :pointId")
    suspend fun setIgnored(pointId: Long, reason: String)

    /** As above for a whole set at once. Callers must chunk by [IDS_PER_STATEMENT]. */
    @Query("UPDATE track_points SET ignored = 1, ignoreReason = :reason WHERE id IN (:pointIds)")
    suspend fun setIgnored(pointIds: List<Long>, reason: String)

    /** Hand a set of points back to the track — the undo of [setIgnored], used when a moved rule
     *  withdraws an edge stay it once found. */
    @Query("UPDATE track_points SET ignored = 0, ignoreReason = NULL WHERE id IN (:pointIds)")
    suspend fun clearIgnored(pointIds: List<Long>)

    /**
     * Duplicate check for GPX import: some track already holds fixes at both ends of the file's
     * span (its first and last point). Asked of the points, not `tracks.startedAt`/`endedAt`: the
     * bounds pull in when the recorder's overrun comes off a track's edges while its points stay,
     * so a row no longer answers to the span of the file it was imported from. One-shot, not
     * observed, so it may read `track_points` (the observed queries below may not). Soft-deleted
     * tracks are excluded, here and in [countTracksOverlapping]: Recently deleted is a holding pen
     * on the way out, not what the app has seen, so a span covered only by discarded rows imports.
     *
     * Ignored fixes count here and deliberately: this asks whether a track already *holds* the
     * file's two instants, and a file re-imported after its edges were trimmed still landed once.
     *
     * [exceptTrackId] is the row being rewritten, whose own fixes sit in the span it is moving to and
     * are what the write replaces — a track edited in place would otherwise always collide with
     * itself. Ids are positive, so an insert passes [NO_TRACK] and excludes nothing.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks t
        WHERE t.discardedAt IS NULL AND t.id != :exceptTrackId
          AND EXISTS (SELECT 1 FROM track_points p WHERE p.trackId = t.id AND p.timestamp = :startedAt)
          AND EXISTS (SELECT 1 FROM track_points p WHERE p.trackId = t.id AND p.timestamp = :endedAt)
        """,
    )
    suspend fun countTracksSpanning(startedAt: Long, endedAt: Long, exceptTrackId: Long): Int

    /**
     * Overlap check for GPX import, asked once [countTracksSpanning] rules out an exact duplicate:
     * some track's own point span intersects the file's — a second path over a period already
     * covered. Both ends compare strictly: tracks merely touching at one instant don't overlap, or
     * a file split into back-to-back legs would import its first leg and reject the rest.
     * [exceptTrackId] as above.
     *
     * **Asked of the path, so ignored fixes are not part of it** — unlike [countTracksSpanning],
     * which asks after fixes a track *holds*. A trimmed overrun sits outside the row's own bounds:
     * the edge-stay rule pulls `startedAt`/`endedAt` in to the first and last good fix and leaves
     * the ignored ones where they were, on about a third of this history's tracks. Counting them
     * would make a track overlap the very interval the timeline shows as empty beside it — and a
     * trip entered to fill that gap is timed at exactly those bounds, so it would be refused every
     * time.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks t
        WHERE t.discardedAt IS NULL AND t.id != :exceptTrackId
          AND EXISTS (
            SELECT 1 FROM track_points p
            WHERE p.trackId = t.id AND p.ignored = 0 AND p.timestamp < :endedAt
          )
          AND EXISTS (
            SELECT 1 FROM track_points p
            WHERE p.trackId = t.id AND p.ignored = 0 AND p.timestamp > :startedAt
          )
        """,
    )
    suspend fun countTracksOverlapping(startedAt: Long, endedAt: Long, exceptTrackId: Long): Int

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL")
    suspend fun openTracks(): List<Track>

    @Query("SELECT MAX(timestamp) FROM track_points WHERE trackId = :trackId")
    suspend fun lastPointTime(trackId: Long): Long?

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun track(trackId: Long): Track?

    @Query("SELECT id FROM tracks WHERE discardedAt IS NULL ORDER BY startedAt DESC")
    suspend fun allTrackIds(): List<Long>

    /** Every finished track oldest-first, discarded ones included — the stats sweep's set. Rows,
     *  not ids: the sweep compares what it computed against what each row holds. */
    @Query("SELECT * FROM tracks WHERE endedAt IS NOT NULL ORDER BY startedAt ASC")
    suspend fun finishedTracks(): List<Track>

    /** Finished, kept tracks oldest-first — the backup export's set, and the review sweep's. */
    @Query("SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL ORDER BY startedAt ASC")
    suspend fun exportTracks(): List<Track>

    // --- Observed queries -----------------------------------------------------------------------
    // These deliberately read `tracks` only: Room invalidates per table, so touching `track_points`
    // would re-run them on every fix of a live recording — a scan of the whole point history, once
    // a second, for a result that cannot have changed (an open track has no endedAt, so it's in
    // none of them); the aggregates live on the track row, written at finish ([Track], [TrackStats]).
    //
    // Each selects `*` under @RewriteQueriesToDropUnusedColumns: Room resolves the star against the
    // schema at compile time and emits SQL naming only the columns its result class reads, so the
    // transferred columns stay as narrow as a hand-written list while the result class is the single
    // place that list is declared. Hand-listing them is how a column reached one projection and not
    // its twin — a drift no compiler could see, since a missing column is a Room warning.

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL ORDER BY startedAt DESC")
    fun observeSummaries(): Flow<List<TrackSummary>>

    /** The inverse of [observeSummaries]: soft-deleted tracks (user delete, keep-threshold
     *  filter, merge originals) for the Recently deleted screen. */
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM tracks WHERE discardedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeDiscardedSummaries(): Flow<List<DiscardedSummary>>

    /** Finished tracks with first/last good-point coordinates, oldest first — the stay deriver's input. */
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL ORDER BY startedAt ASC")
    fun observeEndpoints(): Flow<List<TrackEndpoints>>

    /** [observeEndpoints] for a caller deriving once inside a transaction rather than observing. */
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL ORDER BY startedAt ASC")
    suspend fun endpointsOnce(): List<TrackEndpoints>

    /**
     * Which of [ids] are on the timeline at all — a repair asks about the tracks a change touched,
     * and the ones it discarded or purged answer by being absent rather than by a second query.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM tracks WHERE id IN (:ids) AND endedAt IS NOT NULL AND discardedAt IS NULL " +
            "ORDER BY startedAt ASC",
    )
    suspend fun endpointsFor(ids: List<Long>): List<TrackEndpoints>

    /**
     * The kept track on either side of a stretch being repaired — [excluding] holds the ids the
     * change itself touched, whose own rows must not answer as their own neighbour.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL " +
            "AND id NOT IN (:excluding) AND startedAt < :before ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun keptTrackBefore(before: Long, excluding: List<Long>): TrackEndpoints?

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM tracks WHERE endedAt IS NOT NULL AND discardedAt IS NULL " +
            "AND id NOT IN (:excluding) AND startedAt >= :after ORDER BY startedAt ASC LIMIT 1",
    )
    suspend fun keptTrackAfter(after: Long, excluding: List<Long>): TrackEndpoints?
}
