package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertNotEquals
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
            provenance = StayDeriver.Provenance.OBSERVED,
            afterTrackId = 1,
            clusterId = 0,
        ),
        place = PlaceResolver.ResolvedStay(place = place, visitCount = 1, centroid = ORIGIN),
        merge = if (merge) MERGE_PLAN else null,
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

    // --- Track rows ----------------------------------------------------------

    private fun trackRow(activity: ActivityType) = row {
        TrackRow(
            track = TrackSummary(
                id = 1,
                activityType = activity.name,
                startedAt = noon,
                endedAt = noon + HOUR,
                distanceMeters = 5_000.0,
                pointCount = 100,
                ignoredCount = 0,
                source = "recorded",
            ),
            shape = SHAPE,
            zone = zone,
            endZone = null,
            onOpen = {},
            onDelete = {},
        )
    }

    @Test
    fun `a track row leads with its activity and reads the same name aloud`() {
        trackRow(ActivityType.CYCLING)

        val cycling = context.getString(R.string.activity_cycling)
        compose.onNodeWithText(cycling, substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(cycling).assertIsDisplayed()
    }

    @Test
    fun `a track row states its distance in the display system's own unit`() {
        trackRow(ActivityType.DRIVING)

        // Not the literal "5 km": the number's shape is UnitsTest's business, the wiring is this
        // suite's — that the row asks the measures in composition rather than formatting its own.
        val distance = Measures(UnitSystem.METRIC, unitSymbols(context)).distance(5_000.0)
        compose.onNodeWithText(distance, substring = true).assertIsDisplayed()
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
            reason = StayDeriver.GapReason.MOVED_UNRECORDED,
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
            zone = zone,
            onOpenPlace = {},
            onAddTrip = {},
        )
    }

    @Test
    fun `a gap holding both ends states how long it ran, not when`() {
        gapRow(gapItem(start = noon, end = noon + HOUR))

        val lasting = context.getString(R.string.timeline_gap_lasting, durationOf(HOUR))
        compose.onNodeWithText(lasting).assertIsDisplayed()
    }

    @Test
    fun `a gap speaking only for its arrival names that bound alone`() {
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsDeparture = false))

        val until = context.getString(R.string.timeline_gap_until, clock.time(noon + HOUR, zone))
        compose.onNodeWithText(until).assertIsDisplayed()
    }

    @Test
    fun `a gap speaking only for its departure names that bound alone`() {
        // The half every absence longer than a day produces first, and the one the reader meets at
        // the top of an outage: it says when recording stopped and nothing about when it resumed.
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsArrival = false))

        val from = context.getString(R.string.timeline_gap_from, clock.time(noon, zone))
        compose.onNodeWithText(from).assertIsDisplayed()
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
        // The gap sentences are whole lines per case rather than a lead-in and a tail, so this is
        // where a translation that lost one of the three would show.
        gapRow(gapItem(start = noon, end = noon + HOUR, holdsDeparture = false))

        val until = translated(R.string.timeline_gap_until)
        compose.onNodeWithText(until.substringBefore("%1\$s"), substring = true).assertIsDisplayed()
    }

    private fun place(label: String) = Place(
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
