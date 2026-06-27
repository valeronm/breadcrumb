package com.valeronm.activitytracker.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.valeronm.activitytracker.data.TrackRepository
import com.valeronm.activitytracker.data.db.TrackPoint
import com.valeronm.activitytracker.data.db.TrackSummary
import com.valeronm.activitytracker.data.export.GpxExporter
import com.valeronm.activitytracker.location.LocationRecordingService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)

    val tracks: StateFlow<List<TrackSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Mark any track left in a "recording" state by a crash/kill as completed.
        viewModelScope.launch {
            repository.finalizeDangling(exceptTrackId = LocationRecordingService.activeTrackId)
        }
    }

    fun delete(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    suspend fun getPoints(trackId: Long): List<TrackPoint> = repository.pointsFor(trackId)

    /** Exports every track as a .gpx file into the picked folder; reports how many were written. */
    fun exportAll(treeUri: Uri, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(GpxExporter.exportAllToTree(getApplication(), repository, treeUri))
        }
    }

    /** Builds a GPX file for the track and hands back a share chooser Intent. */
    fun share(trackId: Long, onReady: (Intent?) -> Unit) {
        viewModelScope.launch {
            val uri = GpxExporter.export(getApplication(), repository, trackId)
            if (uri == null) {
                onReady(null)
                return@launch
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onReady(Intent.createChooser(share, "Share GPX track"))
        }
    }

    /** Exports several tracks and hands back a multi-file share chooser Intent. */
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
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/gpx+xml"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            onReady(Intent.createChooser(intent, "Share GPX tracks"))
        }
    }
}
