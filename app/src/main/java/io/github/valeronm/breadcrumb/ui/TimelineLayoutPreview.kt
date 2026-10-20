package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.Settings.TimelineDisplayOptions
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import java.time.LocalDate
import java.time.ZoneId

private val SAMPLE_ORIGIN = Coordinate(lat = 1.0, lon = -2.0)
private val SAMPLE_DAY = LocalDate.of(2021, 3, 4)
private const val SAMPLE_HOUR_MS = 3_600_000L
private val sampleRowShape = RoundedCornerShape(0.dp)

private const val SAMPLE_ROW_COUNT = 5

private fun sampleShape(index: Int, grouped: Boolean) =
    if (grouped) groupedRowShape(index, SAMPLE_ROW_COUNT) else sampleRowShape

private val classicPageLabels = listOf(R.string.timeline_view_list, R.string.timeline_view_map)

/** The colour page's preview: the same sample day, colored by the palette and location mode in hand. */
@Composable
internal fun TimelineColourSamplePreview(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    val classic = !displayOptions.modernTimelineLayout
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = if (classic) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        TimelineLayoutSampleRows(
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            rowSpacing = 2.dp,
            contentPadding = if (classic) {
                // Settings already insets 16.dp; match the Timeline list's sole horizontal gutter.
                PaddingValues(top = 12.dp, bottom = 12.dp)
            } else {
                PaddingValues(12.dp)
            },
            grouped = classic,
        )
    }
}

@Composable
internal fun TimelineLayoutSamplePreview(
    modern: Boolean,
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    val rowOptions = remember(modern, displayOptions) {
        displayOptions.copy(modernTimelineLayout = modern)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = if (modern) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.background
        },
    ) {
        if (modern) {
            ModernTimelineLayoutSample(rowOptions, colorPalette)
        } else {
            ClassicTimelineLayoutSample(rowOptions, colorPalette)
        }
    }
}

@Composable
private fun ModernTimelineLayoutSample(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    val sheetColor = timelineSheetColor()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(sheetColor),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
                )
            }
            TimelineLayoutSampleRows(
                displayOptions = displayOptions,
                colorPalette = colorPalette,
                rowSpacing = 0.dp,
                contentPadding = PaddingValues(0.dp),
                rowColor = sheetColor,
            )
        }
    }
}

@Composable
private fun ClassicTimelineLayoutSample(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    Column(Modifier.fillMaxWidth()) {
        ViewSwitchRow(
            labelsRes = classicPageLabels,
            selectedIndex = 0,
            onSelect = {},
        )
        TimelineLayoutSampleRows(
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            rowSpacing = 2.dp,
            contentPadding = PaddingValues(bottom = 12.dp),
            grouped = true,
        )
    }
}

@Composable
private fun timelineSheetColor() = lerp(
    MaterialTheme.colorScheme.surfaceContainerLow,
    MaterialTheme.colorScheme.surfaceContainerLowest,
    0.35f,
)

@Composable
private fun TimelineLayoutSampleRows(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
    rowSpacing: Dp,
    contentPadding: PaddingValues,
    rowColor: Color? = null,
    grouped: Boolean = false,
) {
    val zone = timelineZone()
    val homeLabel = stringResource(R.string.settings_timeline_layout_sample_home)
    val shoppingLabel = stringResource(R.string.settings_timeline_layout_sample_shopping_mall)
    val samples = remember(homeLabel, shoppingLabel, zone) {
        timelineLayoutSamples(homeLabel, shoppingLabel, zone)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        // Newest first, as on the Timeline day list — later stay and its arrival time read above the earlier one.
        StayCard(
            item = samples.stayShopping,
            shape = sampleShape(0, grouped),
            named = true,
            highlighted = false,
            zone = zone,
            onClick = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            lastTripActivity = ActivityType.DRIVING,
            containerColor = rowColor,
        )
        TrackRow(
            track = samples.driveTrack,
            shape = sampleShape(1, grouped),
            zone = zone,
            onOpen = {},
            onDelete = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            containerColor = rowColor,
        )
        TrackRow(
            track = samples.walkTrack,
            shape = sampleShape(2, grouped),
            zone = zone,
            onOpen = {},
            onDelete = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            containerColor = rowColor,
        )
        StayCard(
            item = samples.stayHome,
            shape = sampleShape(3, grouped),
            named = true,
            highlighted = false,
            zone = zone,
            onClick = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            lastTripActivity = ActivityType.RUNNING,
            containerColor = rowColor,
        )
        TrackRow(
            track = samples.runTrack,
            shape = sampleShape(4, grouped),
            zone = zone,
            onOpen = {},
            onDelete = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            containerColor = rowColor,
        )
    }
}

private data class TimelineLayoutSamples(
    val stayHome: TimelineItem.StayItem,
    val runTrack: TrackSummary,
    val walkTrack: TrackSummary,
    val driveTrack: TrackSummary,
    val stayShopping: TimelineItem.StayItem,
)

private fun timelineLayoutSamples(
    homeLabel: String,
    shoppingLabel: String,
    zone: ZoneId,
): TimelineLayoutSamples {
    val midnight = SAMPLE_DAY.atStartOfDay(zone).toInstant().toEpochMilli()
    val homeStayStart = midnight + 7 * SAMPLE_HOUR_MS
    val homeStayEnd = homeStayStart + 2 * SAMPLE_HOUR_MS
    val walkEnd = homeStayEnd + 2 * SAMPLE_HOUR_MS
    val shoppingStayStart = midnight + 13 * SAMPLE_HOUR_MS
    val shoppingStayEnd = shoppingStayStart + SAMPLE_HOUR_MS
    val homePlace = Place(
        id = 1,
        label = homeLabel,
        lat = SAMPLE_ORIGIN.lat,
        lon = SAMPLE_ORIGIN.lon,
        createdAt = 0,
        radiusM = 60.0,
        category = PlaceCategory.HOME.code,
    )
    val shoppingPlace = Place(
        id = 2,
        label = shoppingLabel,
        lat = SAMPLE_ORIGIN.lat + 0.01,
        lon = SAMPLE_ORIGIN.lon + 0.01,
        createdAt = 0,
        radiusM = 80.0,
        category = PlaceCategory.SHOPPING.code,
    )
    return TimelineLayoutSamples(
        stayHome = TimelineItem.StayItem(
            stay = StayDeriver.Stay(start = homeStayStart, end = homeStayEnd, afterTrackId = 1, clusterId = 0),
            place = PlaceResolver.ResolvedStay(place = homePlace, visitCount = 4, centroid = SAMPLE_ORIGIN),
            merge = null,
            holdsStart = true,
            holdsEnd = true,
        ),
        runTrack = TrackSummary(
            id = 1,
            activityType = ActivityType.RUNNING.name,
            startedAt = homeStayStart - SAMPLE_HOUR_MS,
            endedAt = homeStayStart,
            distanceMeters = 5_200.0,
            pointCount = 95,
            ignoredCount = 0,
            source = TrackOrigin.RECORDED.code,
            startPlaceId = null,
            endPlaceId = null,
        ),
        walkTrack = TrackSummary(
            id = 2,
            activityType = ActivityType.WALKING.name,
            startedAt = homeStayEnd,
            endedAt = walkEnd,
            distanceMeters = 2_400.0,
            pointCount = 80,
            ignoredCount = 0,
            source = TrackOrigin.RECORDED.code,
            startPlaceId = null,
            endPlaceId = null,
        ),
        driveTrack = TrackSummary(
            id = 3,
            activityType = ActivityType.DRIVING.name,
            startedAt = walkEnd,
            endedAt = shoppingStayStart,
            distanceMeters = 18_000.0,
            pointCount = 120,
            ignoredCount = 2,
            source = TrackOrigin.RECORDED.code,
            startPlaceId = null,
            endPlaceId = null,
        ),
        stayShopping = TimelineItem.StayItem(
            stay = StayDeriver.Stay(
                start = shoppingStayStart,
                end = shoppingStayEnd,
                afterTrackId = 3,
                clusterId = 1,
            ),
            place = PlaceResolver.ResolvedStay(place = shoppingPlace, visitCount = 2, centroid = SAMPLE_ORIGIN),
            merge = null,
            holdsStart = true,
            holdsEnd = true,
        ),
    )
}
