package io.github.valeronm.breadcrumb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.TrackEndpoints
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val livenessRepository = LivenessRepository(app)
    private val placeRepository = PlaceRepository(app)

    val tracks: StateFlow<List<TrackSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tracks interleaved with derived stays and data gaps, newest first, sliced per local day. */
    // This is combine's last typed overload (5 flows) — a sixth flow needs the vararg form.
    val timeline: StateFlow<List<TimelineItem>> = combine(
        repository.observeSummaries(),
        repository.observeEndpoints(),
        livenessRepository.observeEvents(),
        placeRepository.observePlaces(),
        TrackingStatus.state,
    ) { summaries, endpoints, events, places, status ->
        val now = System.currentTimeMillis()
        val derivation = StayDeriver.derive(
            tracks = endpoints.map { it.toTrackEnd() },
            liveness = events.mapNotNull { it.toLiveness() },
            nowMs = now,
            activeRecording = status.recording,
            distance = AndroidDistance,
            placePins = places.map { PlaceClusterer.Seed(StayDeriver.Endpoint(it.lat, it.lon), it.radiusM) },
        )
        // Resolve places over the UNSLICED stays — after slicePerDay a 3-day stay would count
        // as 3 visits. afterTrackId keys survive the slicing copies.
        val resolutions = PlaceResolver.resolve(
            derivation.intervals.filterIsInstance<StayDeriver.Stay>(),
            derivation.clusters, places,
        )
        StayDeriver.interleave(
            summaries,
            StayDeriver.slicePerDay(derivation.intervals, ZoneId.systemDefault(), now),
        ).map { item ->
            if (item is TimelineItem.StayItem) item.copy(place = resolutions[item.stay.afterTrackId])
            else item
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-place aggregate stats for the Places screen (idle unless that screen is open). */
    val places: StateFlow<List<PlaceResolver.PlaceSummary>> = combine(
        repository.observeEndpoints(),
        livenessRepository.observeEvents(),
        placeRepository.observePlaces(),
        TrackingStatus.state,
    ) { endpoints, events, places, status ->
        val now = System.currentTimeMillis()
        val derivation = StayDeriver.derive(
            tracks = endpoints.map { it.toTrackEnd() },
            liveness = events.mapNotNull { it.toLiveness() },
            nowMs = now,
            activeRecording = status.recording,
            distance = AndroidDistance,
            placePins = places.map { PlaceClusterer.Seed(StayDeriver.Endpoint(it.lat, it.lon), it.radiusM) },
        )
        PlaceResolver.summarize(
            derivation.intervals.filterIsInstance<StayDeriver.Stay>(),
            derivation.clusters, places, now,
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

    init {
        viewModelScope.launch {
            // Crash-cleanup of dangling tracks happens in the service's arm path; here only the
            // one-time backfill of the ignore reason over points recorded before DB v5.
            if (!Settings.isIgnoreReasonBackfillDone(getApplication())) {
                repository.backfillIgnoreReasons()
                Settings.setIgnoreReasonBackfillDone(getApplication())
            }
        }
    }

    fun delete(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    /** DEBUG: inserts a synthetic track for exercising the UI without real movement. */
    fun seedSampleTrack(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.seedSampleTrack()
            onDone()
        }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    /** The ignored "bad fix" points, shown as markers on the track map. */
    suspend fun getIgnoredPoints(trackId: Long): List<TrackPoint> = repository.ignoredPointsFor(trackId)

    /** Exports every track as a .gpx file into the picked folder; reports how many were written. */
    fun exportAll(treeUri: Uri, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(GpxExporter.exportAllToTree(getApplication(), repository, treeUri))
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
