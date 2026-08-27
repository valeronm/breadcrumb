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
            BackupExporter.Content(tracks, { points[it].orEmpty() }, places),
        )
    }
    return out.toString()
}
