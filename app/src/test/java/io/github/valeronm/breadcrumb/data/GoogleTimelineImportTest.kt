package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.data.export.GoogleTimelineImporter
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.StringReader
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class GoogleTimelineImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target = TestDb()
    private val repositories = BackupRepositories(
        tracks = target.repository,
        places = PlaceRepository(context, target.db),
        derivation = DerivationStore(context, target.db),
    )

    @After fun tearDown() = target.close()

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun activity(startMs: Long, endMs: Long, fromLat: Double, toLat: Double, type: String = "WALKING") =
        """{"startTime":"${iso(startMs)}","endTime":"${iso(endMs)}","activity":{"start":{"latLng":"$fromLat°, -2.0°"},""" +
            """"end":{"latLng":"$toLat°, -2.0°"},"topCandidate":{"type":"$type"}}}"""

    private fun visit(startMs: Long, lat: Double, semanticType: String) =
        """{"startTime":"${iso(startMs)}","endTime":"${iso(startMs + 60_000)}","visit":{"topCandidate":""" +
            """{"placeId":"home","semanticType":"$semanticType","placeLocation":{"latLng":"$lat°, -2.0°"}}}}"""

    private fun path(startMs: Long, fromLat: Double, count: Int) =
        """{"startTime":"${iso(startMs)}","endTime":"${iso(startMs + count * 60_000L)}","timelinePath":[""" +
            (1..count).joinToString(",") {
                """{"point":"${fromLat + it * 0.001}°, -2.0°","time":"${iso(startMs + it * 60_000L)}"}"""
            } +
            "]}"

    private fun doc(vararg segments: String) = """{"semanticSegments":[${segments.joinToString(",")}]}"""

    private suspend fun import(json: String) = GoogleTimelineImporter.import(
        StringReader(json),
        repositories,
        placeLabel = { if (it == PlaceCategory.HOME) "Home" else "Work" },
        nowMs = TEST_START,
    )

    private val out = TEST_START
    private val back = TEST_START + 3 * 3_600_000L

    private val twoTripsAndAHome = doc(
        visit(out - 3_600_000L, 1.0, "HOME"),
        activity(out, out + 11 * 60_000L, 1.0, 1.011),
        path(out, 1.0, 10),
        path(out + 60 * 60_000L, 1.011, 3),
        activity(back, back + 11 * 60_000L, 1.011, 1.0, type = "IN_BUS"),
        visit(back + 12 * 60_000L, 1.0, "HOME"),
        """{"startTime":"bad","endTime":"bad","activity":{}}""",
    )

    @Test fun `each activity becomes a Google Timeline track with its stats settled`() = runTest {
        val summary = import(twoTripsAndAHome)
        assertEquals(2, summary.tracks)
        assertEquals(1, summary.skipped)
        val tracks = target.repository.exportTracks()
        assertEquals(listOf("WALKING", "TRANSIT"), tracks.map { it.activityType })
        tracks.forEach {
            assertEquals(TrackOrigin.GOOGLE_TIMELINE.code, it.source)
            target.assertStatsMatchPoints(it.id)
        }
        assertEquals(12, target.dao.allPointsFor(tracks[0].id).size)
        assertEquals(2, target.dao.allPointsFor(tracks[1].id).size)
    }

    @Test fun `a home the visits agree on becomes a named, tagged place`() = runTest {
        val summary = import(twoTripsAndAHome)
        assertEquals(1, summary.places)
        val place = target.db.placeDao().allPlaces().single()
        assertEquals("Home", place.label)
        assertEquals(PlaceCategory.HOME, place.placeCategory)
    }

    @Test fun `the stays are derived exactly as a fresh pass would derive them`() = runTest {
        import(twoTripsAndAHome)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `a sample the walk could not have reached is stored as a jump and left out of the distance`() = runTest {
        val farSample = """{"startTime":"${iso(out)}","endTime":"${iso(out + 3 * 60_000L)}","timelinePath":[""" +
            """{"point":"1.001°, -2.0°","time":"${iso(out + 60_000L)}"},""" +
            """{"point":"1.05°, -2.0°","time":"${iso(out + 2 * 60_000L)}"}]}"""
        import(doc(activity(out, out + 3 * 60_000L, 1.0, 1.002), farSample))
        val track = target.repository.exportTracks().single()
        val points = target.dao.allPointsFor(track.id)
        assertEquals(listOf(null, null, IgnoreReason.JUMP.code, null), points.map { it.ignoreReason })
        target.assertStatsMatchPoints(track.id)
        assertTrue(track.distanceMeters < 500.0)
    }

    @Test fun `a file with no readable trip is refused and writes nothing`() = runTest {
        val failure = runCatching { import(doc(visit(out, 1.0, "HOME"))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(target.repository.exportTracks().isEmpty())
        assertTrue(target.db.placeDao().allPlaces().isEmpty())
    }
}
