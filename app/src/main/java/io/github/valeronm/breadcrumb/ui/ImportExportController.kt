package io.github.valeronm.breadcrumb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.data.export.GoogleTimelineImporter
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Breadcrumb"

/**
 * The long-running data-transfer operations — GPX import/export/share and full backup/restore — kept
 * out of [TrackListViewModel] so the timeline's state holder isn't also an orchestration hub. [scope]
 * is the owning ViewModel's, so every operation and its progress flow survive navigation.
 */
internal class ImportExportController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repository: TrackRepository,
    private val backupRepositories: BackupRepositories,
) {

    /** Track progress of a long-running export/restore-style operation. */
    class OpProgress(val tracksDone: Int, val tracksTotal: Int?)

    /** What a load into the history reports when it is not a failure, which is null. */
    sealed interface LoadOutcome<out S> {
        class Loaded<S>(val summary: S) : LoadOutcome<S>

        /** Refused at the start: another load into the history is running. */
        data object Busy : LoadOutcome<Nothing>

        /** Refused at the start: the history holds a track, and this load merges nothing. */
        data object NotEmpty : LoadOutcome<Nothing>
    }

    /** Non-null while a GPX bulk export runs — drives the Export tracks row; survives navigation. */
    private val _gpxExportProgress = MutableStateFlow<OpProgress?>(null)
    val gpxExportProgress: StateFlow<OpProgress?> = _gpxExportProgress

    /** Non-null while a backup export runs — drives the "Back up everything" row; survives navigation. */
    private val _exportProgress = MutableStateFlow<OpProgress?>(null)
    val exportProgress: StateFlow<OpProgress?> = _exportProgress

    /** Non-null while a backup restore runs — drives the empty-state progress text. */
    private val _restoreProgress = MutableStateFlow<OpProgress?>(null)
    val restoreProgress: StateFlow<OpProgress?> = _restoreProgress

    /**
     * The shared scaffold of the long-running operations above: one at a time per [progress] flow
     * (a second call while one runs is ignored), progress published as the operation reports it
     * and cleared when it ends, failures logged and surfaced to [onDone] as null.
     */
    private fun <T> runExclusiveOp(
        progress: MutableStateFlow<OpProgress?>,
        logLabel: String,
        onDone: (T?) -> Unit,
        op: suspend (onProgress: (Int, Int?) -> Unit) -> T?,
    ) {
        if (progress.value != null) return
        progress.value = OpProgress(0, null)
        scope.launch {
            // These run for minutes over a long history, and a report that one was slow arrives
            // with no way to tell slow from stalled unless the log says when it began and how long
            // it took. Elapsed realtime, not the wall clock, so a clock correction mid-export
            // can't produce a negative duration.
            val startedAt = SystemClock.elapsedRealtime()
            DebugLog.i(TAG, "$logLabel started")
            val result = withContext(Dispatchers.IO) {
                try {
                    op { done, total -> progress.value = OpProgress(done, total) }
                    // Boundary catch: whatever an export/import throws, the user gets the failure
                    // toast instead of a crash. Cancellation isn't a failure — rethrow so the
                    // coroutine winds down instead of reporting a spurious null result.
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    DebugLog.w(TAG, "$logLabel failed: ${e.message}")
                    null
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            DebugLog.i(TAG, "$logLabel ${if (result == null) "gave up" else "finished"} in $elapsed ms")
            progress.value = null
            onDone(result)
        }
    }

    /** Exports every track as a .gpx file into the picked folder; reports how many were written. */
    fun exportAll(treeUri: Uri, onDone: (Int?) -> Unit) =
        runExclusiveOp(_gpxExportProgress, "gpx export", onDone) { onProgress ->
            GpxExporter.exportAllToTree(app, repository, treeUri, onProgress)
        }

    /**
     * Writes the whole history as one gzipped JSON file (backup, and the web companion's data
     * source); reports the track count, or null on failure.
     */
    fun exportBackup(uri: Uri, onDone: (Int?) -> Unit) =
        runExclusiveOp(_exportProgress, "backup export", onDone) { onProgress ->
            BackupExporter.exportTo(app, backupRepositories, uri, System.currentTimeMillis(), onProgress)
        }

    /** Non-null while the history is being cleared. */
    private val _clearProgress = MutableStateFlow<OpProgress?>(null)
    val clearProgress: StateFlow<OpProgress?> = _clearProgress

    /** Two operations on the history running together would each write without seeing the other. */
    private fun loading(): Boolean = historyOps.any { it.value != null }

    /**
     * Deletes the whole history, recording left armed; [onBusy] instead while another operation on
     * the history runs.
     */
    fun clearHistory(onBusy: () -> Unit, onDone: (cleared: Boolean) -> Unit) {
        if (loading()) {
            onBusy()
            return
        }
        runExclusiveOp(_clearProgress, "history clear", { onDone(it != null) }) {
            val clear: suspend () -> Unit = { repository.clearHistory() }
            LocationRecordingService.instance?.clearingHistory(clear) ?: clear()
        }
    }

    /**
     * A load that merges nothing, so it starts only into a history holding no track, checked as it
     * starts rather than when it was offered: a recording can end while the file is being picked.
     */
    private fun <S> runEmptyHistoryLoad(
        progress: MutableStateFlow<OpProgress?>,
        logLabel: String,
        onDone: (LoadOutcome<S>?) -> Unit,
        load: suspend (onProgress: (Int, Int?) -> Unit) -> S?,
    ) {
        if (loading()) {
            onDone(LoadOutcome.Busy)
            return
        }
        runExclusiveOp(progress, logLabel, onDone) { onProgress ->
            if (repository.hasKeptTracks()) LoadOutcome.NotEmpty else load(onProgress)?.let { LoadOutcome.Loaded(it) }
        }
    }

    /** Restores a backup file whole into an empty history. Reports the outcome, or null on failure. */
    fun restoreBackup(uri: Uri, onDone: (LoadOutcome<BackupImporter.Summary>?) -> Unit) =
        runEmptyHistoryLoad(_restoreProgress, "backup restore", onDone) { onProgress ->
            BackupImporter.importFrom(app, backupRepositories, uri, onProgress)
        }

    /** Non-null while a Google Timeline import runs. */
    private val _googleTimelineImportProgress = MutableStateFlow<OpProgress?>(null)
    val googleTimelineImportProgress: StateFlow<OpProgress?> = _googleTimelineImportProgress

    /** Loads a Google Timeline export into an empty history. Reports the outcome, or null on failure. */
    fun importGoogleTimeline(uri: Uri, onDone: (LoadOutcome<GoogleTimelineImporter.Summary>?) -> Unit) =
        runEmptyHistoryLoad(_googleTimelineImportProgress, "google timeline import", onDone) { onProgress ->
            GoogleTimelineImporter.importFrom(
                app,
                backupRepositories,
                uri,
                placeLabel = { app.getString(it.labelRes) },
                onProgress = onProgress,
            )
        }

    class GpxImportSummary(
        val imported: Int,
        val duplicates: Int,
        val overlapping: Int,
        val failed: Int,
    )

    class GpxImportProgress(val filesDone: Int, val filesTotal: Int, val imported: Int)

    /** Non-null while an import runs — drives the Settings progress row; survives navigation. */
    private val _importProgress = MutableStateFlow<GpxImportProgress?>(null)
    val importProgress: StateFlow<GpxImportProgress?> = _importProgress

    /** Every operation that writes into the history or clears it; declared after each of them. */
    private val historyOps: List<StateFlow<*>> =
        listOf(_restoreProgress, _googleTimelineImportProgress, _importProgress, _clearProgress)

    /** Whether one of [historyOps] runs, for the rows to show. It trails them, so the start guard reads them directly. */
    val historyBusy: StateFlow<Boolean> = combine(historyOps) { ops -> ops.any { it != null } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Imports the picked GPX files, one file at a time with [importProgress] updates.
     * [GpxImportSummary.failed] counts unreadable/unparseable files plus tracks without enough
     * timestamped points to place on the timeline. [onBusy] instead while any load into the history
     * runs.
     */
    fun importGpx(uris: List<Uri>, onBusy: () -> Unit, onDone: (GpxImportSummary) -> Unit) {
        if (loading()) {
            onBusy()
            return
        }
        _importProgress.value = GpxImportProgress(0, uris.size, 0)
        scope.launch {
            var imported = 0
            var duplicates = 0
            var overlapping = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                for ((index, uri) in uris.withIndex()) {
                    try {
                        val parsed =
                            resolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: emptyList()
                        val importable = parsed.mapNotNull { GpxParser.toImportable(it) }
                        failed += parsed.size - importable.size
                        if (parsed.isEmpty()) failed++ // a readable file with no tracks at all
                        val counts = repository.importTracks(importable)
                        imported += counts.imported
                        duplicates += counts.duplicates
                        overlapping += counts.overlapping
                        // Boundary catch: one unreadable file counts as failed, the rest import.
                        // Cancellation isn't a failed file — rethrow instead of counting it.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        DebugLog.w("Breadcrumb", "gpx import failed for $uri: ${e.message}")
                        failed++
                    }
                    _importProgress.value = GpxImportProgress(index + 1, uris.size, imported)
                }
            }
            _importProgress.value = null
            onDone(GpxImportSummary(imported, duplicates, overlapping, failed))
        }
    }

    /** Exports the given tracks and hands back a share chooser Intent (single- or multi-file). */
    fun shareTracks(trackIds: List<Long>, onReady: (Intent?) -> Unit) {
        scope.launch {
            val uris = ArrayList<Uri>()
            for (id in trackIds) {
                GpxExporter.export(app, repository, id)?.let { uris.add(it) }
            }
            if (uris.isEmpty()) {
                onReady(null)
                return@launch
            }
            val single = uris.size == 1
            val intent = Intent(if (single) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = GpxExporter.MIME_TYPE
                if (single) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val title = app.getString(if (single) R.string.share_gpx_track else R.string.share_gpx_tracks)
            onReady(Intent.createChooser(intent, title))
        }
    }
}
