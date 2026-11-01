package io.github.valeronm.breadcrumb.data

import android.app.Application
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.domain.TrackSettle
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

private const val BACKUP_PROPERTY = "settlePreview.backup"
private const val REPORT_PROPERTY = "settlePreview.report"

/**
 * What the settle sweep would rewrite in a backup under the rules in the working tree; the
 * `previewSettle` task runs it. A backup holds the flags of the build that exported it, so an export
 * from the installed build previews the sweep a [TrackSettle.RULE_VERSION] bump runs on the phone.
 * Robolectric supplies the framework's distance formula behind [TrackSettler.verdict]. A backup
 * rounds its fixes ([io.github.valeronm.breadcrumb.data.export.BackupExporter]), so a stop at the
 * edge of a rule can come out differently here than on the phone. Not a test: it asserts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettlePreview {

    private class Rewrite(val track: Track, val changes: List<String>, val metersBefore: Double, val metersAfter: Double)

    private fun key(track: Track) = "${track.activityType} ${track.source}"

    @Test
    fun preview() = runBlocking {
        val backup = File(requireNotNull(System.getProperty(BACKUP_PROPERTY)) { "pass -Pbackup=<file>" })
        val report = File(requireNotNull(System.getProperty(REPORT_PROPERTY)))
        val read = sortedMapOf<String, Int>()
        val rewrites = mutableListOf<Rewrite>()
        GZIPInputStream(backup.inputStream()).bufferedReader().use { reader ->
            BackupImporter.parse(
                reader,
                onTrack = { track, points, _ ->
                    read.merge(key(track), 1, Int::plus)
                    val endedAt = track.endedAt ?: return@parse
                    val verdict = TrackSettler.verdict(track, endedAt, points)
                    if (verdict.changed) {
                        val changes = changes(verdict, track, endedAt, points)
                        rewrites += Rewrite(track, changes, TrackStats.of(points).distanceMeters, verdict.stats.distanceMeters)
                    }
                },
                onPlaces = {},
            )
        }
        val summary = summary(backup, read, rewrites)
        report.parentFile?.mkdirs()
        report.writeText(summary + "\n" + details(rewrites))
        println(summary)
        println("Report: $report")
    }

    private fun changes(verdict: TrackSettler.Applied, track: Track, endedAt: Long, points: List<TrackPoint>): List<String> {
        val plan = verdict.settled.plan
        val flagged = plan.ignore.values.groupingBy { it.code }.eachCount().map { (code, n) -> "+$n $code" }
        val restored = plan.restore.groupingBy { checkNotNull(points[it].ignoreReason) }.eachCount()
            .map { (code, n) -> "-$n $code" }
        val start = "start %+.1fs".format((verdict.bounds.startedAt - track.startedAt) / 1000.0).takeIf { verdict.movedStart }
        val end = "end %+.1fs".format((verdict.bounds.endedAt - endedAt) / 1000.0).takeIf { verdict.movedEnd }
        return flagged + restored + listOfNotNull(start, end)
    }

    private fun summary(backup: File, read: Map<String, Int>, rewrites: List<Rewrite>): String = buildString {
        val byKey = rewrites.groupBy { key(it.track) }
        appendLine("${backup.name} under settle rule v${TrackSettle.RULE_VERSION}")
        appendLine("${read.values.sum()} tracks read, ${rewrites.size} rewritten")
        for ((key, n) in read) {
            val changed = byKey[key] ?: continue
            val meters = changed.sumOf { it.metersAfter - it.metersBefore }.roundToLong()
            appendLine("  $key: ${changed.size} of $n, ${"%+d".format(meters)} m")
        }
    }

    private fun details(rewrites: List<Rewrite>): String = buildString {
        val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        for (r in rewrites.sortedBy { it.track.startedAt }) {
            appendLine(
                "${r.track.id}  ${clock.format(Instant.ofEpochMilli(r.track.startedAt))}  ${key(r.track)}  " +
                    "${r.changes.joinToString(", ")}  ${r.metersBefore.roundToLong()} -> ${r.metersAfter.roundToLong()} m",
            )
        }
    }
}
