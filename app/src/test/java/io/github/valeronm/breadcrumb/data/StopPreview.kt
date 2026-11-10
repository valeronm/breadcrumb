package io.github.valeronm.breadcrumb.data

import android.app.Application
import io.github.valeronm.breadcrumb.data.db.Track
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
import kotlin.math.roundToLong

private const val BACKUP_PROPERTY = "stopPreview.backup"
private const val REPORT_PROPERTY = "stopPreview.report"

/**
 * Robolectric supplies the framework's distance formula. Not a test: it asserts nothing, and only
 * `previewStops` runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StopPreview {

    private class Scanned(val track: Track, val endedAt: Long, val stops: List<StopFinder.Stop>)

    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    @Test
    fun preview() = runBlocking {
        val backup = File(requireNotNull(System.getProperty(BACKUP_PROPERTY)) { "pass -Pbackup=<file>" })
        val report = File(requireNotNull(System.getProperty(REPORT_PROPERTY)))
        val scanned = read(backup)
        val summary = "${backup.name}: ${scanned.size} recorded tracks, ${scanned.sumOf { it.stops.size }} stops"
        report.parentFile?.mkdirs()
        report.writeText(summary + "\n" + listing(scanned))
        println(summary)
        println("Report: $report")
    }

    private suspend fun read(backup: File): List<Scanned> {
        val out = mutableListOf<Scanned>()
        GZIPInputStream(backup.inputStream()).bufferedReader().use { reader ->
            BackupImporter.parse(
                reader,
                onTrack = { track, points, _ ->
                    val endedAt = track.endedAt
                    if (StopFinder.runsFor(track.source) && endedAt != null) {
                        out += Scanned(track, endedAt, StopFinder.find(points, StopFinder.SHIPPED, AndroidDistance))
                    }
                },
                onPlaces = {},
            )
        }
        return out.sortedBy { it.track.startedAt }
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
