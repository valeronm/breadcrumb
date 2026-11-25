package io.github.valeronm.breadcrumb.data

import android.app.Application
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.domain.StopFinder
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

private const val BACKUP_PROPERTY = "stepPreview.backup"
private const val REPORT_PROPERTY = "stepPreview.report"

/**
 * Robolectric supplies the framework's distance formula. Not a test: it asserts nothing, and only
 * `previewSteps` runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StepPreview {

    private class Span(val label: String, val points: List<TrackPoint>)

    private class Counted(val track: Track, val endedAt: Long, val points: List<TrackPoint>)

    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val hour = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    @Test
    fun preview() = runBlocking {
        val backup = File(requireNotNull(System.getProperty(BACKUP_PROPERTY)) { "pass -Pbackup=<file>" })
        val report = File(requireNotNull(System.getProperty(REPORT_PROPERTY)))
        val counted = read(backup)
        val summary = "${backup.name}: ${counted.size} tracks carry step totals"
        report.parentFile?.mkdirs()
        report.writeText("$summary\n${listing(counted)}")
        println(summary)
        println("Report: $report")
    }

    private suspend fun read(backup: File): List<Counted> {
        val out = mutableListOf<Counted>()
        GZIPInputStream(backup.inputStream()).bufferedReader().use { reader ->
            BackupImporter.parse(
                reader,
                onTrack = { track, raw, _ ->
                    val endedAt = track.endedAt
                    if (endedAt != null && raw.any { it.steps != null }) {
                        out += Counted(track, endedAt, TrackSettler.verdict(track, endedAt, raw).settled.points)
                    }
                },
                onPlaces = {},
            )
        }
        return out.sortedBy { it.track.startedAt }
    }

    /** Empty for a track with no stop. */
    private fun spans(points: List<TrackPoint>): List<Span> {
        val stops = StopFinder.find(points, StopFinder.SHIPPED, AndroidDistance)
        if (stops.isEmpty()) return emptyList()
        return buildList {
            var from = Long.MIN_VALUE
            for (stop in stops) {
                add(Span("moving", points.filter { it.timestamp in from until stop.startTs }))
                add(Span("stop ${stop.signals.joinToString(",")}", points.filter { it.timestamp in stop.startTs..stop.endTs }))
                from = stop.endTs + 1
            }
            add(Span("moving", points.filter { it.timestamp >= from }))
        }.filter { it.points.size >= 2 }
    }

    private fun listing(counted: List<Counted>): String = buildString {
        for (c in counted) {
            appendLine(
                "${c.track.id}  ${c.track.activityType}  ${c.track.source}  " +
                    "${clock.format(Instant.ofEpochMilli(c.track.startedAt))} → ${hour.format(Instant.ofEpochMilli(c.endedAt))}",
            )
            appendLine("    whole                  ${figures(c.points)}")
            for (span in spans(c.points)) {
                val at = hour.format(Instant.ofEpochMilli(span.points.first().timestamp))
                appendLine("    $at ${span.label.padEnd(14)} ${figures(span.points)}")
            }
        }
    }

    /** A drop in the step total is a reboot. */
    private fun figures(points: List<TrackPoint>): String {
        val good = points.filterNot { it.ignored }
        if (good.size < 2) return "-"
        val meters = TrackStats.of(good, AndroidDistance).distanceMeters
        val minutes = (good.last().timestamp - good.first().timestamp) / 60_000.0
        val totals = good.mapNotNull { it.steps }
        val deltas = totals.zipWithNext { a, b -> b - a }
        val steps = deltas.filter { it >= 0 }.sum()
        val restarts = deltas.count { it < 0 }
        return "%7.0f m %6.1f min %6d steps %6.1f/min %6s m/step %5.1f km/h%s".format(
            meters,
            minutes,
            steps,
            if (minutes > 0) steps / minutes else 0.0,
            if (steps > 0) "%.2f".format(meters / steps) else "-",
            if (minutes > 0) meters / 1000 / (minutes / 60) else 0.0,
            if (restarts > 0) "  restarts $restarts" else "",
        )
    }
}
