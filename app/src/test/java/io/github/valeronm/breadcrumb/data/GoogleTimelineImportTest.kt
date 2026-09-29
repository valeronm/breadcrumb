package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.data.export.GoogleTimelineImporter
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val places = PlaceRepository(context, target.db)
    private val repositories = BackupRepositories(
        tracks = target.repository,
        places = places,
        derivation = DerivationStore(context, target.db),
    )

    @After fun tearDown() = target.close()

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun activity(startMs: Long, endMs: Long, fromLat: Double, toLat: Double, type: String = "WALKING") =
        """{"startTime":"${iso(startMs)}","endTime":"${iso(endMs)}","activity":{"start":{"latLng":"$fromLat°, -2.0°"},""" +
            """"end":{"latLng":"$toLat°, -2.0°"},"topCandidate":{"type":"$type"}}}"""

    private fun visit(startMs: Long, lat: Double, semanticType: String, endMs: Long = startMs + 60_000, placeId: String = "home") =
        """{"startTime":"${iso(startMs)}","endTime":"${iso(endMs)}","visit":{"hierarchyLevel":0,"topCandidate":""" +
            """{"placeId":"$placeId","semanticType":"$semanticType","placeLocation":{"latLng":"$lat°, -2.0°"}}}}"""

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
        maxAccuracyM = 50f,
        nowMs = TEST_START,
    )

    private val out = TEST_START
    private val back = TEST_START + 3 * 3_600_000L

    private val twoTripsAndAHome = doc(
        visit(out - 3_600_000L, 1.0, "HOME", endMs = out),
        activity(out, out + 11 * 60_000L, 1.0, 1.011),
        path(out, 1.0, 10),
        path(out + 60 * 60_000L, 1.011, 3),
        activity(back, back + 11 * 60_000L, 1.011, 1.0, type = "IN_BUS"),
        visit(out + 11 * 60_000L, 1.011, "UNKNOWN", endMs = back, placeId = "cafe"),
        visit(back + 11 * 60_000L, 1.0, "HOME"),
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

    private suspend fun placeRow(googleId: String) =
        target.db.placeDao().allPlaces().single { it.externalId == googleId }

    @Test fun `every Google place is a place row, and only a home the visits agree on is named`() = runTest {
        val summary = import(twoTripsAndAHome)
        assertEquals(2, summary.places)
        val home = placeRow("home")
        assertEquals("Home", home.label)
        assertEquals(PlaceCategory.HOME, home.placeCategory)
        assertEquals(GoogleTimelineImporter.PROVIDER, home.externalProvider)
        val cafe = placeRow("cafe")
        assertNull(cafe.label)
        assertNull(cafe.category)
    }

    @Test fun `each trip end is stated to the visit Google joined it to`() = runTest {
        import(twoTripsAndAHome)
        val (outbound, inbound) = target.repository.exportTracks()
        val home = placeRow("home").id
        val cafe = placeRow("cafe").id
        assertEquals(home to cafe, outbound.startPlaceId to outbound.endPlaceId)
        assertEquals(cafe to home, inbound.startPlaceId to inbound.endPlaceId)
    }

    @Test fun `deleting a place clears the ends stated to it, and undoing it states them again`() = runTest {
        import(twoTripsAndAHome)
        val cafe = placeRow("cafe")
        val removal = places.delete(cafe)
        val (outbound, inbound) = target.repository.exportTracks()
        assertNull(outbound.endPlaceId)
        assertNull(inbound.startPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)

        places.restore(removal)
        val (outboundAgain, inboundAgain) = target.repository.exportTracks()
        assertEquals(cafe, placeRow("cafe"))
        assertEquals(cafe.id, outboundAgain.endPlaceId)
        assertEquals(cafe.id, inboundAgain.startPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `naming an imported place writes the name onto its row`() = runTest {
        import(twoTripsAndAHome)
        val cafe = placeRow("cafe")
        places.save(cafe.copy(label = "Cafe"))
        val named = placeRow("cafe")
        assertEquals(cafe.id, named.id)
        assertEquals("Cafe", named.label)
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

    private val homeCafeShop = doc(
        visit(out - 3_600_000L, 1.0, "HOME", endMs = out),
        activity(out, out + 11 * 60_000L, 1.0, 1.011),
        visit(out + 11 * 60_000L, 1.011, "UNKNOWN", endMs = back, placeId = "cafe"),
        activity(back, back + 11 * 60_000L, 1.011, 1.02),
        visit(back + 11 * 60_000L, 1.02, "UNKNOWN", placeId = "shop"),
    )

    @Test fun `a trip a kept track overlaps is skipped, and the other loads`() = runTest {
        val recorded = target.walk(back + 60_000L, 0, 5)
        val summary = import(homeCafeShop)
        assertEquals(1, summary.tracks)
        assertEquals(1, summary.overlapping)
        val tracks = target.repository.exportTracks()
        assertEquals(listOf(TrackOrigin.GOOGLE_TIMELINE.code, TrackOrigin.RECORDED.code), tracks.map { it.source })
        assertEquals(recorded, tracks[1].id)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `a place only a skipped trip reached is not made`() = runTest {
        target.walk(back + 60_000L, 0, 5)
        val summary = import(homeCafeShop)
        assertEquals(2, summary.places)
        val made = target.db.placeDao().allPlaces().map { it.externalId }.toSet()
        assertEquals(setOf("home", "cafe"), made)
    }

    @Test fun `a home stands with no trip loaded there`() = runTest {
        target.walk(out + 60_000L, 0, 5)
        target.walk(back + 60_000L, 5, 0)
        val summary = import(twoTripsAndAHome)
        assertEquals(0, summary.tracks)
        assertEquals(2, summary.overlapping)
        assertEquals(listOf("home"), target.db.placeDao().allPlaces().mapNotNull { it.externalId })
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `a later export states its trip ends to the rows an earlier one made`() = runTest {
        import(
            doc(
                visit(out - 3_600_000L, 1.0, "HOME", endMs = out),
                activity(out, out + 11 * 60_000L, 1.0, 1.011),
                visit(out + 11 * 60_000L, 1.011, "UNKNOWN", endMs = back, placeId = "cafe"),
            ),
        )
        val cafe = placeRow("cafe")
        val summary = import(homeCafeShop)
        assertEquals(1, summary.tracks)
        assertEquals(1, summary.overlapping)
        assertEquals(1, summary.places)
        assertEquals(cafe, placeRow("cafe"))
        val (_, later) = target.repository.exportTracks()
        assertEquals(cafe.id to placeRow("shop").id, later.startPlaceId to later.endPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `a home the history holds takes Google's trip ends, and its row is not written`() = runTest {
        val ownId = places.create(target.place("Flat", 1.0005, -2.0).copy(category = PlaceCategory.HOME.code))
        val own = target.db.placeDao().allPlaces().single()
        val summary = import(twoTripsAndAHome)
        assertEquals(1, summary.places)
        assertTrue(target.db.placeDao().allPlaces().none { it.externalId == "home" })
        assertEquals(own, target.db.placeDao().allPlaces().single { it.id == ownId })
        val (outbound, inbound) = target.repository.exportTracks()
        assertEquals(ownId, outbound.startPlaceId)
        assertEquals(ownId, inbound.endPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(target.db, back + 86_400_000L)
    }

    @Test fun `an untagged place the history holds does not absorb Google's place beside it`() = runTest {
        places.create(target.place("Cafe", 1.011, -2.0))
        import(twoTripsAndAHome)
        val cafe = placeRow("cafe")
        val (outbound, _) = target.repository.exportTracks()
        assertEquals(cafe.id, outbound.endPlaceId)
    }

    @Test fun `a trip ending in a linger keeps its tail, which the overrun rule would take off`() = runTest {
        val steps = (1..40).map { i ->
            val lat = if (i <= 30) 1.0 + i * 0.00018 else 1.0 + 30 * 0.00018 + if (i % 2 == 0) 0.00008 else -0.00008
            """{"point":"$lat°, -2.0°","time":"${iso(out + i * 15_000L)}"}"""
        }
        val path = """{"startTime":"${iso(out)}","endTime":"${iso(out + 41 * 15_000L)}","timelinePath":[""" +
            steps.joinToString(",") + "]}"
        import(doc(activity(out, out + 41 * 15_000L, 1.0, 1.0 + 30 * 0.00018), path))
        val track = target.repository.exportTracks().single()
        val points = target.dao.allPointsFor(track.id)
        assertTrue(points.none { it.ignoreReason == IgnoreReason.EDGE_STAY.code })
        assertEquals(points.last().timestamp, track.endedAt)
        val recorderPlan = EdgeStayIgnore.settle(
            points, track.startedAt, track.endedAt!!, EdgeStayDetector.BRIEF_STOP, AndroidDistance,
        ).plan
        assertTrue(recorderPlan.ignore.isNotEmpty())
    }

    @Test fun `a file with no readable trip is refused and writes nothing`() = runTest {
        val failure = runCatching { import(doc(visit(out, 1.0, "HOME"))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(target.repository.exportTracks().isEmpty())
        assertTrue(target.db.placeDao().allPlaces().isEmpty())
    }
}
