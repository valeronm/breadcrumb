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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Breadcrumb"

/**
 * The operations on the whole history, and GPX sharing, kept out of [TrackListViewModel] so the
 * timeline's state holder isn't also an orchestration hub. [scope] is the owning ViewModel's, so
 * every operation and its progress flow survive navigation.
 */
internal class ImportExportController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val repository: TrackRepository,
    private val backupRepositories: BackupRepositories,
    private val say: (String) -> Unit,
) {

    /** Track progress of a long-running export/restore-style operation. */
    class OpProgress(val tracksDone: Int, val tracksTotal: Int?)

    class GpxImportProgress(val filesDone: Int, val filesTotal: Int, val imported: Int)

    /** What a restore reports when it is not a failure, which is null. */
    private sealed interface RestoreOutcome {
        class Restored(val summary: BackupImporter.Summary) : RestoreOutcome

        /** Refused at the start: the history holds a track, and a restore merges nothing. */
        data object NotEmpty : RestoreOutcome
    }

    private enum class Transfer(val logLabel: String) {
        GPX_EXPORT("gpx export"),
        BACKUP_EXPORT("backup export"),
        BACKUP_RESTORE("backup restore"),
        CLEAR("history clear"),
        GOOGLE_IMPORT("google timeline import"),
    }

    private sealed interface Running {
        class Transferring(val transfer: Transfer, val progress: OpProgress) : Running

        class ImportingGpx(val progress: GpxImportProgress) : Running

        data object Undeleting : Running
    }

    /**
     * Two operations on the whole history running together would each read or write without seeing
     * the other, so an operation takes this slot only through [launchClaimed].
     */
    private val running = MutableStateFlow<Running?>(null)

    /**
     * Refused, with the reader told why, while another operation holds the slot. The message [work]
     * returns is said once the slot is free.
     */
    private fun launchClaimed(op: Running, work: suspend () -> String?) {
        if (!running.compareAndSet(null, op)) {
            say(app.getString(R.string.data_load_busy))
            return
        }
        scope.launch {
            val message = try {
                work()
            } finally {
                running.value = null
            }
            message?.let(say)
        }
    }

    private fun <T> derived(read: (Running?) -> T): StateFlow<T> =
        running.map(read).stateIn(scope, SharingStarted.Eagerly, read(running.value))

    private fun progressOf(transfer: Transfer): StateFlow<OpProgress?> =
        derived { op -> (op as? Running.Transferring)?.takeIf { it.transfer == transfer }?.progress }

    /** Trails [running], so no operation is gated on it. */
    val historyBusy: StateFlow<Boolean> = derived { it != null }

    /** Non-null while a GPX bulk export runs. */
    val gpxExportProgress: StateFlow<OpProgress?> = progressOf(Transfer.GPX_EXPORT)

    /** Non-null while a backup export runs. */
    val exportProgress: StateFlow<OpProgress?> = progressOf(Transfer.BACKUP_EXPORT)

    /** Non-null while a backup restore runs. */
    val restoreProgress: StateFlow<OpProgress?> = progressOf(Transfer.BACKUP_RESTORE)

    /** Non-null while the history is being cleared. */
    val clearProgress: StateFlow<OpProgress?> = progressOf(Transfer.CLEAR)

    /** Non-null while a Google Timeline import runs. Its total is the trips found in the file, null
     *  until the file is read. */
    val googleTimelineImportProgress: StateFlow<OpProgress?> = progressOf(Transfer.GOOGLE_IMPORT)

    /** Non-null while a GPX import runs. */
    val importProgress: StateFlow<GpxImportProgress?> = derived { (it as? Running.ImportingGpx)?.progress }

    /** Null when [block] throws, logged as [label] failing; a cancellation is no failure and propagates. */
    private suspend fun <T> failureAsNull(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        DebugLog.w(TAG, "$label failed: ${e.message}")
        null
    }

    /** [op]'s failures are logged and reach [message] as null. */
    private fun <T> runTransfer(
        transfer: Transfer,
        message: (T?) -> String,
        op: suspend (onProgress: (Int, Int?) -> Unit) -> T?,
    ) = launchClaimed(Running.Transferring(transfer, OpProgress(0, null))) {
        // These run for minutes over a long history, and a report that one was slow arrives with no
        // way to tell slow from stalled unless the log says when it began and how long it took.
        // Elapsed realtime, not the wall clock, so a clock correction mid-export can't produce a
        // negative duration.
        val startedAt = SystemClock.elapsedRealtime()
        DebugLog.i(TAG, "${transfer.logLabel} started")
        val result = withContext(Dispatchers.IO) {
            failureAsNull(transfer.logLabel) {
                op { done, total -> running.value = Running.Transferring(transfer, OpProgress(done, total)) }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        DebugLog.i(TAG, "${transfer.logLabel} ${if (result == null) "gave up" else "finished"} in $elapsed ms")
        message(result)
    }

    private fun exportResultMessage(count: Int?): String = if (count == null) {
        app.getString(R.string.data_export_failed)
    } else {
        app.counted(R.plurals.data_exported, count)
    }

    /** Exports every track as a .gpx file into the picked folder. */
    fun exportAll(treeUri: Uri) =
        runTransfer(Transfer.GPX_EXPORT, ::exportResultMessage) { onProgress ->
            GpxExporter.exportAllToTree(app, repository, treeUri, onProgress)
        }

    /** Writes the whole history as one gzipped JSON file (backup, and the web companion's data source). */
    fun exportBackup(uri: Uri) =
        runTransfer(Transfer.BACKUP_EXPORT, ::exportResultMessage) { onProgress ->
            BackupExporter.exportTo(app, backupRepositories, uri, System.currentTimeMillis(), onProgress)
        }

    /** Deletes the whole history, recording left armed. */
    fun clearHistory() =
        runTransfer(
            Transfer.CLEAR,
            { app.getString(if (it == null) R.string.data_clear_failed else R.string.data_cleared) },
        ) {
            val clear: suspend () -> Unit = { repository.clearHistory() }
            LocationRecordingService.instance?.clearingHistory(clear) ?: clear()
        }

    private fun restoreMessage(outcome: RestoreOutcome?): String = when (outcome) {
        null -> app.getString(R.string.data_restore_failed)
        is RestoreOutcome.Restored -> app.getString(
            R.string.data_restored,
            app.counted(R.plurals.data_trip_count, outcome.summary.tracks),
            app.counted(R.plurals.data_place_count, outcome.summary.places),
        )
        RestoreOutcome.NotEmpty -> app.getString(R.string.data_load_not_empty)
    }

    /**
     * Restores a backup file whole into an empty history. Emptiness is checked as the restore
     * starts rather than when it was offered: a recording can end while the file is being picked.
     */
    fun restoreBackup(uri: Uri) =
        runTransfer(Transfer.BACKUP_RESTORE, ::restoreMessage) { onProgress ->
            if (repository.hasKeptTracks()) {
                RestoreOutcome.NotEmpty
            } else {
                BackupImporter.importFrom(app, backupRepositories, uri, onProgress)?.let(RestoreOutcome::Restored)
            }
        }

    fun restoreDeleted(trackIds: List<Long>) = launchClaimed(Running.Undeleting) {
        when (failureAsNull("recently deleted restore") { repository.restoreTracks(trackIds) }) {
            null -> app.getString(R.string.discarded_restore_failed)
            false -> app.getString(R.string.discarded_restore_overlaps)
            true -> null
        }
    }

    private fun googleImportMessage(summary: GoogleTimelineImporter.Summary?): String {
        if (summary == null) return app.getString(R.string.data_google_import_failed)
        val trips = app.counted(R.plurals.data_trip_count, summary.tracks)
        val places = app.counted(R.plurals.data_place_count, summary.places)
        val loaded = if (summary.skipped == 0) {
            app.getString(R.string.data_google_imported, trips, places)
        } else {
            val unreadable = app.counted(R.plurals.data_google_unreadable, summary.skipped)
            app.getString(R.string.data_google_imported_skipped, trips, places, unreadable)
        }
        if (summary.overlapping == 0) return loaded
        val overlapping = app.counted(R.plurals.gpx_overlapping, summary.overlapping)
        return "$loaded · $overlapping"
    }

    fun importGoogleTimeline(uri: Uri) =
        runTransfer(Transfer.GOOGLE_IMPORT, ::googleImportMessage) { onProgress ->
            GoogleTimelineImporter.importFrom(
                app,
                backupRepositories,
                uri,
                placeLabel = { app.getString(it.labelRes) },
                onProgress = onProgress,
            )
        }

    private class GpxImportSummary(
        val imported: Int,
        val duplicates: Int,
        val overlapping: Int,
        val failed: Int,
    )

    private fun gpxImportMessage(result: GpxImportSummary): String = buildList {
        add(app.counted(R.plurals.gpx_imported, result.imported))
        if (result.duplicates > 0) add(app.counted(R.plurals.gpx_duplicates, result.duplicates))
        if (result.overlapping > 0) add(app.counted(R.plurals.gpx_overlapping, result.overlapping))
        if (result.failed > 0) add(app.counted(R.plurals.gpx_failed, result.failed))
    }.joinToString(" · ")

    /**
     * The failures it reports count unreadable or unparseable files and tracks without enough
     * timestamped points to place on the timeline.
     */
    fun importGpx(uris: List<Uri>) = launchClaimed(Running.ImportingGpx(GpxImportProgress(0, uris.size, 0))) {
        var imported = 0
        var duplicates = 0
        var overlapping = 0
        var failed = 0
        withContext(Dispatchers.IO) {
            val resolver = app.contentResolver
            for ((index, uri) in uris.withIndex()) {
                // One unreadable file counts as failed, and the rest still import.
                val counts = failureAsNull("gpx import of $uri") {
                    val parsed = resolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: emptyList()
                    val importable = parsed.mapNotNull { GpxParser.toImportable(it) }
                    failed += parsed.size - importable.size
                    if (parsed.isEmpty()) failed++ // a readable file with no tracks at all
                    repository.importTracks(importable)
                }
                if (counts == null) {
                    failed++
                } else {
                    imported += counts.imported
                    duplicates += counts.duplicates
                    overlapping += counts.overlapping
                }
                running.value = Running.ImportingGpx(GpxImportProgress(index + 1, uris.size, imported))
            }
        }
        gpxImportMessage(GpxImportSummary(imported, duplicates, overlapping, failed))
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
