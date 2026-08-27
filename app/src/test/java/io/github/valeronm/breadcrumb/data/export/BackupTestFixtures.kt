package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlinx.coroutines.test.runTest
import java.io.StringWriter

/** A [BackupExporter.writeJson] document over in-memory data — the shared test fixture. */
internal fun exportJson(
    tracks: List<Track> = emptyList(),
    points: Map<Long, List<TrackPoint>> = emptyMap(),
    places: List<Place> = emptyList(),
): String {
    val out = StringWriter()
    runTest {
        BackupExporter.writeJson(
            out,
            5_000L,
            // filterKeys, not associateWith: the read this stands in for leaves a track with no
            // fixes out of the map rather than mapping it to an empty list.
            BackupExporter.Content(tracks, { ids -> points.filterKeys { it in ids } }, places),
        )
    }
    return out.toString()
}
