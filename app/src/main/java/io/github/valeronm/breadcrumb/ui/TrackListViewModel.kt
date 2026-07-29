package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The places table, admitted only when a reading would cluster differently from the last one —
 * compared through [PlaceClusterer.seedsOf], so the rule is stated in the type the clusterer
 * actually consumes and a new seed field cannot be forgotten in a comparison kept somewhere else.
 * A rename or a tag projects to the same seeds and is dropped here.
 *
 * This is the gate on the most expensive computation in the app: whatever survives it re-clusters
 * the entire history, and the user waits behind that. Rows rather than seeds come out, because the
 * clustering has to keep the exact list it was built from — see [TrackListViewModel.Clustered].
 * Extracted so the rule is reachable by a test without a database — see `PlaceDerivationGateTest`.
 */
internal fun pinnedRows(rows: Flow<List<Place>>): Flow<List<Place>> = rows
    .distinctUntilChanged { before, after ->
        PlaceClusterer.seedsOf(before) == PlaceClusterer.seedsOf(after)
    }

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val livenessRepository = LivenessRepository(app)
    private val placeRepository = PlaceRepository(app)
    private val backupRepositories = BackupRepositories(repository, placeRepository, livenessRepository)

    /** GPX import/export/share and full backup/restore — the transfer half of this screen's API. */
    internal val importExport = ImportExportController(app, viewModelScope, repository, backupRepositories)

    // These read `tracks` only, so a live recording's points can't wake them (see TrackDao) — the
    // distinctUntilChanged calls are for the writes that do: opening a track re-emits a list that
    // doesn't contain it (endedAt IS NOT NULL), and they stop that identical re-emission from
    // re-running the derivation downstream.
    //
    // Shared unseeded, and [timeline] combines *this* rather than the StateFlow below: a seeded
    // StateFlow emits its `emptyList()` at once, which would let the combine produce a real, empty
    // timeline before the first query returned — the very "a full history reads as empty" the null
    // initial exists to prevent, re-entering through the other input.
    private val trackRows: Flow<List<TrackSummary>> = repository.observeSummaries()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val tracks: StateFlow<List<TrackSummary>> = trackRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Soft-deleted tracks (deleted, filtered, merged), for the Recently deleted screen. */
    val discardedTracks: StateFlow<List<DiscardedSummary>> = repository.observeDiscardedSummaries()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A clustering run, the clock it was taken against, and **the exact places list it was built
     * from**. That list is not incidental: [PlaceResolver] resolves a cluster to a place by position
     * (`seedIndex`), so a derivation is only meaningful beside the reading that seeded it. Pairing
     * one with a later reading is what deleting a place would otherwise do — every row after the
     * gap shifts, and clusters resolve to their neighbours.
     */
    private class Clustered(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
    )

    /** One derivation run's inputs and outputs, shared by [timeline] and [places]. */
    private class Derived(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
    ) {
        /** The unsliced stays, extracted once — every downstream flow needs them. */
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()
    }

    /** The places table, read once for every reader below rather than observed per consumer. */
    private val placeRows: Flow<List<Place>> = placeRepository.observePlaces()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // The stay/place derivation is the most expensive pure computation in the app, so it runs once
    // here and both screens map from it. Of the live status only the active track's start matters
    // (constant per track) — distinctUntilChanged keeps per-fix status emissions from re-running
    // the clustering.
    private val clustered: Flow<Clustered> = combine(
        repository.observeEndpoints().distinctUntilChanged(),
        livenessRepository.observeEvents().distinctUntilChanged(),
        pinnedRows(placeRows),
        TrackingStatus.state.map { if (it.recording) it.startedAtMillis else null }.distinctUntilChanged(),
    ) { endpoints, events, places, activeStartedAt ->
        val now = System.currentTimeMillis()
        Clustered(
            StayDeriver.derive(
                tracks = endpoints.map { it.toTrackEnd() },
                liveness = events.mapNotNull { it.toLiveness() },
                nowMs = now,
                activeTrack = activeStartedAt?.let { StayDeriver.ActiveTrack(it) },
                distance = AndroidDistance,
                placePins = PlaceClusterer.seedsOf(places),
            ),
            places,
            now,
        )
    }.flowOn(Dispatchers.Default)

    // Freshening the rows *over* the clustering's own is what makes a tag cheap: the pins are
    // unchanged, so the cached derivation is reused and only what reads a label or category runs
    // again. Matched by id onto the list the clustering was built from — never replaced by it — so
    // the positional contract holds whatever the newer reading added or removed; a place deleted
    // while a re-clustering is in flight stays until that lands, which is correct, because it is
    // still in the clustering.
    private val derived: Flow<Derived> = combine(clustered, placeRows) { clustering, fresh ->
        val byId = fresh.associateBy { it.id }
        Derived(clustering.derivation, clustering.places.map { byId[it.id] ?: it }, clustering.now)
    }
        // A places write that moved a pin re-emits both sides; the first carries a list the patch
        // above has just restored to what the previous emission held, and re-deriving the timeline
        // off it would walk the whole history for an identical answer.
        .distinctUntilChanged { before, after ->
            before.derivation === after.derivation && before.places == after.places
        }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Tracks interleaved with derived stays and data gaps, newest first, sliced per local day.
     *
     * **Null until the first derivation lands**, which is not the same answer as an empty list and
     * must not be collapsed into one: the derivation walks the whole history, so on a cold start
     * there is a window where a full history reads as empty. The Timeline's empty state offers a
     * backup restore — an offer that is only safe *because* there is nothing to merge with — so a
     * reader that can't tell the two apart makes that offer over the user's data.
     */
    val timeline: StateFlow<List<TimelineItem>?> = combine(trackRows, derived) { summaries, d ->
        // Resolve places over the UNSLICED stays — after slicePerDay a 3-day stay would count
        // as 3 visits. Cluster ids survive the slicing copies, so items look up directly.
        val clusterPlaces = PlaceResolver.resolveClusters(d.stays, d.derivation.clusters, d.places)
        // Each track paired with its chronological successor, keyed by the track an interval
        // follows — what merging the two tracks around a short interval needs. observeSummaries
        // returns newest first, so chronological order is a reversed *view*: no re-sort of the
        // whole history on every emission.
        val neighbors = summaries.asReversed().zipWithNext()
            .associate { (a, b) -> a.id to (a to b) }

        // Stays and short gaps merge on the same rule, decided over the intervals as derived —
        // the rows below are per-day slices, whose bounds are the display's, not the stop's.
        val mergePlans = TrackMerge.plansByAnchor(d.derivation.intervals, neighbors)
        StayDeriver.interleave(
            summaries,
            StayDeriver.slicePerDay(d.derivation.intervals, ZoneId.systemDefault(), d.now),
        ).map { item ->
            when (item) {
                is TimelineItem.TrackItem -> item
                is TimelineItem.GapItem -> item.copy(
                    fromPlace = item.gap.fromClusterId?.let(clusterPlaces::getOrNull),
                    toPlace = item.gap.toClusterId?.let(clusterPlaces::getOrNull),
                    merge = mergePlans[item.gap.afterTrackId],
                )
                is TimelineItem.StayItem -> item.copy(
                    place = clusterPlaces.getOrNull(item.stay.clusterId),
                    merge = mergePlans[item.stay.afterTrackId],
                )
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every cluster's aggregate stats — visited places for the Places screen plus zero-visit
     * pass-through clusters so gap sides always have a detail page to open (the Places tab
     * filters the zero-visit rows out at display time). Idle unless a subscriber screen is open.
     *
     * **Null until the first derivation lands**, for the reason given on [timeline]: a screen that
     * reads "not yet" as "nothing" tells the user their history is empty while it is being read.
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>?> = derived.map { d ->
        PlaceResolver.summarize(d.stays, d.derivation.clusters, d.places, d.now)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What the user's own naming says about a place's category, retrained whenever the places table
     * changes — a rename or a fresh tag is exactly the evidence this learns from, so there is
     * nothing to invalidate by hand. Reads [placeRows] rather than [derived]: retraining owes
     * nothing to the clustering, and waiting on it would retrain on every finished track.
     */
    val categorySuggester: StateFlow<PlaceCategorySuggester.Model> = placeRows
        .map(PlaceCategorySuggester::train)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceCategorySuggester.Untrained)

    fun renamePlace(id: Long, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { placeRepository.rename(id, trimmed) }
    }

    /**
     * Name an unnamed cluster from the Places screen — pins a place at its centroid. Always
     * untagged: naming and categorizing are separate steps, because what a place is called is what
     * the category suggestion is read from.
     */
    fun createPlace(lat: Double, lon: Double, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            placeRepository.create(trimmed, lat, lon, System.currentTimeMillis())
        }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    /** Undo a [deletePlace] — the row comes back with its id, pin and radius intact. */
    fun restorePlace(place: Place) {
        viewModelScope.launch { placeRepository.restore(place) }
    }

    /**
     * Tag what a place is for, or untag it with null. Nothing re-derives — a category is metadata
     * the timeline reads back, not an input to clustering.
     */
    fun setPlaceCategory(id: Long, category: PlaceCategory?) {
        viewModelScope.launch { placeRepository.setCategory(id, category) }
    }

    /** Set a place's capture radius; the derivation re-runs and re-clusters reactively. */
    fun setPlaceRadius(id: Long, radiusM: Double) {
        viewModelScope.launch { placeRepository.setRadius(id, radiusM) }
    }

    /** Move a place's pin (re-center action); clustering and stays re-derive around it. */
    fun setPlacePin(id: Long, lat: Double, lon: Double) {
        viewModelScope.launch { placeRepository.setPin(id, lat, lon) }
    }

    /**
     * Merge the two tracks bracketing a short same-activity stay or gap (closing it). [onMerged] gets
     * the new track's id — the undo snackbar needs it to unmerge.
     */
    fun mergeTracks(plan: TrackMerge.Plan, onMerged: (Long) -> Unit) {
        viewModelScope.launch {
            repository.mergeTracks(plan.earlierId, plan.laterId)?.let(onMerged)
        }
    }

    /** Undo a [mergeTracks]: drop the merged track, bring both originals back. */
    fun unmergeTracks(mergedId: Long, plan: TrackMerge.Plan) {
        viewModelScope.launch {
            repository.unmergeTracks(mergedId, plan.earlierId, plan.laterId)
        }
    }

    /**
     * Cut a track in two at [atTs] (the point picked on the track screen's graph) — the track keeps
     * its id as the first half. [onSplit] gets what the undo snackbar needs to reverse it, and is
     * not called when the cut is refused.
     */
    fun splitTrack(trackId: Long, atTs: Long, onSplit: (TrackRepository.Split) -> Unit) {
        viewModelScope.launch {
            repository.splitTrack(trackId, atTs)?.let(onSplit)
        }
    }

    /** Undo a [splitTrack]: the second half's fixes go back and its row goes away. */
    fun unsplitTracks(originalId: Long, split: TrackRepository.Split) {
        viewModelScope.launch {
            repository.unsplitTracks(originalId, split)
        }
    }

    fun delete(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    /** Restore a discarded track (deleted, keep-threshold-filtered, or merge original). */
    fun restoreTrack(trackId: Long) {
        viewModelScope.launch { repository.restoreTrack(trackId) }
    }

    /** Permanently delete everything in Recently deleted. */
    fun purgeAllDiscarded() {
        viewModelScope.launch { repository.purgeAllDiscarded() }
    }

    fun setTrackActivity(trackId: Long, activityType: ActivityType) {
        viewModelScope.launch { repository.setActivityType(trackId, activityType) }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    /** Points newer than [afterId] — the live preview's incremental reload. */
    suspend fun getPointsAfter(trackId: Long, afterId: Long): List<TrackPoint> =
        repository.pointsAfter(trackId, afterId)

    /** Everything the track screen draws — the path, the bad fixes marked on it, and the overrun
     *  grayed off its ends. The overrun is read back from the rows, never re-detected: the screen
     *  shows what the track says it is. */
    suspend fun getTrackPoints(trackId: Long): TrackPoints = repository.trackPointsFor(trackId)
}

private fun TrackEndpoints.toTrackEnd() = StayDeriver.TrackEnd(
    trackId = id,
    startedAt = startedAt,
    endedAt = endedAt,
    start = if (startLat != null && startLon != null) StayDeriver.Endpoint(startLat, startLon) else null,
    end = if (endLat != null && endLon != null) StayDeriver.Endpoint(endLat, endLon) else null,
)

private fun LivenessEvent.toLiveness(): StayDeriver.Liveness? = when (type) {
    LivenessEvent.TYPE_ARMED -> StayDeriver.Armed(at)
    LivenessEvent.TYPE_DISARMED -> StayDeriver.Disarmed(at)
    LivenessEvent.TYPE_OUTAGE -> until?.let { StayDeriver.Outage(at, it) }
    else -> null
}
