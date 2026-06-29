package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Serialises a stored track to a GPX 1.1 file and returns a shareable content Uri. */
object GpxExporter {

    /** MIME type for the GPX documents this exporter produces. */
    const val MIME_TYPE = "application/gpx+xml"

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Loads a track and renders its GPX document, or null if the track is missing/empty. */
    private suspend fun gpxFor(repository: TrackRepository, trackId: Long): Pair<Track, String>? {
        val track = repository.track(trackId) ?: return null
        val points = repository.pointsFor(trackId)
        if (points.isEmpty()) return null
        return track to buildGpx(track, points)
    }

    suspend fun export(context: Context, repository: TrackRepository, trackId: Long): Uri? {
        val (track, gpx) = gpxFor(repository, trackId) ?: return null

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(track))
        file.writeText(gpx)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * Writes every track as its own .gpx file into the user-picked folder [treeUri] (from the
     * system folder picker). Returns the number of tracks exported.
     */
    suspend fun exportAllToTree(context: Context, repository: TrackRepository, treeUri: Uri): Int {
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        var exported = 0
        for (trackId in repository.allTrackIds()) {
            val (track, gpx) = gpxFor(repository, trackId) ?: continue
            val file = dir.createFile(MIME_TYPE, fileName(track)) ?: continue
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(gpx.toByteArray())
            } ?: continue
            exported++
        }
        return exported
    }

    private fun fileName(track: Track): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(track.startedAt))
        return "${track.activityType.lowercase(Locale.US)}-$stamp.gpx"
    }

    private fun buildGpx(track: Track, points: List<TrackPoint>): String {
        val iso = isoFormatter()
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(
                """<gpx version="1.1" creator="Breadcrumb" """ +
                    """xmlns="http://www.topografix.com/GPX/1/1">""",
            )
            appendLine("  <trk>")
            appendLine("    <name>${track.activityType} ${iso.format(Date(track.startedAt))}</name>")
            appendLine("    <type>${track.activityType}</type>")
            appendLine("    <trkseg>")
            for ((i, p) in points.withIndex()) {
                // A segment start (the fix after an auto-pause resumed) opens a fresh <trkseg> so the
                // paused gap isn't drawn as a connecting line by GPX consumers.
                if (i > 0 && p.segmentStart) {
                    appendLine("    </trkseg>")
                    appendLine("    <trkseg>")
                }
                appendLine("""      <trkpt lat="${p.latitude}" lon="${p.longitude}">""")
                p.altitude?.let { appendLine("        <ele>$it</ele>") }
                appendLine("        <time>${iso.format(Date(p.timestamp))}</time>")
                appendLine("      </trkpt>")
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
            appendLine("</gpx>")
        }
    }
}
