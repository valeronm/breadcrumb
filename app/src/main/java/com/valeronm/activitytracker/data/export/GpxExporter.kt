package com.valeronm.activitytracker.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.valeronm.activitytracker.data.TrackRepository
import com.valeronm.activitytracker.data.db.Track
import com.valeronm.activitytracker.data.db.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Serialises a stored track to a GPX 1.1 file and returns a shareable content Uri. */
object GpxExporter {

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    suspend fun export(context: Context, repository: TrackRepository, trackId: Long): Uri? {
        val track = repository.track(trackId) ?: return null
        val points = repository.pointsFor(trackId)
        if (points.isEmpty()) return null

        val gpx = buildGpx(track, points)

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(track.startedAt))
        val file = File(dir, "${track.activityType.lowercase(Locale.US)}-$stamp.gpx")
        file.writeText(gpx)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun buildGpx(track: Track, points: List<TrackPoint>): String {
        val iso = isoFormatter()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="ActivityGpsTracker" """ +
                """xmlns="http://www.topografix.com/GPX/1/1">""",
        ).append('\n')
        sb.append("  <trk>\n")
        sb.append("    <name>${track.activityType} ${iso.format(Date(track.startedAt))}</name>\n")
        sb.append("    <type>${track.activityType}</type>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("""      <trkpt lat="${p.latitude}" lon="${p.longitude}">""").append('\n')
            p.altitude?.let { sb.append("        <ele>$it</ele>\n") }
            sb.append("        <time>${iso.format(Date(p.timestamp))}</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
