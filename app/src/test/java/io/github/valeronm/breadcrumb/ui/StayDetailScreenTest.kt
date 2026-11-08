package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.NearbyPlaces
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.ResolvedStayInterval
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.ui.GapFixtures.ORIGIN
import io.github.valeronm.breadcrumb.ui.GapFixtures.place
import io.github.valeronm.breadcrumb.ui.GapFixtures.side
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class StayDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val clock: ReaderClock = readerClockOf(ApplicationProvider.getApplicationContext())

    private fun measures() = Measures(UnitSystem.METRIC, unitSymbols(context))

    private fun screen(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMeasures provides measures(),
                LocalDurationSymbols provides durationSymbols(LocalContext.current),
                LocalReaderClock provides clock,
            ) {
                AppTheme { content() }
            }
        }
    }

    private fun nearby(place: Place, distanceM: Double, current: Boolean = false) =
        NearbyPlaces.Nearby(place, distanceM, current)

    private fun rowOf(text: String): SemanticsMatcher = hasAnyDescendant(hasText(text))

    // --- Nearby places ---------------------------------------------------------

    @Test
    fun `nearby rows name each place, unnamed ones as such`() {
        val rows = listOf(nearby(place(id = 1, label = "Bakery"), 40.0), nearby(place(id = 2, label = null), 120.0))
        screen { NearbyPlaceRows(rows, onPick = {}) }

        compose.onNodeWithText("Bakery").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.timeline_unnamed_place)).assertIsDisplayed()
    }

    @Test
    fun `tapping a nearby place reports it`() {
        val bakery = place(id = 1, label = "Bakery")
        val rows = listOf(nearby(bakery, 40.0), nearby(place(id = 2, label = "Library"), 120.0))
        var picked: Place? = null
        screen { NearbyPlaceRows(rows, onPick = { picked = it }) }

        compose.onNodeWithText("Bakery").performClick()

        assertEquals(bakery, picked)
    }

    @Test
    fun `the current place is marked and does not report a tap`() {
        val rows = listOf(nearby(place(id = 1, label = "Bakery"), 40.0, current = true), nearby(place(id = 2, label = "Library"), 120.0))
        var picked: Place? = null
        screen { NearbyPlaceRows(rows, onPick = { picked = it }) }

        val currentLabel = context.getString(R.string.stay_current_place)
        compose.onNode(rowOf("Bakery") and !rowOf("Library"), useUnmergedTree = true).assert(rowOf(currentLabel))
        compose.onNode(rowOf("Library") and !rowOf("Bakery"), useUnmergedTree = true).assert(!rowOf(currentLabel))
        compose.onNodeWithText(currentLabel).assertIsDisplayed()
        compose.onNodeWithText("Bakery").performClick()

        assertNull(picked)
    }

    // --- Map -------------------------------------------------------------------

    private fun detail(place: PlaceResolver.ResolvedStay?, nearby: List<NearbyPlaces.Nearby>) = StayDetail(
        resolved = ResolvedStayInterval(
            stay = StayDeriver.Stay(0L, null, afterTrackId = 1, clusterId = 0),
            place = place,
            clocks = Clocks.both(ZoneOffset.UTC),
            hide = null,
            arrival = ORIGIN,
            departure = null,
            hideableHere = emptyList(),
            merge = null,
        ),
        nearby = nearby,
        placeDistanceM = null,
        claim = StayClaim(1, null),
    )

    @Test
    fun `the map highlights the current place among the nearby ones`() {
        val bakery = place(id = 1, label = "Bakery")
        val field = stayMapPlaces(
            detail(side(bakery, visits = 3), listOf(nearby(bakery, 0.0, current = true), nearby(place(id = 2, label = "Library"), 120.0))),
        )

        assertEquals(listOf(PlaceResolver.keyOf(1), PlaceResolver.keyOf(2)), field.places.map { it.key })
        assertEquals(PlaceResolver.keyOf(1), field.highlightKey)
    }

    @Test
    fun `a cluster no place row seeds joins the map as its highlighted place`() {
        val centroid = Coordinate(ORIGIN.lat + 0.001, ORIGIN.lon)
        val cluster = side(null, visits = 3, centroid = centroid)
        val field = stayMapPlaces(detail(cluster, listOf(nearby(place(id = 2, label = "Library"), 120.0))))

        val drawn = field.places.single { it.key == cluster.key }
        assertEquals(centroid, drawn.marker.location)
        assertEquals(PlaceClusterer.DEFAULT_RADIUS_M, drawn.radiusM!!, 0.0)
        assertEquals(2, field.places.size)
        assertEquals(cluster.key, field.highlightKey)
    }

    @Test
    fun `the current unnamed place is highlighted while another unnamed place keeps its dot`() {
        val here = place(id = 1, label = null)
        val field = stayMapPlaces(
            detail(side(here, visits = 3), listOf(nearby(here, 0.0, current = true), nearby(place(id = 2, label = null), 120.0))),
        )

        assertEquals(PlaceResolver.keyOf(1), field.highlightKey)
        assertEquals(UnnamedDot.CLUSTER, field.places.single { it.key == PlaceResolver.keyOf(2) }.dot)
    }

    @Test
    fun `a stay with no place highlights nothing on the map`() {
        val field = stayMapPlaces(detail(null, listOf(nearby(place(id = 2, label = "Library"), 120.0))))

        assertEquals(listOf(PlaceResolver.keyOf(2)), field.places.map { it.key })
        assertNull(field.highlightKey)
    }
}
