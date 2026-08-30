package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.SegmentBreaks
import io.github.valeronm.breadcrumb.domain.TrackBounds
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSplit
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow

private const val TAG = "Breadcrumb"

/** How long soft-deleted tracks stay restorable in Recently deleted before being purged. */
const val DISCARDED_RETENTION_DAYS = 14

/** Safety bound on leading-stray removal per track (real runs are 1, rarely 2). */
private const val MAX_LEADING_STRAYS_DROPPED = 5

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
 * The writers of the track tables and the reads the screens make, so callers don't touch Room
 * directly; what a written track owes its row is [TrackSettler]'s, and the versioned walks over
 * the history are [HistorySweeps]'. [db] is a seam: production passes nothing and gets the app's
 * singleton database, tests pass an in-memory one.
 */
class TrackRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val appContext = context.applicationContext
    private val dao = db.trackDao()

    // What every writer of a track's points owes its row — see [TrackSettler]; the writers below
    // decide only whether they owe the derivation a repair as well.
    private val settler = TrackSettler(dao)

    /** The standing, versioned walks over the whole history, run from `App.onCreate`. */
    internal val sweeps = HistorySweeps(db, dao, settler)

    /**
     * The stored stay/place derivation, repaired from inside the transactions that move a track's
     * endpoints. Held here rather than left to a caller because the repair is part of the write:
     * every path below that changes which tracks the timeline holds, or where one begins and ends,
     * owes the derivation either a [DerivationStore.reknit] over the ids it touched or — where the
     * change is historical and out of order — a [DerivationStore.rebuild].
     */
    private val derivation = DerivationStore(context, db)

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
     * reconciling parallel journeys. Each track inserts and finalizes in one transaction, so later
     * tracks in a file are checked against the ones before them and a crash mid-file commits no row
     * without its aggregates.
     */
    suspend fun importTracks(tracks: List<GpxParser.ImportableTrack>): GpxImportCounts {
        var imported = 0
        var duplicates = 0
        var overlapping = 0
        for (track in tracks) {
            if (dao.countTracksSpanning(track.startedAt, track.endedAt, NO_TRACK) > 0) {
                duplicates++
                continue
            }
            if (dao.countTracksOverlapping(track.startedAt, track.endedAt, NO_TRACK) > 0) {
                overlapping++
                continue
            }
            db.withTransaction {
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
                finalizeImportedTrack(id)
            }
            imported++
        }
        if (tracks.isNotEmpty()) {
            DebugLog.i(
                TAG,
                "gpx import: $imported tracks added, $duplicates duplicates skipped, " +
                    "$overlapping overlapping skipped",
            )
        }
        // A whole derivation, not a seam per track: an import lands historical tracks in whatever
        // order the file holds them, and a repair around one of them assumes the stretch it reaches
        // is otherwise settled.
        if (imported > 0) derivation.reconcile(stale = true)
        return GpxImportCounts(imported, duplicates, overlapping)
    }

    /** One typed end of a manual track — where the user says the leg began or ended, and when. */
    class ManualEnd(val at: Coordinate, val timestampMs: Long)

    sealed class ManualTrackResult {
        class Saved(val trackId: Long) : ManualTrackResult()

        /** The span intersects an existing track's fixes — refused, the same rule as a GPX
         *  overlap: two paths over one period double-count stats and leave stay derivation
         *  reconciling parallel journeys. */
        object Overlapping : ManualTrackResult()

        /** The row an edit named is gone, or was never a manual one — nothing was written. Only an
         *  edit can meet this; the screen offering one has to say so rather than close on silence. */
        object NotEditable : ManualTrackResult()
    }

    /**
     * Inserts a track the user typed in: two points, two times, nothing between. Keep thresholds
     * deliberately do NOT apply, like [importTracks] — an explicit entry is kept as-is, and the
     * two-point purge floor ([KeepRule]) would otherwise delete every manual track on arrival.
     */
    suspend fun insertManualTrack(
        activityType: ActivityType,
        origin: ManualEnd,
        destination: ManualEnd,
    ): ManualTrackResult {
        refusedSpan(origin, destination, exceptTrackId = NO_TRACK)?.let { return it }
        val trackId = db.withTransaction {
            val id = dao.insertTrack(
                Track(
                    activityType = activityType.name,
                    startedAt = origin.timestampMs,
                    endedAt = destination.timestampMs,
                    source = TrackOrigin.MANUAL.code,
                ),
            )
            dao.insertPoints(manualPoints(id, origin, destination))
            finalizeManualTrack(id)
            id
        }
        DebugLog.i(TAG, "manual track: ${activityType.name} inserted as #$trackId")
        return ManualTrackResult.Saved(trackId)
    }

    /**
     * Rewrites a manual track to what the user now says it was — type, both ends, both times — in
     * place, keeping its id. **Only a manual row may be rewritten**: every other track's points are
     * a measurement or a file's, and this replaces them outright.
     *
     * The row is excluded from its own overlap check ([TrackDao.countTracksSpanning]); the fixes it
     * would collide with are the ones being replaced. Nothing else here differs from an insert, the
     * finalize pass included — the aggregates and the overrun verdict are functions of the points,
     * and these are new points.
     */
    suspend fun updateManualTrack(
        trackId: Long,
        activityType: ActivityType,
        origin: ManualEnd,
        destination: ManualEnd,
    ): ManualTrackResult {
        val existing = dao.track(trackId)
        if (existing == null || TrackOrigin.fromCode(existing.source) != TrackOrigin.MANUAL) {
            return ManualTrackResult.NotEditable
        }
        refusedSpan(origin, destination, exceptTrackId = trackId)?.let { return it }
        db.withTransaction {
            dao.deletePointsFor(trackId)
            dao.insertPoints(manualPoints(trackId, origin, destination))
            dao.setManualTrack(
                trackId, activityType.name, origin.timestampMs, destination.timestampMs,
            )
            finalizeManualTrack(trackId)
        }
        DebugLog.i(TAG, "manual track: #$trackId rewritten as ${activityType.name}")
        return ManualTrackResult.Saved(trackId)
    }

    /**
     * The finalize an entered trip gets: the aggregates, then the derivation repaired around it.
     * Runs inside the caller's transaction (Room's are re-entrant), so the row, its aggregates and
     * the stays either side of it commit together. [importTracks] does not use it: a whole file
     * answers with one reconciliation over the history at the end rather than a seam per track.
     */
    private suspend fun finalizeManualTrack(trackId: Long) {
        finalizeImportedTrack(trackId)
        derivation.reknit(listOf(trackId))
    }

    /** [ManualTrackResult.Overlapping] where some other track already covers this span, else null. */
    private suspend fun refusedSpan(
        origin: ManualEnd,
        destination: ManualEnd,
        exceptTrackId: Long,
    ): ManualTrackResult? {
        val from = origin.timestampMs
        val to = destination.timestampMs
        val taken = dao.countTracksSpanning(from, to, exceptTrackId) > 0 ||
            dao.countTracksOverlapping(from, to, exceptTrackId) > 0
        return ManualTrackResult.Overlapping.takeIf { taken }
    }

    /**
     * A manual track's two fixes. Stamped exactly at the row's own bounds, because [TrackBounds] then
     * derives those same bounds back from them: a fix stamped anywhere else would move the clock away
     * from what the user typed — which is why insert and rewrite build them in one place.
     */
    private fun manualPoints(trackId: Long, origin: ManualEnd, destination: ManualEnd) =
        listOf(origin, destination).map { end ->
            TrackPoint(
                trackId = trackId,
                latitude = end.at.lat,
                longitude = end.at.lon,
                altitude = null,
                accuracy = null,
                speed = null,
                bearing = null,
                timestamp = end.timestampMs,
                segmentStart = false,
            )
        }

    /**
     * Inserts a batch of backup tracks, points and all, under fresh ids in one transaction, so a
     * 3000-track restore commits (and wakes the observed timeline queries) dozens of times, not
     * thousands. No keep thresholds, no duplicate check: restore targets an empty app (the UI only
     * offers it there).
     */
    suspend fun insertBackupTracks(batch: List<Pair<Track, List<TrackPoint>>>) {
        db.withTransaction {
            for ((track, points) in batch) {
                // Which fixes are the recorder's overrun, and where the clock sits over what
                // remains, are this code's verdicts rather than properties of the track — the rules
                // live here, not in the file, and a file written by older ones (or none) would
                // restore as-is until the next version bump swept it; so both are re-derived off the
                // in-memory points and applied before they are stored. The plan names points by
                // position, the only handle a restore has: the backup format stores no point ids,
                // so every parsed point carries id 0.
                val settled = settler.settleForInsert(track, points)
                val id = dao.insertTrack(
                    track.copy(
                        id = 0,
                        startedAt = settled.bounds.startedAt,
                        endedAt = if (track.endedAt == null) null else settled.bounds.endedAt,
                    ),
                )
                // Recounted from the restored fixes, through the one writer of those columns:
                // an aggregate on a track row is this code's answer about the points it holds,
                // never a file's, the same rule the GPX import states. The backup path used to
                // trust the file where nothing had moved, which the export's rounding made false.
                settler.refreshStats(id, settled.points)
                dao.insertPoints(settled.points.map { it.copy(id = 0, trackId = id) })
            }
        }
    }

    /**
     * Reassign a finished track's activity (misdetected, or an imported GPX without a type). The
     * activity feeds two stored verdicts, both re-derived here rather than left for a sweep that may
     * be releases away: it chooses the overrun detector's tuning ([EdgeStayDetector.paramsFor]), so
     * a reassignment across the foot/vehicle line would leave the stored overrun as the *other* rule
     * found it; and it sets the jump ceiling ([TrackQuality.jumpCeiling]), so a drive Activity
     * Recognition took for walking arrives judged against a pedestrian's, most of its path rejected
     * as teleports — correcting the activity says that ceiling was wrong, and
     * [TrackQuality.jumpRestores] hands those fixes back, only ever back, never the reverse. Both
     * questions are asked of the derived values rather than of the group, so a third set of params
     * or ceiling needs no edit here; when neither moved, the retype is the plain column write it
     * always was. An open track is left to the recorder, which is still gating its fixes on the
     * activity it detects and settles the edges when it finishes.
     */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) {
        val track = dao.track(trackId) ?: return
        val retuned = settler.tuningChanges(from = track.activityType, to = activityType)
        // An unreadable stored activity has no ceiling to compare against, so nothing is withdrawn.
        val wasType = ActivityType.ofName(track.activityType)
        val raised = wasType != null &&
            TrackQuality.jumpCeiling(activityType) > TrackQuality.jumpCeiling(wasType)
        db.withTransaction {
            dao.setActivityType(trackId, activityType.name)
            val endedAt = track.endedAt ?: return@withTransaction
            if (!retuned && !raised) return@withTransaction
            val retyped = track.copy(activityType = activityType.name)
            val stored = dao.allPointsFor(trackId)
            val restored = if (raised) settler.restoreJumps(trackId, stored, activityType) else null
            val jumpsRestored = restored != null
            // Run unconditionally once either rule is in play: restoring a fix moves the first or
            // last *good* point, which is where the overrun rule takes its bearings from.
            val applied = settler.settleAndRefresh(retyped, endedAt, restored ?: stored, totalsStale = jumpsRestored)
            // Not the free column write it looks like: either rule moves the track's bounds and
            // its first and last good coordinates, which are the whole of what a stay is derived
            // from. A retype that moved neither leaves the derivation alone.
            if (jumpsRestored || applied.changed) derivation.reknit(listOf(trackId))
        }
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
        val points = if (rename != null) settler.restoreJumps(track.id, stored, rename) ?: stored else stored
        // Finishing is where the track's aggregates are computed for the first time — the recorder
        // writes none of them while it records — and where the recorder's overrun is taken off the
        // path. The overrun comes off *before* the keep verdict deliberately: a track is judged on
        // the journey it recorded, not on the minutes it spent parked at the end of it.
        val applied = settler.settleAndRefresh(closing, endedAt, points, totalsStale = true)
        when (keepVerdict(closing, applied.bounds.startedAt, applied.bounds.endedAt, applied.stats)) {
            // The only verdict that puts the track on the timeline, and so the only one the
            // derivation has anything to say about: an open track was never in it, and one
            // discarded or purged at birth never enters.
            KeepRule.Verdict.KEEP -> {
                dao.closeTrack(track.id, applied.bounds.endedAt)
                derivation.reknit(listOf(track.id))
            }
            KeepRule.Verdict.DISCARD -> dao.discardTrack(
                track.id,
                endedAt = applied.bounds.endedAt,
                discardedAt = endedAt,
                reason = Track.REASON_FILTERED,
            )
            KeepRule.Verdict.PURGE -> dao.purgeTrack(track.id)
        }
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
        db.withTransaction {
            dao.setDiscarded(trackId, System.currentTimeMillis(), Track.REASON_DELETED)
            derivation.reknit(listOf(trackId))
        }
    }

    /** Bring a discarded track back to the timeline (undoes a delete/discard within retention). */
    suspend fun restoreTrack(trackId: Long) {
        db.withTransaction {
            dao.restoreTrack(trackId)
            derivation.reknit(listOf(trackId))
        }
    }

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
            // Recomputed, not summed: the merged track is one journey, so the ground between the
            // two halves counts like any other leg — a sum of the originals would leave it out.
            settler.settleAndRefresh(merged, merged.endedAt!!, dao.allPointsFor(mergedId), totalsStale = true)
            val now = System.currentTimeMillis()
            dao.setDiscarded(earlierId, now, Track.REASON_MERGED)
            dao.setDiscarded(laterId, now, Track.REASON_MERGED)
            derivation.reknit(listOf(mergedId, earlierId, laterId))
            mergedId
        }
    }

    /** What a [splitTrack] did, and all [unsplitTracks] needs to take it back. */
    data class Split(
        /** The new track that took everything from the cut onwards. The first half is the original
         *  row, which keeps its id. */
        val secondId: Long,
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
            // inner one; settleTrack below settles all four against the fixes each half keeps.
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
            // row id, timestamp and flag alone, so re-reading them would buy nothing.
            val first = track.copy(endedAt = plan.firstEndTs)
            val second = secondRow.copy(id = secondId)
            // Recomputed per half, not divided: each is its own journey now.
            settler.settleAndRefresh(first, plan.firstEndTs, before, totalsStale = true)
            settler.settleAndRefresh(second, endedAt, after, totalsStale = true)
            derivation.reknit(listOf(trackId, secondId))
            DebugLog.i(
                TAG,
                "track $trackId split at $atTs: kept ${before.size} points, " +
                    "moved ${after.size} to new track $secondId",
            )
            Split(secondId = secondId)
        }
    }

    /**
     * Undo a [splitTrack]: the second half's fixes go back onto [originalId], its now-empty row is
     * dropped, and the reunited track is re-derived — an exact inverse, since the overrun rule reads
     * the raw recording rather than its own output, so the pre-cut flags and bounds come back. The
     * reunited end is the later half's end, and the row holding it is the second half's until it is
     * purged — so it is read there rather than carried through the undo, where it would be a
     * snapshot taken before a sweep the snackbar can easily outlive.
     */
    suspend fun unsplitTracks(originalId: Long, split: Split) {
        db.withTransaction {
            val original = dao.track(originalId) ?: return@withTransaction
            // Absence is the only state this covers — a second tap of the same undo, with nothing
            // left to take back. The row is closed by construction, [splitTrack] having inserted it
            // with the original's own end.
            val rejoinedEnd = dao.track(split.secondId)?.endedAt ?: return@withTransaction
            // Points first: purging the row while they still hang off it would cascade them away.
            dao.movePointsFrom(originalId, split.secondId, Long.MIN_VALUE)
            dao.purgeTrack(split.secondId)
            // Written here rather than left to the settle below, which only rewrites an end it
            // disagrees with — and where it agrees, the row would keep the cut's own end.
            dao.closeTrack(originalId, rejoinedEnd)
            settler.settleAndRefresh(original, rejoinedEnd, dao.allPointsFor(originalId), totalsStale = true)
            derivation.reknit(listOf(originalId, split.secondId))
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
            derivation.reknit(listOf(mergedId, earlierId, laterId))
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

    /**
     * Every fix of several tracks, keyed by track — the backup export's read. Keyed rather than
     * flat so no caller has to know what the query's `ORDER BY` groups by, which also leaves this
     * free to answer from more than one statement. A track with no fixes is absent, not empty.
     */
    suspend fun pointsForTracks(trackIds: List<Long>): Map<Long, List<TrackPoint>> =
        trackIds.chunked(IDS_PER_STATEMENT)
            .flatMap { dao.pointsForTracks(it) }
            .groupBy { it.trackId }

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
     * Manual tracks finalize through here too: on two points every stage is a no-op except the
     * aggregates, which is exactly what they need.
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
                settler.refreshStats(trackId, points)
            } else {
                settler.settleAndRefresh(track, track.endedAt, points, totalsStale = true)
            }
        }
        return dropped
    }
}
