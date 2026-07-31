package io.github.valeronm.breadcrumb.location

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Replays real recorded fixes back through [FixIngest] and checks it reaches the same verdict the
 * recorder did when it stored them. The oracle is the user's own history rather than a fixture: the
 * rules each have their own suite, but the loop that sequences them had none, and hand-written cases
 * only ever test the cases their author thought of.
 *
 * **Recorded tracks only.** An imported one never ran through this path at all — its flags come from
 * the import's leading-stray rule — so replaying it would compare the recorder against a different
 * algorithm. `tracks.source` is what makes the filter possible.
 *
 * Needs an export, which never lives in the repo:
 * `BREADCRUMB_EXPORT=~/path/breadcrumb-*.json.gz ./gradlew :app:testDebugUnitTest --tests '*ReplayTest*'`
 * Skipped without one, so CI and a fresh checkout stay green.
 */
class FixIngestReplayTest {

    /** A stored point, reduced to what a replay needs. */
    private class Stored(
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val altitude: Double?,
        val accuracy: Float?,
        val speed: Float?,
        val bearing: Float?,
        val ignoreReason: String?,
        val segmentStart: Boolean,
    )

    private class Replayed(val tracks: Int, val points: Int, val mismatches: List<String>)

    @Test fun `the extracted ingest reaches the recorder's own verdicts`() {
        val path = System.getenv("BREADCRUMB_EXPORT")
        assumeTrue("set BREADCRUMB_EXPORT to a backup export to run this", path != null)

        val result = replay(File(path))
        println(
            "replayed ${result.points} fixes over ${result.tracks} recorded tracks, " +
                "${result.mismatches.size} verdicts differing",
        )
        result.mismatches.take(20).forEach(::println)

        assertEquals("verdicts differing from the recorder's", emptyList<String>(), result.mismatches)
    }

    private fun replay(file: File): Replayed {
        var tracks = 0
        var points = 0
        var seen = 0
        var notRecorded = 0
        var unknownLabel = 0
        var retyped = 0
        val mismatches = ArrayList<String>()
        readTracks(file) { activity, source, stored ->
            seen++
            // Only what this path produced: an import's flags are another rule's, and a track that
            // finished as "Moving" was retyped *after* recording, so its stored label is not the one
            // its fixes were judged under. An export written before `source` shipped declares no
            // writer, so it is reconstructed from the fixes exactly as a restore does — a recorded
            // fix carries the accuracy radius the platform gave it, a parsed one has none.
            val origin = source?.let { TrackOrigin.fromCode(it) }
                ?: if (stored.any { it.accuracy != null }) TrackOrigin.RECORDED else TrackOrigin.IMPORTED
            if (origin != TrackOrigin.RECORDED) {
                notRecorded++
                return@readTracks
            }
            val label = ActivityType.ofName(activity)
            if (label == null) {
                unknownLabel++
                return@readTracks
            }
            if (label == ActivityType.UNKNOWN) {
                retyped++
                return@readTracks
            }
            tracks++
            points += stored.size
            mismatches += replayTrack(label, stored)
        }
        println(
            "export held $seen tracks: $tracks replayed, $notRecorded not recorder-written, " +
                "$unknownLabel unrecognised label, $retyped retyped to Moving",
        )
        return Replayed(tracks, points, mismatches)
    }

    private fun replayTrack(label: ActivityType, stored: List<Stored>): List<String> {
        val ingest = FixIngest(wgs84Distance)
        ingest.onTrackOpened(label)
        val gate = GateState(label, stillParked = false)
        val out = ArrayList<String>()
        for (point in stored) {
            // Fed, not reproduced. A segment break is the pause machinery's fact, and GNSS backing
            // needs the monotonic clock the export has no room for — so both are handed in exactly
            // where the recorder had them, and what is compared is everything else.
            if (point.segmentStart) ingest.markSegmentStart()
            val noGnss = point.ignoreReason == IgnoreReason.NO_GNSS.code
            val fix = Fix(
                latitude = point.lat,
                longitude = point.lon,
                altitude = point.altitude,
                accuracy = point.accuracy,
                speed = point.speed,
                bearing = point.bearing,
                timeMs = point.timestamp,
                verticalAccuracy = null,
                speedAccuracy = null,
                bearingAccuracy = null,
                elapsedRealtimeMs = if (noGnss) GNSS_ANCHOR + FixIngest.GNSS_FIX_MAX_AGE_MS + 1 else GNSS_ANCHOR,
            )
            val settings = IngestSettings(
                maxAccuracyM = ACCURACY_GATE_M,
                requireGnss = true,
                // Off for the whole recorded history bar recent debug builds; a track it was on for
                // can only make the replay stricter, never wrong: the witness raises ceilings.
                crossCheckMotion = false,
            )
            val got = ingest.onFixes(
                trackId = 1L,
                fixes = listOf(fix),
                gate = gate,
                settings = settings,
                gnss = GnssState(satellitesInFix = null, cn0Top4 = null, lastFixElapsedMs = GNSS_ANCHOR),
            ).points.single().ignoreReason
            // EDGE_STAY is applied when a track finishes, long after this path saw the fix, so at
            // record time it was on the path.
            val expected = point.ignoreReason?.takeIf { it != IgnoreReason.EDGE_STAY.code }
            if (got != expected) {
                out += "  fix at ${point.timestamp} under $label: recorder said ${expected ?: "good"}, " +
                    "replay says ${got ?: "good"} (acc=${point.accuracy})"
            }
        }
        return out
    }

    /** Streams the export, calling [onTrack] with each track's activity, source and points. */
    private fun readTracks(file: File, onTrack: (String, String?, List<Stored>) -> Unit) {
        JsonReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8)).use { r ->
            var fields: List<String> = emptyList()
            r.eachField { name ->
                when (name) {
                    // The exporter writes this before the tracks, so it is always known by the time
                    // a point row — an array in its order — has to be read.
                    "pointFields" -> fields = buildList { r.eachItem { add(r.nextString()) } }
                    "tracks" -> r.eachItem { readTrack(r, fields, onTrack) }
                    else -> r.skipValue()
                }
            }
        }
    }

    private fun readTrack(r: JsonReader, fields: List<String>, onTrack: (String, String?, List<Stored>) -> Unit) {
        var activity = ""
        var source: String? = null
        var points: List<Stored> = emptyList()
        r.eachField { name ->
            when (name) {
                "activityType" -> activity = r.nextString()
                "source" -> source = r.stringOrNull()
                "points" -> points = readPoints(r, fields)
                else -> r.skipValue()
            }
        }
        onTrack(activity, source, points)
    }

    private fun readPoints(r: JsonReader, fields: List<String>): List<Stored> {
        val at = fields.withIndex().associate { (i, name) -> name to i }
        val out = ArrayList<Stored>()
        r.eachItem {
            val row = buildList { r.eachItem { add(r.stringOrNull()) } }
            fun field(name: String): String? = at[name]?.let { row.getOrNull(it) }
            out += Stored(
                timestamp = field("timestamp")!!.toLong(),
                lat = field("lat")!!.toDouble(),
                lon = field("lon")!!.toDouble(),
                altitude = field("alt")?.toDouble(),
                accuracy = field("accuracy")?.toFloat(),
                speed = field("speed")?.toFloat(),
                bearing = field("bearing")?.toFloat(),
                ignoreReason = field("ignoreReason"),
                segmentStart = field("segmentStart") == "1",
            )
        }
        return out
    }

    private companion object {
        /** Any monotonic base: only the *difference* from a fix's own time is read. */
        const val GNSS_ANCHOR = 1_000_000L

        /** The shipped default; a history recorded under a different gate shows up as accuracy noise. */
        const val ACCURACY_GATE_M = 50f
    }
}

/** Each key of the object at the cursor, the value left for the body to read or skip. */
private inline fun JsonReader.eachField(body: (String) -> Unit) {
    beginObject()
    while (hasNext()) body(nextName())
    endObject()
}

/** Each element of the array at the cursor. */
private inline fun JsonReader.eachItem(body: () -> Unit) {
    beginArray()
    while (hasNext()) body()
    endArray()
}

/** The scalar at the cursor as text, or null — the export writes absence as JSON null throughout. */
private fun JsonReader.stringOrNull(): String? =
    if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else {
        nextString()
    }
