package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.util.UnitSystem

// Static, per-activity speed→colour scale so tracks are visually comparable across the whole list:
// red (slow) → green (a good cruising pace) → blue (fast). Hue runs 0°(red)→240°(blue), so with an
// evenly-spaced min/mid/max the midpoint speed lands exactly on green.
private const val HUE_RED = 0f

private const val HUE_BLUE = 240f

private const val SPEED_SATURATION = 0.9f

// L=0.5 glows against the dark basemap but washes out (especially the green/yellow middle of
// the ramp) on the pale light basemap — deeper colours there.
private fun rampLuminance(dark: Boolean) = if (dark) 0.5f else 0.33f

/**
 * Speed thresholds (in the display system's speed unit) anchoring the red and blue ends of the
 * colour ramp per activity. Hand-rounded per system like the slider ladders — the legend must
 * read "20 / 55 / 90 mph", not the converted "19 / 56 / 93" — so min/max are picked evenly
 * spaced around a round midpoint (the legend's middle label is their average, and lands on
 * green). The anchors therefore sit a hair apart between systems; a user only sees one.
 */
private data class SpeedScale(val min: Float, val max: Float)

private fun speedScaleFor(activity: ActivityType, units: UnitSystem): SpeedScale = when (activity) {
    ActivityType.DRIVING, ActivityType.TAXI, ActivityType.UNKNOWN ->
        units.bySpeedUnit(kmh = SpeedScale(30f, 150f), mph = SpeedScale(20f, 90f))
    ActivityType.CYCLING ->
        units.bySpeedUnit(kmh = SpeedScale(10f, 34f), mph = SpeedScale(6f, 22f))
    ActivityType.RUNNING ->
        units.bySpeedUnit(kmh = SpeedScale(6f, 16f), mph = SpeedScale(4f, 10f))
    ActivityType.WALKING, ActivityType.STILL ->
        units.bySpeedUnit(kmh = SpeedScale(2f, 8f), mph = SpeedScale(1f, 5f))
}

// --- Track line colouring by metric ------------------------------------------------------------

/** Which per-point metric the track line is coloured by. */
enum class ColorMode(val label: String) {
    SPEED("Speed"),
    ELEVATION("Elevation"),
    ACCURACY("Accuracy"),
    SATELLITES("Satellites"),
    CN0("Signal"),
}

/** Grey for points the metric has no value for; darker on the light basemap. */
private fun noDataArgb(dark: Boolean) = Color.hsl(0f, 0f, if (dark) 0.6f else 0.45f).toArgb()

/** Legend content for the current colour mode. */
internal sealed interface Legend {
    /** Continuous red→green→blue ramp with anchor labels. */
    data class Ramp(val left: String, val mid: String, val right: String) : Legend

    /** No point in the track carries this metric. */
    data class None(val message: String) : Legend
}

internal class TrackColoring(val colors: IntArray, val legend: Legend)

/** ARGB on the red(0°)→green(120°)→blue(240°) ramp for [value] between the [redAt] and [blueAt] anchors. */
private fun rampColor(value: Float?, redAt: Float, blueAt: Float, luminance: Float, noData: Int): Int {
    if (value == null) return noData
    val t = ((value - redAt) / (blueAt - redAt)).coerceIn(0f, 1f)
    val hue = HUE_RED + t * (HUE_BLUE - HUE_RED)
    return Color.hsl(hue, SPEED_SATURATION, luminance).toArgb()
}

private fun rampColoring(
    values: List<Float?>,
    redAt: Float,
    blueAt: Float,
    unit: String,
    emptyMsg: String,
    dark: Boolean,
): TrackColoring {
    // Resolved once per coloring, not per point — Color.hsl is a real conversion.
    val noData = noDataArgb(dark)
    if (values.all { it == null }) {
        return TrackColoring(IntArray(values.size) { noData }, Legend.None(emptyMsg))
    }
    val luminance = rampLuminance(dark)
    val colors = IntArray(values.size) { rampColor(values[it], redAt, blueAt, luminance, noData) }
    fun num(v: Float) = "%.0f".format(v)
    // Unit only on the rightmost label, else three "… unit" labels overflow the fixed-width legend.
    val right = num(blueAt).let { if (unit.isEmpty()) it else "$it $unit" }
    return TrackColoring(colors, Legend.Ramp(num(redAt), num((redAt + blueAt) / 2f), right))
}

/**
 * The per-point value series for [mode] (null where a point lacks the metric) and its display
 * unit — the single mode→series/unit mapping, feeding both the graph and the map colouring.
 */
internal fun metricSeries(
    points: List<TrackPoint>,
    mode: ColorMode,
    speedsKmh: FloatArray,
    units: UnitSystem,
): Pair<List<Float?>, String> = when (mode) {
    ColorMode.SPEED -> List(points.size) { units.fromKmh(speedsKmh[it]) } to units.speedUnit
    ColorMode.ELEVATION -> points.map { it.altitude?.toFloat()?.let(units::fromMeters) } to units.shortUnit
    ColorMode.ACCURACY -> points.map { it.accuracy?.let(units::fromMeters) } to units.shortUnit
    ColorMode.SATELLITES -> points.map { it.satellitesInFix?.toFloat() } to "sat"
    ColorMode.CN0 -> points.map { it.cn0 } to "dB"
}

/**
 * Per-point colours + legend for [mode]. Ramps go red→green→blue between two anchor values; where an
 * anchor is "worse" it's placed at red (e.g. accuracy: 50 m = red, 0 m = blue). Points missing the
 * metric are grey.
 */
internal fun trackColoring(
    points: List<TrackPoint>,
    speedsKmh: FloatArray,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    units: UnitSystem,
): TrackColoring {
    // Anchors are hand-rounded in the display unit (see SpeedScale), so the legend reads round
    // numbers in every system; the colours may sit a hair apart between systems as a result.
    val (values, unit) = metricSeries(points, mode, speedsKmh, units)
    return when (mode) {
        ColorMode.SPEED -> {
            val s = speedScaleFor(activity ?: ActivityType.UNKNOWN, units)
            rampColoring(values, s.min, s.max, unit, "No speed data", dark)
        }
        ColorMode.ELEVATION -> {
            val present = values.filterNotNull()
            if (present.isEmpty()) {
                val noData = noDataArgb(dark)
                TrackColoring(IntArray(points.size) { noData }, Legend.None("No elevation data"))
            } else {
                val lo = present.min()
                val hi = present.max()
                val span = if (hi - lo < 1f) 1f else hi - lo // avoid a zero-width ramp on a flat track
                rampColoring(values, lo, lo + span, unit, "No elevation data", dark)
            }
        }
        // Lower accuracy radius is better, so zero sits at the blue (good) end. The red anchor is
        // hand-rounded per display unit like the speed scales: 150 ft, not the converted 164.
        ColorMode.ACCURACY ->
            rampColoring(values, units.byShortUnit(metres = 50f, feet = 150f), 0f, unit, "No accuracy data", dark)
        ColorMode.SATELLITES -> rampColoring(values, 0f, 12f, unit, "No satellite data", dark)
        ColorMode.CN0 -> rampColoring(values, 15f, 45f, unit, "No signal data", dark)
    }
}

/** Horizontally-scrollable chips to pick how the track line is coloured. */
@Composable
internal fun ColorModeSelector(selected: ColorMode, onSelect: (ColorMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ColorMode.entries) {
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
internal fun TrackLegend(legend: Legend, modifier: Modifier) {
    when (legend) {
        is Legend.None ->
            LegendSurface(modifier) {
                Text(legend.message, style = MaterialTheme.typography.labelSmall)
            }
        is Legend.Ramp ->
            LegendSurface(modifier) {
                val luminance = rampLuminance(isSystemInDarkTheme())
                // Dense stops along the same HSL ramp the track uses: the brush blends
                // neighbours in RGB, and RGB midpoints of red/green and green/blue are muddy
                // brown/grey — 30° hue steps stay on-hue.
                val rampBrush = remember(luminance) {
                    Brush.horizontalGradient(
                        (0..8).map {
                            val hue = HUE_RED + it * (HUE_BLUE - HUE_RED) / 8f
                            Color.hsl(hue, SPEED_SATURATION, luminance)
                        },
                    )
                }
                Box(
                    Modifier
                        .width(132.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(rampBrush),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(legend.left, style = MaterialTheme.typography.labelSmall)
                    Text(legend.mid, style = MaterialTheme.typography.labelSmall)
                    Text(legend.right, style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}

@Composable
internal fun LegendSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}
