package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.SegmentBreaks
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSplit
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow

private const val TAG = "Breadcrumb"

/** How long soft-deleted tracks stay restorable in Recently deleted before being purged. */
const val DISCARDED_RETENTION_DAYS = 14

/** Safety bound on leading-stray removal per track (real runs are 1, rarely 2). */
private const val MAX_LEADING_STRAYS_DROPPED = 5

/** Tracks per transaction in the edge-stay sweep — see [TrackRepository.sweepEdgeStays]. */
private const val SWEEP_BATCH_TRACKS = 100

/** Point ids per `WHERE id IN (…)` statement: SQLite binds at most 999 variables per statement. */
private const val POINT_ID_CHUNK = 500

/**
 * A track's points split the three ways the track screen draws them — see
 * [TrackRepository.trackPointsFor], which is the only thing that builds one.
 */
class TrackPoints(
    /** The path itself: the fixes that are part of the journey. */
    val good: List<TrackPoint>,
    /** Bad fixes the recorder rejected ([TrackQuality]), drawn as markers with a legend. */
    val noisy: List<TrackPoint>,
    /** The recorder's overrun at the track's edges, grayed off the ends of the path. */
    val edgeStay: List<TrackPoint>,
)

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
        dao.insertTrack(
            Track(
                activityType = activityType.name,
                startedAt = startedAt,
                source = TrackOrigin.RECORDED.code,
            ),
        )

    suspend fun addPoints(points: List<TrackPoint>) = dao.insertPoints(points)

    class GpxImportCounts(val imported: Int, val duplicates: Int, val overlapping: Int)

    /**
     * Inserts parsed GPX tracks; keep thresholds do NOT apply — an explicit import is kept as-is. A
     * track already holding fixes at both ends of the file's span is skipped as a duplicate
     * (re-importing the same file, or our own export back), one merely intersecting an existing span
     * as overlapping: two paths over one period double-count its stats and leave stay derivation
     * reconciling parallel journeys. Each track inserts in its own transaction, so later tracks in a
     * file are checked against the ones before them.
     */
    suspend fun importTracks(tracks: List<GpxParser.ImportableTrack>): GpxImportCounts {
        var imported = 0
        var duplicates = 0
        var overlapping = 0
        for (track in tracks) {
            if (dao.countTracksSpanning(track.startedAt, track.endedAt) > 0) {
                duplicates++
                continue
            }
            if (dao.countTracksOverlapping(track.startedAt, track.endedAt) > 0) {
                overlapping++
                continue
            }
            val trackId = db.withTransaction {
                val id = dao.insertTrack(
                    Track(
                        activityType = track.activityTypeName,
                        startedAt = track.startedAt,
                        endedAt = track.endedAt,
                        source = TrackOrigin.IMPORTED.code,
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
            finalizeImportedTrack(trackId)
            imported++
        }
        if (tracks.isNotEmpty()) {
            DebugLog.i(
                TAG,
                "gpx import: $imported tracks added, $duplicates duplicates skipped, " +
                    "$overlapping overlapping skipped",
            )
        }
        return GpxImportCounts(imported, duplicates, overlapping)
    }

    /**
     * Inserts a batch of backup tracks, points and all, under fresh ids in one transaction, so a
     * 3000-track restore commits (and wakes the observed timeline queries) dozens of times, not
     * thousands. Aggregates come from the file unless the edge-stay plan below moves a point —
     * [refreshStats] wrote them over these same points before the export. No keep thresholds, no
     * duplicate check: restore targets an empty app (the UI only offers it there).
     */
    suspend fun insertBackupTracks(batch: List<Pair<Track, List<TrackPoint>>>) {
        db.withTransaction {
            for ((track, points) in batch) {
                // Which fixes are the recorder's overrun is this code's verdict, not a property of
                // the track — the rule lives here, not in the file, and a file written by an older
                // rule (or none) would restore as-is until the next version bump swept it; so the
                // plan is re-derived off the in-memory points and applied before they are stored.
                // The aggregates — the opposite case, a fixed function of the points — must follow
                // the flags, so they are recomputed here too. The plan names points by position, the
                // only handle a restore has: the backup format stores no point ids, so every parsed
                // point carries id 0.
                val plan = EdgeStayIgnore.plan(
                    points = points,
                    startedAt = track.startedAt,
                    endedAt = track.endedAt ?: track.startedAt,
                    params = EdgeStayDetector.paramsFor(track.activityType),
                    distance = AndroidDistance,
                )
                if (!plan.movesPoints) {
                    // The file already agrees with the current rule — the common case, and the
                    // one where its aggregates are exactly what a recompute would produce.
                    val id = dao.insertTrack(track.copy(id = 0))
                    dao.insertPoints(points.map { it.copy(id = 0, trackId = id) })
                    continue
                }
                val applied = EdgeStayIgnore.applied(points, plan)
                val stats = TrackStats.of(applied)
                val id = dao.insertTrack(
                    track.copy(
                        id = 0,
                        startedAt = plan.startedAt,
                        endedAt = if (track.endedAt == null) null else plan.endedAt,
                        distanceMeters = stats.distanceMeters,
                        pointCount = stats.pointCount,
                        ignoredCount = stats.ignoredCount,
                        startLat = stats.startLat,
                        startLon = stats.startLon,
                        endLat = stats.endLat,
                        endLon = stats.endLon,
                    ),
                )
                dao.insertPoints(applied.map { it.copy(id = 0, trackId = id) })
            }
        }
    }

    /**
     * Reassign a finished track's activity (misdetected, or an imported GPX without a type). The
     * activity feeds two stored verdicts, both re-derived here rather than left for a sweep that may
     * be releases away: it chooses the overrun detector's tuning ([EdgeStayDetector.paramsFor]), so
     * a reassignment across the foot/vehicle line would leave the stored overrun as the *other* rule
     * found it; and it sets the jump ceiling ([TrackQuality.jumpCeilingKmh]), so a drive Activity
     * Recognition took for walking arrives judged against 12 km/h, most of its path rejected as
     * teleports — correcting the activity says that ceiling was wrong, and
     * [TrackQuality.jumpRestores] hands those fixes back, only ever back, never the reverse. Both
     * questions are asked of the derived values rather than of the group, so a third set of params
     * or ceiling needs no edit here; when neither moved, the retype is the plain column write it
     * always was. An open track is left to the recorder, which is still gating its fixes on the
     * activity it detects and settles the edges when it finishes.
     */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) {
        val track = dao.track(trackId) ?: return
        val retuned =
            EdgeStayDetector.paramsFor(activityType.name) != EdgeStayDetector.paramsFor(track.activityType)
        // An unreadable stored activity has no ceiling to compare against, so nothing is withdrawn.
        val wasType = ActivityType.ofName(track.activityType)
        val raised = wasType != null &&
            TrackQuality.jumpCeilingKmh(activityType) > TrackQuality.jumpCeilingKmh(wasType)
        db.withTransaction {
            dao.setActivityType(trackId, activityType.name)
            val endedAt = track.endedAt ?: return@withTransaction
            if (!retuned && !raised) return@withTransaction
            val retyped = track.copy(activityType = activityType.name)
            val stored = dao.allPointsFor(trackId)
            val restored = if (raised) restoreJumps(trackId, stored, activityType) else null
            // Run unconditionally once either rule is in play: restoring a fix moves the first or
            // last *good* point, which is where the overrun rule takes its bearings from.
            val applied = applyEdgeStays(retyped, endedAt, restored ?: stored)
            if (restored != null || applied.changed) refreshStats(trackId, applied.points)
        }
    }

    /**
     * Hand back the jump-flagged fixes the retyped activity's ceiling accepts ([TrackQuality.jumpRestores]),
     * and return the points as their rows now read — or null when the ceiling accepted none, which
     * is how the caller knows nothing moved. The caller supplies the transaction.
     */
    private suspend fun restoreJumps(
        trackId: Long,
        points: List<TrackPoint>,
        activityType: ActivityType,
    ): List<TrackPoint>? {
        val restores = TrackQuality.jumpRestores(points, activityType, AndroidDistance)
        if (restores.isEmpty()) return null
        restores.map { points[it].id }.chunked(POINT_ID_CHUNK).forEach { dao.clearIgnored(it) }
        DebugLog.i(
            TAG,
            "track $trackId: ${restores.size} jump fixes restored under the " +
                "${activityType.name.lowercase()} ceiling",
        )
        return points.mapIndexed { i, p ->
            if (i in restores) p.copy(ignored = false, ignoreReason = null) else p
        }
    }

    /**
     * Recompute a track's aggregates from its points and store them on its row — the only writer of
     * the denormalized columns, so every path that changes a track's points (finish, merge, import,
     * retype, re-derived overrun) must end here or the timeline shows stale counts. Returns the
     * stats it wrote. [points] is *all* of the track's points, ignored ones included, from the
     * caller: every path that ends here has just walked or rewritten them, and none may re-read.
     */
    private suspend fun refreshStats(trackId: Long, points: List<TrackPoint>): TrackStats.Stats {
        val stats = TrackStats.of(points)
        dao.updateStats(stats.toUpdate(trackId))
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
     * Closes a track, soft-deleting it instead if too short to be meaningful. Discarded tracks keep
     * their rows/points (excluded from the UI, stats, stays, and export) so the keep-thresholds can
     * be tuned against real data — except a track of [KeepRule.PURGE_MAX_POINTS] or fewer points in
     * total (good + ignored), hard-deleted outright: empty of information, nothing to review either.
     */
    private suspend fun closeOrDelete(track: Track, endedAt: Long, renameTo: ActivityType? = null) = db.withTransaction {
        // The carrier rename runs first, inside the finish transaction, so everything after — jump
        // restores, edge stays, stats, keep verdict — judges the track the witness proved, not the
        // label detection guessed. Which labels rename, and to what, is the domain's decision
        // (CarrierEvidence.renameFor); this only applies it. A later manual retype is the last word, as
        // everywhere, and this runs before the track is ever user-visible as finished, so the two cannot fight.
        val rename = renameTo?.takeIf { it.name != track.activityType }
        if (rename != null) {
            dao.setActivityType(track.id, rename.name)
            DebugLog.i(TAG, "track ${track.id}: carrier evidence proven — finishing as ${rename.name}")
        }
        val closing = if (rename != null) track.copy(activityType = rename.name) else track
        val stored = dao.allPointsFor(track.id)
        // The rename target's ceiling outranks the foot label's by construction, so the warm-up
        // fixes rejected before the confirmer had evidence come back here.
        val points = if (rename != null) restoreJumps(track.id, stored, rename) ?: stored else stored
        // Finishing is where the track's aggregates are computed for the first time — the recorder
        // writes none of them while it records — and where the recorder's overrun is taken off the
        // path. The overrun comes off *before* the keep verdict deliberately: a track is judged on
        // the journey it recorded, not on the minutes it spent parked at the end of it.
        val applied = applyEdgeStays(closing, endedAt, points)
        val stats = refreshStats(track.id, applied.points)
        when (keepVerdict(track, applied.startedAt, applied.endedAt, stats)) {
            KeepRule.Verdict.KEEP -> dao.closeTrack(track.id, applied.endedAt)
            KeepRule.Verdict.DISCARD -> dao.discardTrack(
                track.id,
                endedAt = applied.endedAt,
                discardedAt = endedAt,
                reason = Track.REASON_FILTERED,
            )
            KeepRule.Verdict.PURGE -> dao.purgeTrack(track.id)
        }
    }

    /** A track's points and bounds as [applyEdgeStays] left them. */
    private class Applied(
        val points: List<TrackPoint>,
        val startedAt: Long,
        val endedAt: Long,
        /** Whether the rule moved anything — a flag or either bound. False on the re-runs that
         *  agree with the stored rows, which is what lets a re-sweep cost no writes. */
        val changed: Boolean,
    )

    /**
     * Take the recorder's overrun off this track's path: the stay's fixes are flagged
     * [IgnoreReason.EDGE_STAY] and the track's clock pulled in to the boundary fix, so the journey
     * ends where it ended rather than where Activity Recognition noticed ([EdgeStayDetector]:
     * position decides *whether*, speed collapse *where*; [EdgeStayIgnore]: what that means for the
     * rows). Nothing is destroyed — the points stay, and a rule that later withdraws a stay hands
     * them straight back — and it is idempotent, which lets every path that changes a track's points
     * end here. The point flags and both bounds are written, except on a still-open row ([endedAt]
     * is then the proposed end time), where the caller is mid-finish and writes the end itself. The
     * stats are the caller's to recompute from the returned points; callers wrap the sequence in one
     * transaction.
     */
    private suspend fun applyEdgeStays(track: Track, endedAt: Long, points: List<TrackPoint>): Applied {
        val plan = EdgeStayIgnore.plan(
            points = points,
            startedAt = track.startedAt,
            endedAt = endedAt,
            params = EdgeStayDetector.paramsFor(track.activityType),
            distance = AndroidDistance,
        )
        // The plan names points by position; these ones came out of the database, so each has a
        // row id to write against.
        plan.ignore.map { points[it].id }.chunked(POINT_ID_CHUNK)
            .forEach { dao.setIgnored(it, IgnoreReason.EDGE_STAY.code) }
        plan.restore.map { points[it].id }.chunked(POINT_ID_CHUNK).forEach { dao.clearIgnored(it) }
        if (plan.startedAt != track.startedAt) dao.setStartedAt(track.id, plan.startedAt)
        if (track.endedAt != null && plan.endedAt != endedAt) dao.closeTrack(track.id, plan.endedAt)
        if (plan.movesPoints) {
            val what = plan.stays
                .joinToString { "${it.side.name.lowercase()} overrun of ${it.stayMs / 1000}s" }
                .ifEmpty { "no overrun" }
            DebugLog.i(
                TAG,
                "track ${track.id}: $what " +
                    "(${plan.ignore.size} points ignored, ${plan.restore.size} restored)",
            )
        }
        return Applied(
            points = EdgeStayIgnore.applied(points, plan),
            startedAt = plan.startedAt,
            endedAt = plan.endedAt,
            changed = plan.movesPoints ||
                plan.startedAt != track.startedAt ||
                plan.endedAt != endedAt,
        )
    }

    /**
     * Re-derive one finished track's overrun and, when anything moved, the aggregates that follow —
     * the whole of what a stored track needs when the rule, or the tuning its activity selects, has
     * changed under it. Returns whether it wrote anything. An open track is skipped (the recorder is
     * still adding to the edge the rule would cut; finishing runs this itself). The caller supplies
     * the transaction and passes [track] as the row should now read — a retype hands in the new
     * activity, since the tuning derives from it.
     */
    private suspend fun rederiveEdgeStays(track: Track): Boolean {
        val endedAt = track.endedAt ?: return false
        val applied = applyEdgeStays(track, endedAt, dao.allPointsFor(track.id))
        if (!applied.changed) return false
        refreshStats(track.id, applied.points)
        return true
    }

    /**
     * Re-derive every kept track's overrun against the current rule. Unlike CLAUDE.md's one-shot
     * backfills this is *standing* infrastructure, deliberately: the ignored fixes are a verdict,
     * [EdgeStayDetector.RULE_VERSION] says which rule produced it, and App.onCreate runs this
     * whenever the version last swept is behind — don't delete it once it has run; the next rule
     * change needs it. Self-correcting in both directions, since the plan comes from the raw
     * recording: a rule that now finds less hands the points back, one that finds more takes them,
     * and a crash mid-pass costs only a re-run (the version is stored after). Points are loaded one
     * track at a time — the whole history is over a million rows and must never be resident at once
     * — and tracks commit in batches: `tracks` is observed, so a commit per track would re-run the
     * timeline's queries and the derivation behind them thousands of times, while one transaction
     * for the lot would hold every rewritten point row in the journal at once.
     */
    suspend fun sweepEdgeStays() {
        val tracks = dao.exportTracks()
        val changed = sweep(SweepStatus.Kind.EDGE_STAYS, tracks) { rederiveEdgeStays(it) }
        DebugLog.i(
            TAG,
            "edge-stay sweep (rule v${EdgeStayDetector.RULE_VERSION}) over ${tracks.size} " +
                "tracks: $changed rewritten",
        )
    }

    /**
     * The walk both sweeps share: [rederive] runs per item and returns whether it wrote anything
     * (only the count is reported). The batching and progress cadence are why this is one function —
     * [sweepEdgeStays] says why each is shaped as it is. A sweep that agrees with the stored rows
     * must cost no writes, so a `rederive` decides for itself whether there is anything to store.
     */
    private suspend fun <T> sweep(
        kind: SweepStatus.Kind,
        items: List<T>,
        rederive: suspend (T) -> Boolean,
    ): Int {
        var changed = 0
        SweepStatus.start(kind, items.size)
        try {
            for ((batch, chunk) in items.chunked(SWEEP_BATCH_TRACKS).withIndex()) {
                db.withTransaction {
                    for ((i, item) in chunk.withIndex()) {
                        // Reported every 10 items: the point walk is the slow part, and a state
                        // emission per item would recompose the banner faster than it can be read.
                        if (i % 10 == 0) SweepStatus.advance(batch * SWEEP_BATCH_TRACKS + i)
                        if (rederive(item)) changed++
                    }
                }
            }
        } finally {
            SweepStatus.finish()
        }
        return changed
    }

    /**
     * Re-walk every finished track's points and rewrite its aggregates — the standing answer to
     * [TrackStats.RULE_VERSION] moving, in the shape [sweepEdgeStays] uses for its own rule. A stored
     * total is the output of a walk that has since changed, and nothing re-walks a track whose points
     * sat still: the edge-stay sweep skips it, and a track is otherwise re-walked only when finished,
     * merged, imported or retyped. Every track is swept, not just the ones a particular change
     * touched — which tracks a *future* change reaches isn't knowable here, and a walk that skipped
     * some would quietly leave them behind. Discarded tracks are included, unlike the edge-stay
     * sweep's set (Recently deleted shows a distance, and restoring brings the row back as it
     * stands); open tracks are skipped — the recorder owns those columns until it finishes, where
     * the same walk runs anyway. Idempotent: it recomputes from the points, and the version is
     * stored after, so an interrupted sweep costs a re-run. Points load one track at a time and
     * commit in batches, for the reasons on [sweepEdgeStays] — and a track whose stored columns
     * already agree is left alone: `tracks` is observed, so a needless UPDATE per track re-runs the
     * timeline.
     */
    suspend fun sweepStats() {
        val tracks = dao.finishedTracks()
        val changed = sweep(SweepStatus.Kind.STATS, tracks) { track ->
            val stats = TrackStats.of(dao.allPointsFor(track.id))
            if (stats.matches(track)) {
                false
            } else {
                dao.updateStats(stats.toUpdate(track.id))
                true
            }
        }
        DebugLog.i(
            TAG,
            "stats sweep (rule v${TrackStats.RULE_VERSION}) over ${tracks.size} " +
                "tracks: $changed rewritten",
        )
    }

    /**
     * [renameTo] is the carrier-evidence verdict, decided in the domain (`CarrierEvidence.renameFor`:
     * a proven carried journey on a foot label renames to UNKNOWN, "Moving") — this layer only
     * applies it, inside the finish transaction, with the warm-up jump flags restored under the new
     * label's ceiling. Null, the default and what every evidence-less path passes, leaves the finish
     * untouched by the evidence channel.
     */
    suspend fun finishTrack(trackId: Long, endedAt: Long, renameTo: ActivityType? = null) {
        val track = dao.track(trackId) ?: return
        closeOrDelete(track, endedAt, renameTo)
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
     * Close the short stay between [earlierId] and [laterId]: a NEW track spans both (points copied
     * in order, a segment break marking the join) and the originals move to discarded — reviewable
     * and restorable from Settings → Recently deleted until the retention purge, not destroyed. The
     * derived stay disappears because the discarded originals leave the timeline. Returns the merged
     * track's id (null if either original is gone), which [unmergeTracks] needs to undo it.
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
                    source = earlier.source, // likewise — TrackMerge refuses across writers
                ),
            )
            dao.copyPointsInto(mergedId, earlierId)
            dao.copyPointsInto(mergedId, laterId)
            dao.firstPointAtOrAfter(mergedId, later.startedAt)?.let { dao.markSegmentStart(it) }
            // The originals' flags come along with their points, and the run through the rule below
            // settles them: the outer edges are re-derived, and the earlier track's overrun — now
            // buried mid-track, where no edge rule reaches it — is handed back to the path, which
            // is the merged track's own way through the stop it drove on from.
            val merged = dao.track(mergedId)!!
            val applied = applyEdgeStays(merged, merged.endedAt!!, dao.allPointsFor(mergedId))
            // Recomputed, not summed: the merged track is one journey, so the ground between the
            // two halves counts like any other leg — a sum of the originals would leave it out.
            // It also keeps the one writer of the denormalized columns in charge.
            refreshStats(mergedId, applied.points)
            val now = System.currentTimeMillis()
            dao.setDiscarded(earlierId, now, Track.REASON_MERGED)
            dao.setDiscarded(laterId, now, Track.REASON_MERGED)
            mergedId
        }
    }

    /** What a [splitTrack] did, and all [unsplitTracks] needs to take it back. */
    data class Split(
        /** The new track that took everything from the cut onwards. The first half is the original
         *  row, which keeps its id. */
        val secondId: Long,
        /**
         * The original's end before the cut moved it. Carried because it is the one thing undoing a
         * split cannot re-derive: a track's `endedAt` is the moment the recorder stopped, which sits
         * a few seconds past its last fix, and nothing stores that gap.
         */
        val originalEndedAt: Long,
    )

    /**
     * Cut one track in two at [atTs] — the user's own split, for a journey the recorder never broke.
     * The original row *becomes* the first half, keeping its id and start with its end pulled back to
     * the cut; everything from [atTs] onwards is rehomed onto one new track — *reassigned*, not
     * copied, deliberately unlike [mergeTracks]. A merge must copy and leave its originals in
     * Recently deleted (two rows become one; the originals are the only record of the pair), but a
     * split's fixes all survive on one row or the other, so a copied original would be a redundant
     * third row over a period two live tracks already cover — and restoring it (offered for
     * everything on that screen) would lay a duplicate journey over both halves. Nothing is
     * discarded, and [unsplitTracks] hands the fixes straight back.
     *
     * Two things deliberately don't run. The keep verdict: the user chose this cut, so a half below
     * the thresholds stays on the timeline rather than being silently filed in Recently deleted,
     * undoing half the action behind their back — only the floor below is enforced, since a
     * single-point half is not a track anyone can look at. And marking a segment break: a break says
     * the recorder wasn't watching across a leg ([SegmentBreaks]), but here there is no leg — the
     * fixes either side of the cut land on different tracks, and that inter-track gap is exactly
     * what puts the stop on the timeline. Both halves do go through the overrun rule, so a cut at a
     * stop comes out right: each half's new inner edge runs into the stop and its fixes are flagged
     * [IgnoreReason.EDGE_STAY] there rather than dragging the line across the parked minutes —
     * asymmetrically, as [EdgeStayDetector] finds a stay of about a minute at a track's end but
     * needs several times that at its start, so a cut through a brief stop comes off the arrival
     * side while the departure side keeps its parked head on the path. Returns null — writing
     * nothing — if the track is gone, still recording, or [TrackSplit] refuses the cut.
     */
    suspend fun splitTrack(trackId: Long, atTs: Long): Split? {
        return db.withTransaction {
            val track = dao.track(trackId) ?: return@withTransaction null
            // An open track is still growing the edge the rule would cut; finish it first.
            val endedAt = track.endedAt ?: return@withTransaction null
            val points = dao.allPointsFor(trackId)
            val plan = TrackSplit.plan(points, atTs) ?: return@withTransaction null
            val (before, after) = points.partition { it.timestamp < atTs }

            // Each half keeps the original's outer bound and takes the raw fix at the cut as its
            // inner one; applyEdgeStays below pulls that in wherever it finds an overrun.
            // Built once and re-read below with its id: a second description of the same row could
            // disagree with this one, and `track.copy` would quietly carry the first half's
            // aggregates onto it — numbers that are not this row's and that nothing would contradict.
            val secondRow = Track(
                activityType = track.activityType,
                startedAt = plan.secondStartTs,
                endedAt = endedAt,
                // The half is the same recording, cut: a split never introduces a writer.
                source = track.source,
            )
            val secondId = dao.insertTrack(secondRow)
            dao.movePointsFrom(secondId, trackId, atTs)
            dao.closeTrack(trackId, plan.firstEndTs)
            // The points are the lists already in hand: the move rewrote one column and left every
            // row id, timestamp and flag alone, so re-reading them would buy nothing (and
            // refreshStats requires the caller's walk, not a fresh read).
            val first = track.copy(endedAt = plan.firstEndTs)
            val second = secondRow.copy(id = secondId)
            // Recomputed per half, not divided: each is its own journey now, and this keeps the one
            // writer of the denormalized columns in charge.
            refreshStats(trackId, applyEdgeStays(first, plan.firstEndTs, before).points)
            refreshStats(secondId, applyEdgeStays(second, endedAt, after).points)
            DebugLog.i(
                TAG,
                "track $trackId split at $atTs: kept ${before.size} points, " +
                    "moved ${after.size} to new track $secondId",
            )
            Split(secondId = secondId, originalEndedAt = endedAt)
        }
    }

    /**
     * Undo a [splitTrack]: the second half's fixes go back onto [originalId], its now-empty row is
     * dropped, and the reunited track is re-derived — an exact inverse, since the overrun rule reads
     * the raw recording rather than its own output, so the pre-cut flags and bounds come back. Only
     * the recorder's stop time can't be re-derived; [Split.originalEndedAt] carries it.
     */
    suspend fun unsplitTracks(originalId: Long, split: Split) {
        db.withTransaction {
            val original = dao.track(originalId) ?: return@withTransaction
            // Points first: purging the row while they still hang off it would cascade them away.
            dao.movePointsFrom(originalId, split.secondId, Long.MIN_VALUE)
            dao.purgeTrack(split.secondId)
            dao.closeTrack(originalId, split.originalEndedAt)
            val applied = applyEdgeStays(original, split.originalEndedAt, dao.allPointsFor(originalId))
            refreshStats(originalId, applied.points)
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

    /**
     * A track's path: the good fixes, with any segment break stranded on an ignored one carried
     * onto the fix that resumes ([SegmentBreaks]). Loads every row to find those breaks, which the
     * good-only query can't see — a per-track read, not the recorder's hot path.
     */
    suspend fun pointsFor(trackId: Long): List<TrackPoint> =
        SegmentBreaks.goodWithCarriedBreaks(dao.allPointsFor(trackId))

    /** Every point of a track, ignored ones included — the backup export's per-track load. */
    suspend fun allPointsFor(trackId: Long): List<TrackPoint> = dao.allPointsFor(trackId)

    /** Usable points inserted after [afterId] — the live preview's incremental reload. */
    suspend fun pointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        dao.pointsAfter(trackId, afterId)

    /**
     * One load of a track's points, split rather than queried three times: the slices are disjoint
     * parts of one ordered list, and separate reads would let a re-derivation land between two — the
     * new path drawn against the old overrun. Which slice a reason belongs to is decided here, not in
     * the screen; [EdgeStayIgnore] owns the one that isn't a rejection.
     */
    suspend fun trackPointsFor(trackId: Long): TrackPoints {
        val all = dao.allPointsFor(trackId)
        val ignored = all.filter { it.ignored }
        val (edgeStay, noisy) = ignored.partition(EdgeStayIgnore::isEdgeStay)
        return TrackPoints(
            good = SegmentBreaks.goodWithCarriedBreaks(all),
            noisy = noisy,
            edgeStay = edgeStay,
        )
    }

    /**
     * The import's whole finalize, bringing the track to the state a recorded one finishes in: imports
     * bypass live ingest filtering, so each stray leading point ([TrackQuality.leadingPointIsJump] —
     * the drive-start cold-start artifact) is flagged an ignored JUMP fix, looping while strays lead;
     * then the overrun comes off the edges and the missing aggregates are computed. Returns the dropped count.
     */
    suspend fun finalizeImportedTrack(trackId: Long): Int {
        var dropped = 0
        db.withTransaction {
            // Bounded: each pass ignores one leading point, so a handful covers any real run of
            // strays. The check reads only the leading prefix, so the loop is cheap.
            while (dropped < MAX_LEADING_STRAYS_DROPPED) {
                val head = dao.firstPointsFor(trackId, TrackQuality.LEADING_CHECK_POINT_COUNT)
                if (!TrackQuality.leadingPointIsJump(head)) break
                dao.setIgnored(head.first().id, IgnoreReason.JUMP.code)
                dropped++
            }
            // Unconditional, and inside the transaction: the imported rows have no aggregates yet,
            // so this runs whether or not a stray was dropped, and is the one point walk either
            // way. The overrun is derived from the same points, so it rides along rather than
            // re-reading them.
            val track = dao.track(trackId)
            val points = dao.allPointsFor(trackId)
            if (track?.endedAt == null) {
                // Still open: its edges aren't settled yet, and finishing it applies the rule.
                refreshStats(trackId, points)
            } else {
                refreshStats(trackId, applyEdgeStays(track, track.endedAt, points).points)
            }
        }
        return dropped
    }
}
