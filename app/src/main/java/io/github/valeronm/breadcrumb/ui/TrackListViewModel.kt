package io.github.valeronm.breadcrumb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.ActivityType
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
    val tracks: StateFlow<List<TrackSummary>> = repository.observeSummaries()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Soft-deleted tracks (deleted, filtered, merged), for the Recently deleted screen. */
    val discardedTracks: StateFlow<List<DiscardedSummary>> = repository.observeDiscardedSummaries()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One derivation run's inputs and outputs, shared by [timeline] and [places]. */
    private class Derived(
        val derivation: StayDeriver.Derivation,
        val places: List<Place>,
        val now: Long,
    ) {
        /** The unsliced stays, extracted once — every downstream flow needs them. */
        val stays: List<StayDeriver.Stay> = derivation.intervals.filterIsInstance<StayDeriver.Stay>()
    }

    // The stay/place derivation is the most expensive pure computation in the app, so it runs once
    // here and both screens map from it. Of the live status only the active track's start matters
    // (constant per track) — distinctUntilChanged keeps per-fix status emissions from re-running
    // the clustering.
    private val derived: Flow<Derived> = combine(
        repository.observeEndpoints().distinctUntilChanged(),
        livenessRepository.observeEvents().distinctUntilChanged(),
        placeRepository.observePlaces().distinctUntilChanged(),
        TrackingStatus.state.map { if (it.recording) it.startedAtMillis else null }.distinctUntilChanged(),
    ) { endpoints, events, places, activeStartedAt ->
        val now = System.currentTimeMillis()
        val derivation = StayDeriver.derive(
            tracks = endpoints.map { it.toTrackEnd() },
            liveness = events.mapNotNull { it.toLiveness() },
            nowMs = now,
            activeTrack = activeStartedAt?.let { StayDeriver.ActiveTrack(it) },
            distance = AndroidDistance,
            placePins = places.map { PlaceClusterer.Seed(StayDeriver.Endpoint(it.lat, it.lon), it.radiusM) },
        )
        Derived(derivation, places, now)
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Tracks interleaved with derived stays and data gaps, newest first, sliced per local day. */
    val timeline: StateFlow<List<TimelineItem>> = combine(tracks, derived) { summaries, d ->
        // Resolve places over the UNSLICED stays — after slicePerDay a 3-day stay would count
        // as 3 visits. Cluster ids survive the slicing copies, so items look up directly.
        val clusterPlaces = PlaceResolver.resolveClusters(d.stays, d.derivation.clusters, d.places)
        // Each track's chronological successor, for merging a short same-activity stay's two tracks.
        val byId = summaries.associateBy { it.id }
        val nextTrack = summaries.sortedBy { it.startedAt }.zipWithNext()
            .associate { (a, b) -> a.id to b }
        StayDeriver.interleave(
            summaries,
            StayDeriver.slicePerDay(d.derivation.intervals, ZoneId.systemDefault(), d.now),
        ).map { item ->
            when (item) {
                is TimelineItem.TrackItem -> item
                is TimelineItem.GapItem -> item.copy(
                    fromPlace = item.gap.fromClusterId?.let(clusterPlaces::getOrNull),
                    toPlace = item.gap.toClusterId?.let(clusterPlaces::getOrNull),
                )
                is TimelineItem.StayItem -> {
                    val resolution = clusterPlaces.getOrNull(item.stay.clusterId)
                    val before = byId[item.stay.afterTrackId]
                    val after = nextTrack[item.stay.afterTrackId]
                    val merge = if (before != null && after != null) {
                        TrackMerge.plan(
                            before, after, item.stay.start, item.stay.end,
                            stayIsNamedPlace = resolution?.label != null,
                        )
                    } else {
                        null
                    }
                    item.copy(place = resolution, merge = merge)
                }
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every cluster's aggregate stats — visited places for the Places screen plus zero-visit
     * pass-through clusters so gap sides always have a detail page to open (the Places tab
     * filters the zero-visit rows out at display time). Idle unless a subscriber screen is open.
     */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>> = derived.map { d ->
        PlaceResolver.summarize(d.stays, d.derivation.clusters, d.places, d.now)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun renamePlace(id: Long, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { placeRepository.rename(id, trimmed) }
    }

    /** Name an unnamed cluster from the Places screen — pins a place at its centroid. */
    fun createPlace(lat: Double, lon: Double, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { placeRepository.create(trimmed, lat, lon, System.currentTimeMillis()) }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    /** Undo a [deletePlace] — the row comes back with its id, pin and radius intact. */
    fun restorePlace(place: Place) {
        viewModelScope.launch { placeRepository.restore(place) }
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
     * Merge the two tracks bracketing a short same-activity stay (closes the stay). [onMerged] gets
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

    /** The ignored "bad fix" points, shown as markers on the track map. */
    suspend fun getIgnoredPoints(trackId: Long): List<TrackPoint> = repository.ignoredPointsFor(trackId)

    /** The fixes already taken off the path as the recorder's overrun — grayed on the track map.
     *  Read back from the rows, never re-detected: the screen shows what the track says it is. */
    suspend fun getEdgeStayPoints(trackId: Long): List<TrackPoint> =
        repository.edgeStayPointsFor(trackId)
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
