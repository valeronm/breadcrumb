package io.github.valeronm.breadcrumb.data

import android.app.Application
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.domain.StopFinder
import io.github.valeronm.breadcrumb.domain.overlapMs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

private const val BACKUP_PROPERTY = "stopPreview.backup"
private const val REPORT_PROPERTY = "stopPreview.report"
private const val AGAINST_PROPERTY = "stopPreview.against"

/** A gap between one `against` track's `endedAt` and the next one's `startedAt` counts as a stop from this length. */
private const val KEY_STOP_MS = 60_000L

/**
 * Robolectric supplies the framework's distance formula. Not a test: it asserts nothing, and only
 * `previewStops` runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StopPreview {

    private class Scanned(
        val track: Track,
        val endedAt: Long,
        val stops: List<StopFinder.Stop>,
        val withEdges: List<StopFinder.Stop>,
    )

    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    @Test
    fun preview() = runBlocking {
        val backup = File(requireNotNull(System.getProperty(BACKUP_PROPERTY)) { "pass -Pbackup=<file>" })
        val report = File(requireNotNull(System.getProperty(REPORT_PROPERTY)))
        val against = System.getProperty(AGAINST_PROPERTY)?.let(::File)
        val scanned = read(backup, scoring = against != null)
        val comparison = against?.let { comparison(scanned, boundaries(it)) }
        val summary = "${backup.name}: ${scanned.size} recorded tracks, ${scanned.sumOf { it.stops.size }} stops"
        report.parentFile?.mkdirs()
        report.writeText(listOfNotNull(summary, comparison, listing(scanned)).joinToString("\n"))
        println(summary)
        comparison?.let(::print)
        println("Report: $report")
    }

    private suspend fun forEachRecorded(backup: File, action: (Track, Long, List<TrackPoint>) -> Unit) {
        GZIPInputStream(backup.inputStream()).bufferedReader().use { reader ->
            BackupImporter.parse(
                reader,
                onTrack = { track, points, _ ->
                    val endedAt = track.endedAt
                    if (StopFinder.runsFor(track.source) && endedAt != null) action(track, endedAt, points)
                },
                onPlaces = {},
            )
        }
    }

    private suspend fun read(backup: File, scoring: Boolean): List<Scanned> {
        val out = mutableListOf<Scanned>()
        forEachRecorded(backup) { track, endedAt, points ->
            val stops = StopFinder.find(points, StopFinder.SHIPPED, AndroidDistance)
            val withEdges = if (scoring) {
                // Only a negative tolerance keeps every stop the edge trim owns, one at the first or last fix included.
                StopFinder.find(points, StopFinder.SHIPPED.copy(edgeToleranceMs = -1), AndroidDistance)
            } else {
                emptyList()
            }
            out += Scanned(track, endedAt, stops, withEdges)
        }
        return out.sortedBy { it.track.startedAt }
    }

    private class Boundary(val closedAt: Long, val openedAt: Long)

    private suspend fun boundaries(backup: File): List<Boundary> {
        val spans = mutableListOf<Pair<Long, Long>>()
        forEachRecorded(backup) { track, endedAt, _ -> spans += track.startedAt to endedAt }
        return spans.sortedBy { it.first }
            .zipWithNext { a, b -> Boundary(closedAt = a.second, openedAt = b.first) }
            .filter { it.openedAt - it.closedAt >= KEY_STOP_MS }
    }

    private fun StopFinder.Stop.overlaps(b: Boundary) = overlapMs(startTs, endTs, b.closedAt, b.openedAt) > 0

    private fun Scanned.encloses(b: Boundary) = track.startedAt < b.closedAt && b.openedAt < endedAt

    private fun comparison(scanned: List<Scanned>, key: List<Boundary>): String = buildString {
        val inside = key.mapNotNull { b -> scanned.firstOrNull { it.encloses(b) }?.let { b to it } }
        val (found, notFound) = inside.partition { (b, s) -> s.stops.any { it.overlaps(b) } }
        val (atEdge, missed) = notFound.partition { (b, s) -> s.withEdges.any { it.overlaps(b) } }
        val extra = scanned.flatMap { s -> s.stops.filterNot { stop -> key.any { stop.overlaps(it) } }.map { s to it } }
        appendLine(
            "Against the reference backup: found ${found.size}, at the edge ${atEdge.size}, missed ${missed.size}, " +
                "extra ${extra.size}",
        )
        for ((b, s) in atEdge) appendLine("  edge   ${time(b.closedAt)} → ${time(b.openedAt)} inside track ${s.track.id}")
        for ((b, s) in missed) appendLine("  missed ${time(b.closedAt)} → ${time(b.openedAt)} inside track ${s.track.id}")
        for ((s, stop) in extra) {
            appendLine("  extra  ${time(stop.startTs)} → ${time(stop.endTs)} in track ${s.track.id}  ${stop.signals.joinToString(", ")}")
        }
    }

    private fun listing(scanned: List<Scanned>): String = buildString {
        for (s in scanned.filter { it.stops.isNotEmpty() }) {
            appendLine("${s.track.id}  ${s.track.activityType}  ${time(s.track.startedAt)} → ${time(s.endedAt)}")
            for (stop in s.stops) {
                appendLine(
                    "    ${time(stop.startTs)} → ${time(stop.endTs)}  ${(stop.endTs - stop.startTs) / 1000} s  " +
                        "spread ${stop.spreadM.roundToLong()} m  ${stop.signals.joinToString(", ")}",
                )
            }
        }
    }

    private fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms))
}
