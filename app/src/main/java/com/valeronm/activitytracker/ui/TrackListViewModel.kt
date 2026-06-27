package com.valeronm.activitytracker.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.valeronm.activitytracker.data.TrackRepository
import com.valeronm.activitytracker.data.db.TrackSummary
import com.valeronm.activitytracker.data.export.GpxExporter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)

    val tracks: StateFlow<List<TrackSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
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
}
