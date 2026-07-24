package io.github.valeronm.breadcrumb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The long-running data-transfer operations — GPX import/export/share and full backup/restore —
 * split out of [TrackListViewModel] so the timeline's state holder isn't also an orchestration
 * hub. Owned by the ViewModel and scoped to its lifetime: [scope] is the ViewModel's scope, so
 * every operation (and its progress flow) survives navigation exactly as before.
 */
internal class ImportExportController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repository: TrackRepository,
    private val backupRepositories: BackupRepositories,
) {

    /** Track progress of a long-running export/restore-style operation. */
    class OpProgress(val tracksDone: Int, val tracksTotal: Int?)

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
            val result = withContext(Dispatchers.IO) {
                try {
                    op { done, total -> progress.value = OpProgress(done, total) }
                    // Boundary catch: whatever an export/import throws, the user gets the failure
                    // toast instead of a crash. Cancellation isn't a failure — rethrow so the
                    // coroutine winds down instead of reporting a spurious null result.
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    DebugLog.w("Breadcrumb", "$logLabel failed: ${e.message}")
                    null
                }
            }
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

    /**
     * Restores a backup file — the whole file, no merging, which is why the UI only offers it
     * while the app is empty. Reports the summary, or null on failure.
     */
    fun restoreBackup(uri: Uri, onDone: (BackupImporter.Summary?) -> Unit) =
        runExclusiveOp(_restoreProgress, "backup restore", onDone) { onProgress ->
            BackupImporter.importFrom(app, backupRepositories, uri, onProgress)
        }

    class GpxImportSummary(val imported: Int, val duplicates: Int, val failed: Int)

    class GpxImportProgress(val filesDone: Int, val filesTotal: Int, val imported: Int)

    /** Non-null while an import runs — drives the Settings progress row; survives navigation. */
    private val _importProgress = MutableStateFlow<GpxImportProgress?>(null)
    val importProgress: StateFlow<GpxImportProgress?> = _importProgress

    /**
     * Imports the picked GPX files, one file at a time with [importProgress] updates.
     * [GpxImportSummary.failed] counts unreadable/unparseable files plus tracks without enough
     * timestamped points to place on the timeline. A second call while one runs is ignored.
     */
    fun importGpx(uris: List<Uri>, onDone: (GpxImportSummary) -> Unit) {
        if (_importProgress.value != null) return
        _importProgress.value = GpxImportProgress(0, uris.size, 0)
        scope.launch {
            var imported = 0
            var duplicates = 0
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
            onDone(GpxImportSummary(imported, duplicates, failed))
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
            onReady(Intent.createChooser(intent, if (single) "Share GPX track" else "Share GPX tracks"))
        }
    }
}
