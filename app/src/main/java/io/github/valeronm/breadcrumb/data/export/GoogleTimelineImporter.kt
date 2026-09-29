package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import java.io.InputStreamReader
import java.io.Reader

/**
 * Loads a Google Timeline export beside the history already kept. A trip a kept track overlaps is
 * skipped. A Google place becomes a row only where a loaded trip starts or ends, or when it is a home
 * or a work, since that category is all an unvisited row carries. A place an earlier import made,
 * found by Google's id, and a home or a work the history already holds take the trip ends Google
 * joined to their own, and those rows are not written.
 */
internal object GoogleTimelineImporter {

    /** [io.github.valeronm.breadcrumb.data.db.Place.externalProvider] on the places an import makes,
     *  whose `externalId` is Google's place id. */
    const val PROVIDER = "google"

    /** [overlapping] counts the trips a kept track covered, [skipped] the entries that could not be read. */
    class Summary(val tracks: Int, val places: Int, val overlapping: Int, val skipped: Int)

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
                val maxAccuracyM = Settings.accuracyGateM(context).toFloat()
                import(reader, repositories, placeLabel, maxAccuracyM, System.currentTimeMillis(), onProgress)
            }
        }
    }

    // Each parameter is an independent input from the caller.
    @Suppress("LongParameterList")
    internal suspend fun import(
        reader: Reader,
        repositories: BackupRepositories,
        placeLabel: (PlaceCategory) -> String,
        maxAccuracyM: Float,
        nowMs: Long,
        onProgress: (tracksDone: Int, tracksTotal: Int?) -> Unit = { _, _ -> },
    ): Summary {
        val export = GoogleTimelineParser.parse(reader)
        require(export.activities.isNotEmpty()) { "no readable trip in the Google Timeline export" }
        val taken = TakenSpans(repositories.tracks.keptSpans())
        val (loaded, overlapping) = export.activities.partition { !taken.overlaps(it.startMs, it.endMs) }
        val ends = GoogleTimelineTracks.Ends(export.visits)
        val stated = loaded.flatMapTo(HashSet()) { listOfNotNull(ends.startOf(it), ends.endOf(it)) }
        val candidates = GoogleTimelinePlaces.places(export.visits)
        val existing = GoogleTimelinePlaces.matchExisting(
            candidates,
            repositories.places.allPlaces(),
            AndroidDistance,
        )
        val added = candidates.filter {
            it.placeId !in existing && (it.placeId in stated || it.category != null)
        }
        val places = added.map {
            Place(
                label = it.category?.let(placeLabel),
                lat = it.lat,
                lon = it.lon,
                createdAt = nowMs,
                radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
                category = it.category?.code,
                externalProvider = PROVIDER,
                externalId = it.placeId,
            )
        }
        val rowOf = existing + added.map { it.placeId }.zip(repositories.places.restorePlaces(places))
        val total = loaded.size
        var done = 0
        onProgress(0, total)
        GoogleTimelineTracks.build(export, rowOf::get, maxAccuracyM, activities = loaded)
            .chunked(BackupImporter.INSERT_BATCH)
            .forEach { batch ->
                repositories.tracks.insertBackupTracks(batch)
                done += batch.size
                onProgress(done, total)
            }
        // The places are seeds, and one pass over every track costs less than a repair per batch.
        repositories.derivation.reconcile(stale = done > 0)
        return Summary(done, places.size, overlapping.size, export.skipped)
    }
}
