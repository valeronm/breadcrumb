package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import java.io.InputStreamReader
import java.io.Reader

/** Loads a Google Timeline export; the history must be empty, since nothing is merged or deduplicated. */
internal object GoogleTimelineImporter {

    class Summary(val tracks: Int, val places: Int, val skipped: Int)

    /** Returns the counts, or null if the stream couldn't be opened; throws on a file that is not
     *  this format or holds no readable trip. */
    suspend fun importFrom(
        context: Context,
        repositories: BackupRepositories,
        uri: Uri,
        placeLabel: (PlaceCategory) -> String,
        onProgress: (tracksDone: Int, tracksTotal: Int?) -> Unit,
    ): Summary? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { raw ->
            InputStreamReader(raw, Charsets.UTF_8).buffered(BackupExporter.STREAM_BUFFER).use { reader ->
                import(reader, repositories, placeLabel, System.currentTimeMillis(), onProgress)
            }
        }
    }

    internal suspend fun import(
        reader: Reader,
        repositories: BackupRepositories,
        placeLabel: (PlaceCategory) -> String,
        nowMs: Long,
        onProgress: (tracksDone: Int, tracksTotal: Int?) -> Unit = { _, _ -> },
    ): Summary {
        val export = GoogleTimelineParser.parse(reader)
        require(export.activities.isNotEmpty()) { "no readable trip in the Google Timeline export" }
        val places = GoogleTimelinePlaces.vote(export.visits).map {
            Place(
                label = placeLabel(it.category),
                lat = it.lat,
                lon = it.lon,
                createdAt = nowMs,
                radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
                category = it.category.code,
            )
        }
        repositories.places.restorePlaces(places)
        val total = export.activities.size
        var done = 0
        onProgress(0, total)
        GoogleTimelineTracks.build(export.activities, export.path)
            .chunked(BackupImporter.INSERT_BATCH)
            .forEach { batch ->
                repositories.tracks.insertBackupTracks(batch)
                done += batch.size
                onProgress(done, total)
            }
        // The places are seeds, and one pass over every track costs less than a repair per batch.
        repositories.derivation.reconcile(stale = true)
        return Summary(done, places.size, export.skipped)
    }
}
