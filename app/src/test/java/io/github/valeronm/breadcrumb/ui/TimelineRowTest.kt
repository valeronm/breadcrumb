package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.PlaceOrigin
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.StaySwipe
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What a timeline row actually puts on screen, composed for real and read back off the semantics
 * tree. Every other suite here stops at the decision — which state, which resource id — and takes on
 * trust that the row renders it; these compose the row against the real resource table, which is the
 * only place a resource that is missing, misnamed, or wired to the wrong row can fail.
 *
 * **Expectations are read from the resource table, never spelled here.** A test asserting the literal
 * "Stayed" would pin English exactly as the recorder's old test fake did, and rot the same way; what
 * is pinned is that *this* state reaches *that* resource.
 *
 * The Portuguese cases are the ones that pay for the harness, and they carry their own guard: each
 * asserts the row renders the Portuguese string **and** that Portuguese differs from English for that
 * key. Without the second half a missing `values-pt` entry would fall back to English, the row would
 * render English, and the test would pass — the fails-open shape this suite exists to avoid.
 *
 * Robolectric rather than a device, so this runs in the normal `testDebugUnitTest` pass — and on an
 * arm64 dev box that means `-PqemuJdk`, like every other Robolectric class here.
 */
@RunWith(RobolectricTestRunner::class)
class TimelineRowTest {

    @get:Rule
    val compose = createComposeRule()

    /** Fetched per call: Robolectric swaps the configuration behind it for the `pt` cases below. */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The rows' clock is the reader's, deliberately. A row marks any time it draws with its offset
     * from the reader's zone, and the reader's zone is `timelineZone()` — a process global with no
     * seam to inject. Taking the same value here keeps the two agreeing without the test mutating
     * the JVM default, and every instant below is then derived from a calendar date in this zone
     * rather than hardcoded, so which branch a fixture lands in does not depend on the test host.
     */
    private val zone: ZoneId get() = timelineZone()

    /**
     * The clock the rows are composed under and the one an expectation is built from — **one
     * object**, so a case states the resource's wording and never the hour cycle. Which cycle that
     * is belongs to `ReaderClockTest`; here the two sides must merely agree, and building them from
     * two contexts would let a `values-pt` case resolve them differently and fail confusingly.
     */
    private val clock: ReaderClock = readerClockOf(ApplicationProvider.getApplicationContext())

    /** A resource as a given language renders it, whatever language the test itself is running in. */
    private fun stringIn(locale: Locale, @StringRes id: Int): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).getString(id)
    }

    /**
     * The Portuguese for a key, having first established that Portuguese and English disagree about
     * it. **A `pt` expectation cannot be obtained without that check running** — which is the point
     * of routing it through here: a missing `values-pt` entry falls back to English, the row renders
     * English, and an unguarded assertion passes. Left to each case to remember, that guard survives
     * only as long as whoever adds the next one reads this file's KDoc.
     */
    private fun translated(@StringRes id: Int): String {
        val portuguese = stringIn(PT, id)
        assertNotEquals(
            "${context.resources.getResourceEntryName(id)} reads the same in both languages, " +
                "so this case would pass on the English fallback and proves nothing",
            stringIn(Locale.ENGLISH, id),
            portuguese,
        )
        return portuguese
    }

    /** What the harness puts in composition — and what an expectation must be measured against. */
    private fun measures() = Measures(UnitSystem.METRIC, unitSymbols(context))

    /**
     * The providers a row needs, and no screen around it, under the app's own theme rather than
     * Material's defaults: the claim being tested is that the row renders as it ships, and it ships
     * under [AppTheme]'s typography and colour remap. `LocalMeasures` and `LocalDurationSymbols`
     * carry no default — a row composed without them throws rather than rendering ASCII-metric — so
     * supplying them is the same wiring `MainActivity` does.
     */
    private fun row(content: @Composable () -> Unit) {
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

    // --- Stay rows -----------------------------------------------------------

    private fun stayItem(
        start: Long,
        end: Long?,
        place: Place? = null,
        merge: Boolean = false,
        /** Which bounds are the stay's own — the slicer's stamp, not something read off the clock. */
        holdsStart: Boolean = true,
        holdsEnd: Boolean = true,
    ) = TimelineItem.StayItem(
        stay = StayDeriver.Stay(
            start = start,
            end = end,
            afterTrackId = 1,
            clusterId = 0,
        ),
        place = PlaceResolver.ResolvedStay(place = place, visitCount = 1, centroid = ORIGIN, radiusM = 150.0),
        swipe = if (merge) StaySwipe.Merge(MERGE_PLAN) else null,
        holdsStart = holdsStart,
        holdsEnd = holdsEnd,
    )

    private fun stayRow(item: TimelineItem.StayItem) = row {
        StayCard(
            item = item,
            shape = SHAPE,
            named = item.place?.label != null,
            highlighted = false,
            zone = zone,
            onClick = {},
        )
    }

    @Test
    fun `an unnamed stay is titled by the resource for a plain stop`() {
        stayRow(stayItem(start = noon, end = noon + HOUR))

        compose.onNodeWithText(context.getString(R.string.timeline_stayed)).assertIsDisplayed()
    }

    @Test
    fun `a named stay is titled by what the user called it, not by a resource`() {
        stayRow(stayItem(noon, noon + HOUR, place = place("The allotment")))

        compose.onNodeWithText("The allotment").assertIsDisplayed()
    }

    @Test
    fun `a stay at a place an import made names its origin aloud`() {
        stayRow(stayItem(noon, noon + HOUR, place = importedPlace(label = null)))

        compose.onNodeWithContentDescription(context.getString(R.string.common_origin_google_timeline))
            .assertIsDisplayed()
    }

    @Test
    fun `a stay at an imported place the user edited carries no origin mark`() {
        stayRow(stayItem(noon, noon + HOUR, place = importedPlace(label = "Café").copy(source = PlaceOrigin.MANUAL.code)))

        compose.onNodeWithContentDescription(context.getString(R.string.common_origin_google_timeline))
            .assertDoesNotExist()
    }

    @Test
    fun `a stay running to midnight states only the bound it has`() {
        // The whole point of the sentence resources: this row names one clock time, and the wording
        // around it has to come from the one-bound resource rather than the two-bound one.
        stayRow(stayItem(start = noon, end = midnightNext, holdsEnd = false))

        val from = context.getString(R.string.timeline_stay_from, clock.time(noon, zone))
        compose.onNodeWithText(from).assertIsDisplayed()
    }

    @Test
    fun `a stay filling a whole day names no clock time at all`() {
        stayRow(stayItem(start = midnight, end = midnightNext, holdsStart = false, holdsEnd = false))

        compose.onNodeWithText(context.getString(R.string.timeline_all_day)).assertIsDisplayed()
    }

    @Test
    fun `a mergeable stop says so in its title and in the badge read aloud`() {
        stayRow(stayItem(noon, noon + 60_000, merge = true))

        compose.onNodeWithText(context.getString(R.string.timeline_short_stop)).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(R.string.timeline_short_stop_mergeable),
        ).assertIsDisplayed()
    }

    private fun swipeRow(swipe: StaySwipe) = row {
        StayRow(
            item = stayItem(noon, noon + 60_000).copy(swipe = swipe),
            shape = SHAPE,
            zone = zone,
            highlighted = false,
            onMerge = {},
            onHide = {},
            onClick = {},
        )
    }

    @Test
    fun `a stay the swipe hides offers the hide, not the merge`() {
        swipeRow(StaySwipe.Hide(HiddenStay(beforeId = 1, afterId = 2)))

        compose.onNodeWithContentDescription(context.getString(R.string.timeline_hide_stay)).assertExists()
        compose.onAllNodesWithContentDescription(context.getString(R.string.timeline_merge)).assertCountEquals(0)
    }

    @Test
    fun `a stay the swipe merges offers the merge, not the hide`() {
        swipeRow(StaySwipe.Merge(MERGE_PLAN))

        compose.onNodeWithContentDescription(context.getString(R.string.timeline_merge)).assertExists()
        compose.onAllNodesWithContentDescription(context.getString(R.string.timeline_hide_stay)).assertCountEquals(0)
    }

    // --- Track rows ----------------------------------------------------------

    private fun trackRow(
        activity: ActivityType,
        source: TrackOrigin = TrackOrigin.RECORDED,
        onShowHiddenStay: (() -> Unit)? = null,
    ) = row {
        TrackRow(
            trip = Trip(
                TrackSummary(
                    id = 1,
                    activityType = activity.name,
                    startedAt = noon,
                    endedAt = noon + HOUR,
                    distanceMeters = 5_000.0,
                    pointCount = 100,
                    ignoredCount = 0,
                    source = source.code,
                    startPlaceId = null,
                    endPlaceId = null,
                    hidesStayBefore = false,
                    hidesStayAfter = false,
                ),
            ),
            shape = SHAPE,
            zone = zone,
            endZone = null,
            partClocks = emptyList(),
            onOpen = {},
            onOpenTrip = {},
            onDelete = {},
            onShowHiddenStay = onShowHiddenStay,
        )
    }

    @Test
    fun `a trip a hidden stay follows offers to show it`() {
        var shown = false
        trackRow(ActivityType.DRIVING) { shown = true }

        compose.onNodeWithContentDescription(context.getString(R.string.timeline_show_hidden_stay)).performClick()

        assertTrue(shown)
    }

    @Test
    fun `a trip no hidden stay follows offers nothing to show`() {
        trackRow(ActivityType.DRIVING)

        compose.onAllNodesWithContentDescription(context.getString(R.string.timeline_show_hidden_stay))
            .assertCountEquals(0)
    }

    @Test
    fun `a track row leads with its activity and reads the same name aloud`() {
        trackRow(ActivityType.CYCLING)

        val cycling = context.getString(R.string.activity_cycling)
        compose.onNodeWithText(cycling, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(cycling).assertIsDisplayed()
    }

    @Test
    fun `a trip from elsewhere names its origin aloud`() {
        trackRow(ActivityType.DRIVING, TrackOrigin.GOOGLE_TIMELINE)
        compose.onNodeWithContentDescription(context.getString(R.string.common_origin_google_timeline))
            .assertIsDisplayed()
    }

    private fun drivePart(id: Long, start: Long, end: Long, source: TrackOrigin) = TrackSummary(
        id = id, activityType = ActivityType.DRIVING.name, startedAt = start, endedAt = end,
        distanceMeters = 1_000.0, pointCount = 2, ignoredCount = 0, source = source.code,
        startPlaceId = null, endPlaceId = null, hidesStayBefore = false, hidesStayAfter = false,
    )

    private fun chainedRow(onOpen: (Long) -> Unit = {}) = row {
        TrackRow(
            trip = Trip(
                listOf(
                    drivePart(1, noon, noon + HOUR / 4, TrackOrigin.MANUAL),
                    drivePart(2, noon + HOUR / 4, noon + HOUR, TrackOrigin.GOOGLE_TIMELINE),
                ),
            ),
            shape = SHAPE,
            zone = zone,
            endZone = null,
            partClocks = emptyList(),
            onOpen = onOpen,
            onOpenTrip = {},
            onDelete = {},
        )
    }

    @Test
    fun `a trip of several parts names each part's origin aloud only once they unfold`() {
        chainedRow()
        val imported = context.getString(R.string.common_origin_google_timeline)
        val entered = context.getString(R.string.common_origin_entered_by_hand)
        compose.onAllNodesWithContentDescription(imported).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(entered).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_show_parts)).performClick()
        compose.onNodeWithContentDescription(imported).assertIsDisplayed()
        compose.onNodeWithContentDescription(entered).assertIsDisplayed()
    }

    @Test
    fun `a trip of several parts unfolds them under its chevron, each opening as its own track`() {
        var opened: Long? = null
        chainedRow(onOpen = { opened = it })
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_show_parts)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_hide_parts)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_origin_google_timeline)).performClick()
        assertEquals(2L, opened)
    }

    @Test
    fun `a recorded trip carries no origin mark`() {
        trackRow(ActivityType.DRIVING)
        listOf(
            R.string.common_origin_gpx,
            R.string.common_origin_google_timeline,
            R.string.common_origin_entered_by_hand,
        ).forEach { compose.onNodeWithContentDescription(context.getString(it)).assertDoesNotExist() }
    }

    @Test
    fun `a track row states its distance in the display system's own unit`() {
        trackRow(ActivityType.DRIVING)

        // Not the literal "5 km": the number's shape is UnitsTest's business, the wiring is this
        // suite's — that the row asks the measures in composition rather than formatting its own.
        val distance = Measures(UnitSystem.METRIC, unitSymbols(context)).distance(5_000.0)
        compose.onNodeWithText(distance, substring = true).assertIsDisplayed()
    }

    @Test
    fun `the recording row names its activity and says it is recording`() {
        row {
            RecordingRow(
                item = TimelineItem.RecordingItem(trackId = 1, label = ActivityType.WALKING, startedAt = noon),
                shape = SHAPE,
                onClick = {},
            )
        }

        val walking = context.getString(R.string.activity_walking)
        compose.onNodeWithText(walking).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.duration_recording), substring = true).assertIsDisplayed()
    }

    // --- Gap rows ------------------------------------------------------------

    private fun gapItem(
        start: Long,
        end: Long,
        holdsDeparture: Boolean = true,
        holdsArrival: Boolean = true,
    ) = TimelineItem.GapItem(
        gap = StayDeriver.Gap(
            start = start,
            end = end,
            afterTrackId = 1,
        ),
        departureZone = zone,
        arrivalZone = zone,
        holdsDeparture = holdsDeparture,
        holdsArrival = holdsArrival,
    )

    private fun gapRow(item: TimelineItem.GapItem) = row {
        GapCard(
            item = item,
            shape = SHAPE,
            onClick = {},
        )
    }

    @Test
    fun `a gap arriving at a place an import made names its origin aloud`() {
        val resolved = PlaceResolver.ResolvedStay(place = importedPlace(label = null), visitCount = 1, centroid = ORIGIN, radiusM = 150.0)
        gapRow(gapItem(start = noon, end = noon + HOUR).copy(toPlace = resolved))

        compose.onNodeWithContentDescription(context.getString(R.string.common_origin_google_timeline))
            .assertIsDisplayed()
    }

    private fun resolved(label: String) =
        PlaceResolver.ResolvedStay(place = place(label), visitCount = 1, centroid = ORIGIN, radiusM = 150.0)

    @Test
    fun `a gap holding both ends states how long it ran, each time beside its place`() {
        gapRow(gapItem(start = noon, end = noon + HOUR).copy(fromPlace = resolved("Left"), toPlace = resolved("Right")))

        compose.onNodeWithText(durationOf(HOUR)).assertIsDisplayed()
        compose.onNodeWithText(clock.time(noon, zone)).assertIsDisplayed()
        compose.onNodeWithText(clock.time(noon + HOUR, zone)).assertIsDisplayed()
    }

    @Test
    fun `a gap with a fix on both sides states how far apart they are`() {
        val to = Coordinate(lat = ORIGIN.lat + 0.05, lon = ORIGIN.lon)
        val item = gapItem(start = noon, end = noon + HOUR)
        gapRow(item.copy(gap = item.gap.copy(from = StayDeriver.Gap.Side(ORIGIN, clusterId = 0), to = StayDeriver.Gap.Side(to, clusterId = 1))))

        val apart = measures().distance(AndroidDistance.meters(ORIGIN.lat, ORIGIN.lon, to.lat, to.lon))
        compose.onNodeWithText("${durationOf(HOUR)} · $apart").assertIsDisplayed()
    }

    @Test
    fun `a gap speaking only for its arrival names that bound alone`() {
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsDeparture = false).copy(toPlace = resolved("Right")))

        val until = context.getString(R.string.timeline_gap_until, clock.time(noon + HOUR, zone))
        compose.onNodeWithText(until).assertIsDisplayed()
        compose.onNodeWithText(clock.time(noon + HOUR, zone)).assertDoesNotExist()
        compose.onNodeWithText(durationOf(HOUR)).assertDoesNotExist()
    }

    @Test
    fun `a gap speaking only for its departure names that bound alone`() {
        // The half every absence longer than a day produces first, and the one the reader meets at
        // the top of an outage: it says when recording stopped and nothing about when it resumed.
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsArrival = false).copy(fromPlace = resolved("Left")))

        val from = context.getString(R.string.timeline_gap_from, clock.time(noon, zone))
        compose.onNodeWithText(from).assertIsDisplayed()
        compose.onNodeWithText(clock.time(noon, zone)).assertDoesNotExist()
        compose.onNodeWithText(durationOf(HOUR)).assertDoesNotExist()
    }

    // --- The translation actually reaching a row -----------------------------

    @Test
    @Config(qualifiers = "pt")
    fun `a stay row renders Portuguese, not the English fallback`() {
        val stayed = translated(R.string.timeline_stayed)

        stayRow(stayItem(start = noon, end = noon + HOUR))

        compose.onNodeWithText(stayed).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt")
    fun `a track row reads its activity aloud in Portuguese`() {
        val cycling = translated(R.string.activity_cycling)

        trackRow(ActivityType.CYCLING)

        compose.onNodeWithContentDescription(cycling).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt")
    fun `a gap row states its absence in Portuguese`() {
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsDeparture = false))

        val until = translated(R.string.timeline_gap_until)
        compose.onNodeWithText(until.substringBefore("%1\$s"), substring = true).assertIsDisplayed()
    }

    private fun importedPlace(label: String?) = place(label).copy(source = PlaceOrigin.GOOGLE_TIMELINE.code)

    private fun place(label: String?) = Place(
        id = 1,
        label = label,
        lat = ORIGIN.lat,
        lon = ORIGIN.lon,
        createdAt = 0,
        radiusM = 60.0,
    )

    /**
     * The fixture day's bounds **in the rows' own zone**, so a row's clock text reads as the day it
     * covers wherever the test host is set. Hardcoded epoch millis would drift off the hour on any
     * machine not running UTC; which bounds a row holds is stamped, not read off these.
     */
    private val midnight: Long get() = DAY.atStartOfDay(zone).toInstant().toEpochMilli()

    private val noon: Long get() = midnight + 12 * HOUR

    private val midnightNext: Long get() = DAY.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** A span as the row spells it, from the same ladder the row reads. */
    private fun durationOf(ms: Long) = formatDurationMs(ms, durationSymbols(context))

    private companion object {
        /** The neutral fixture origin the rest of the suite uses; no real coordinate ships here. */
        val ORIGIN = Coordinate(lat = 1.0, lon = -2.0)

        val SHAPE = RoundedCornerShape(12.dp)
        val MERGE_PLAN = TrackMerge.Plan(earlierId = 1, laterId = 2)
        val PT: Locale = Locale.forLanguageTag("pt")

        /** Any ordinary day; nothing here turns on which, only that it is whole in the row's zone. */
        val DAY: LocalDate = LocalDate.of(2021, 3, 4)

        const val HOUR = 3_600_000L
    }
}
