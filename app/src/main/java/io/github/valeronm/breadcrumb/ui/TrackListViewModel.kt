package io.github.valeronm.breadcrumb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.ActivityType
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
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val livenessRepository = LivenessRepository(app)
    private val placeRepository = PlaceRepository(app)

    // Room re-runs these queries on ANY write to the tables they touch — including every point
    // batch of the live recording, whose content they exclude (endedAt IS NOT NULL). The
    // distinctUntilChanged calls turn those identical re-emissions into cheap list-equality
    // checks instead of re-running the derivation/UI work downstream.
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
    )

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
        // as 3 visits. afterTrackId keys survive the slicing copies.
        val resolutions = PlaceResolver.resolve(
            d.derivation.intervals.filterIsInstance<StayDeriver.Stay>(),
            d.derivation.clusters, d.places,
        )
        // Each track's chronological successor, for merging a short same-activity stay's two tracks.
        val byId = summaries.associateBy { it.id }
        val nextTrack = summaries.sortedBy { it.startedAt }.zipWithNext()
            .associate { (a, b) -> a.id to b }
        StayDeriver.interleave(
            summaries,
            StayDeriver.slicePerDay(d.derivation.intervals, ZoneId.systemDefault(), d.now),
        ).map { item ->
            if (item !is TimelineItem.StayItem) return@map item
            val resolution = resolutions[item.stay.afterTrackId]
            val before = byId[item.stay.afterTrackId]
            val after = nextTrack[item.stay.afterTrackId]
            val merge = if (before != null && after != null) {
                TrackMerge.plan(
                    before, after, item.stay.start, item.stay.end,
                    stayIsNamedPlace = resolution?.label != null,
                )
            } else null
            item.copy(place = resolution, merge = merge)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-place aggregate stats for the Places screen (idle unless that screen is open). */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>> = derived.map { d ->
        PlaceResolver.summarize(
            d.derivation.intervals.filterIsInstance<StayDeriver.Stay>(),
            d.derivation.clusters, d.places, d.now,
        )
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

    /** Set a place's capture radius; the derivation re-runs and re-clusters reactively. */
    fun setPlaceRadius(id: Long, radiusM: Double) {
        viewModelScope.launch { placeRepository.setRadius(id, radiusM) }
    }

    /** Move a place's pin (re-centre action); clustering and stays re-derive around it. */
    fun setPlacePin(id: Long, lat: Double, lon: Double) {
        viewModelScope.launch { placeRepository.setPin(id, lat, lon) }
    }

    /** Merge the two tracks bracketing a short same-activity stay (closes the stay). */
    fun mergeTracks(plan: TrackMerge.Plan) {
        viewModelScope.launch { repository.mergeTracks(plan.earlierId, plan.laterId) }
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

    /** Exports every track as a .gpx file into the picked folder; reports how many were written. */
    fun exportAll(treeUri: Uri, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(GpxExporter.exportAllToTree(getApplication(), repository, treeUri))
        }
    }

    class GpxImportSummary(val imported: Int, val duplicates: Int, val failed: Int)

    class GpxImportProgress(val filesDone: Int, val filesTotal: Int, val imported: Int)

    /** Non-null while an import runs — drives the Settings progress row; survives navigation. */
    private val _importProgress = MutableStateFlow<GpxImportProgress?>(null)
    val importProgress: StateFlow<GpxImportProgress?> = _importProgress

    /**
     * Imports the picked GPX files, one file at a time with [importProgress] updates. [failed]
     * counts unreadable/unparseable files plus tracks without enough timestamped points to place
     * on the timeline. A second call while one runs is ignored.
     */
    fun importGpx(uris: List<Uri>, onDone: (GpxImportSummary) -> Unit) {
        if (_importProgress.value != null) return
        _importProgress.value = GpxImportProgress(0, uris.size, 0)
        viewModelScope.launch {
            var imported = 0
            var duplicates = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                for ((index, uri) in uris.withIndex()) {
                    try {
                        val parsed =
                            resolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: emptyList()
                        val importable = parsed.mapNotNull { GpxParser.toImportable(it, AndroidDistance) }
                        failed += parsed.size - importable.size
                        if (parsed.isEmpty()) failed++ // a readable file with no tracks at all
                        val counts = repository.importTracks(importable)
                        imported += counts.imported
                        duplicates += counts.duplicates
                    } catch (e: Exception) {
                        DebugLog.w("Breadcrumb", "gpx import failed for $uri: ${e.message}")
                        failed++
                    }
                    _importProgress.value = GpxImportProgress(index + 1, uris.size, imported)
                }
            }
            _importProgress.value = null
            onDone(GpxImportSummary(imported, duplicates, failed))
        }
    }

    /** Exports the given tracks and hands back a share chooser Intent (single- or multi-file). */
    fun shareTracks(trackIds: List<Long>, onReady: (Intent?) -> Unit) {
        viewModelScope.launch {
            val uris = ArrayList<Uri>()
            for (id in trackIds) {
                GpxExporter.export(getApplication(), repository, id)?.let { uris.add(it) }
            }
            if (uris.isEmpty()) {
                onReady(null)
                return@launch
            }
            val single = uris.size == 1
            val intent = Intent(if (single) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = GpxExporter.MIME_TYPE
                if (single) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onReady(Intent.createChooser(intent, if (single) "Share GPX track" else "Share GPX tracks"))
        }
    }
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
