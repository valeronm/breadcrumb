package io.github.valeronm.breadcrumb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)

    val tracks: StateFlow<List<TrackSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Mark any track left in a "recording" state by a crash/kill as completed.
            repository.finalizeDangling(exceptTrackId = LocationRecordingService.activeTrackId)
            // One-time backfill of the bad-fix flag over tracks recorded before DB v2.
            if (!Settings.isBadFixBackfillDone(getApplication())) {
                repository.reprocessAllTracks()
                Settings.setBadFixBackfillDone(getApplication())
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
