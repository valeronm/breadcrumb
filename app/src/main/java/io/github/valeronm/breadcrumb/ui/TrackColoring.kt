package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MetricSmoother
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.util.FixUnit
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import kotlin.math.roundToInt

// Static, per-activity speed→color scale so tracks are visually comparable across the whole list:
// red (slow) → green (a good cruising pace) → blue (fast). Hue runs 0°(red)→240°(blue), so with an
// evenly-spaced min/mid/max the midpoint speed lands exactly on green.
private const val HUE_RED = 0f

private const val HUE_BLUE = 240f

private const val SPEED_SATURATION = 0.9f

// L=0.5 glows against the dark basemap but washes out (especially the green/yellow middle of
// the ramp) on the pale light basemap — deeper colors there.
private fun rampLuminance(dark: Boolean) = if (dark) 0.5f else 0.33f

/**
 * Per-activity speed thresholds (display-system units) anchoring the ramp's red and blue ends.
 * Hand-rounded per system like the slider ladders — the legend must read "20 / 55 / 90 mph", not the
 * converted "19 / 56 / 93" — min/max evenly spaced around a round midpoint, so the middle label
 * (their average) lands on green; the anchors sit a hair apart between systems, a user only sees one.
 */
private data class SpeedScale(val min: Float, val max: Float)

private fun speedScaleFor(activity: ActivityType, units: UnitSystem): SpeedScale = when (activity) {
    ActivityType.DRIVING, ActivityType.TAXI, ActivityType.UNKNOWN ->
        units.bySpeedUnit(kmh = SpeedScale(30f, 150f), mph = SpeedScale(20f, 90f))
    ActivityType.CYCLING ->
        units.bySpeedUnit(kmh = SpeedScale(10f, 34f), mph = SpeedScale(6f, 22f))
    // A crossing spends its length near a steady cruise; on the road ramp that whole line is red.
    ActivityType.FERRY ->
        units.bySpeedUnit(kmh = SpeedScale(10f, 50f), mph = SpeedScale(5f, 35f))
    // From a metro crawl to intercity rail; high-speed stretches saturate red, which is honest.
    ActivityType.TRANSIT ->
        units.bySpeedUnit(kmh = SpeedScale(20f, 220f), mph = SpeedScale(10f, 140f))
    ActivityType.RUNNING ->
        units.bySpeedUnit(kmh = SpeedScale(6f, 16f), mph = SpeedScale(4f, 10f))
    ActivityType.WALKING, ActivityType.STILL ->
        units.bySpeedUnit(kmh = SpeedScale(2f, 8f), mph = SpeedScale(1f, 5f))
    // A cruise holds near one speed for hours; anchors wide enough that climb and descent shade.
    ActivityType.FLIGHT ->
        units.bySpeedUnit(kmh = SpeedScale(200f, 1000f), mph = SpeedScale(150f, 650f))
}

// --- Track line coloring by metric ------------------------------------------------------------

/** Which per-point metric the track line is colored by. */
internal enum class ColorMode(
    @StringRes val labelRes: Int,
    /** What the legend says when no fix in the track carries this metric. */
    @StringRes val noDataRes: Int,
    val recorderOnly: Boolean,
) {
    SPEED(R.string.color_mode_speed, R.string.color_no_speed_data, recorderOnly = false),
    ELEVATION(R.string.color_mode_elevation, R.string.color_no_elevation_data, recorderOnly = false),

    // What the receiver said about its own fix at the moment it took it. A file describes a path,
    // not a measurement of one, so these belong to a recording however a parser is taught to read.
    ACCURACY(R.string.color_mode_accuracy, R.string.color_no_accuracy_data, recorderOnly = true),
    SATELLITES(R.string.color_mode_satellites, R.string.color_no_satellite_data, recorderOnly = true),
    CN0(R.string.color_mode_signal, R.string.color_no_signal_data, recorderOnly = true),
}

/**
 * The modes worth offering for one track — the question [rampColoring] answers with [Legend.None]
 * once a series turns out all-null, asked before a chip is shown rather than after it is tapped.
 *
 * Two filters, because a dead metric has two causes. A writer that reports nothing about its own
 * measurements ([TrackOrigin.measuresFixQuality]) rules out the [ColorMode.recorderOnly] ones as a
 * kind, not as an observation — asked of the writer rather than tested against one origin, so a
 * writer added later answers for itself. An unknown writer claims nothing and rules out nothing. The rest is decided by the fixes,
 * which the source can't answer for: a GPX may or may not carry elevation, and a recording taken
 * without a satellite count is a recording still. [ColorMode.SPEED] is never ruled out by the
 * *fixes* — it falls back to position over time (`TrackQuality.pointSpeedsKmh`), so a track that
 * draws a line has it — but a writer whose motion is implied rather than observed
 * ([TrackOrigin.measuresMotion]) rules it out as a kind too, which is how a manual track ends up
 * with no metrics at all and the screens that consult this render no selector for one.
 */
internal fun availableColorModes(points: List<TrackPoint>, source: TrackOrigin?): List<ColorMode> =
    ColorMode.entries.filter { mode ->
        !(mode.recorderOnly && source?.measuresFixQuality == false) &&
            !(mode == ColorMode.SPEED && source?.measuresMotion == false) &&
            mode.carriedBy(points)
    }

/** Exhaustive on purpose, like [metricSeries] it mirrors: a metric added later answers this too. */
private fun ColorMode.carriedBy(points: List<TrackPoint>): Boolean = when (this) {
    ColorMode.SPEED -> true
    ColorMode.ELEVATION -> points.any { it.altitude != null }
    ColorMode.ACCURACY -> points.any { it.accuracy != null }
    ColorMode.SATELLITES -> points.any { it.satellitesInFix != null }
    ColorMode.CN0 -> points.any { it.cn0 != null }
}

/** Gray for points the metric has no value for; darker on the light basemap. */
private fun noDataArgb(dark: Boolean) = Color.hsl(0f, 0f, if (dark) 0.6f else 0.45f).toArgb()

/** Legend content for the current color mode. */
internal sealed interface Legend {
    /** Continuous red→green→blue ramp with anchor labels. */
    data class Ramp(val left: String, val mid: String, val right: String) : Legend

    /**
     * No point in the track carries this metric. Carries the message as a resource, not as text:
     * the coloring is built off the composition (a `remember`, and the map's own layer build), so
     * the words are resolved where the legend is drawn.
     */
    data class None(@StringRes val messageRes: Int) : Legend
}

/**
 * A metric's per-point colors and legend — plus the very series they were read from, so the graph
 * beside the map plots and reports exactly what the map is drawn in rather than deriving it again.
 */
internal class TrackColoring(
    /**
     * A color per point. A surface drawing *legs* takes the color of the fix a leg **arrives at**:
     * an entry is the reading at its own fix, so the leg ending there is what it describes, and the
     * first fix's color is read by nobody. Both the map's line and the graph's strokes obey this;
     * they disagree by a whole segment if one of them stops.
     */
    val colors: IntArray,
    val legend: Legend,
    val values: List<Float?>,
    val unit: String,
)

/**
 * The ramp is a stepped scale of this many bands, not a continuum: a band is a pace worth telling
 * apart, where a shade per fix is a shimmer along a line held at one speed. Banded in the palette,
 * so every surface reading it — the map, the graph's strokes, the legend — is colored by one table.
 *
 * A consequence the map depends on: the track is drawn as one feature per *run* of one color, and
 * an ungraded ramp would make a long cruise thousands of sub-pixel features rather than one.
 */
private const val RAMP_STEPS = 32

/** Hue on the red(0°)→green(120°)→blue(240°) ramp at [t] (0..1), stepped into [RAMP_STEPS] bands. */
private fun bandedHue(t: Float): Float {
    val banded = (t.coerceIn(0f, 1f) * RAMP_STEPS).roundToInt() / RAMP_STEPS.toFloat()
    return HUE_RED + banded * (HUE_BLUE - HUE_RED)
}

/** The [RAMP_STEPS] + 1 colors of the whole ramp — resolved once per coloring, as `Color.hsl` is a
 *  real conversion and a track asks for one per point. */
private fun rampPalette(luminance: Float): IntArray =
    IntArray(RAMP_STEPS + 1) { Color.hsl(bandedHue(it / RAMP_STEPS.toFloat()), SPEED_SATURATION, luminance).toArgb() }

/** ARGB from [palette] for [value] between the [redAt] and [blueAt] anchors. */
private fun rampColor(value: Float?, redAt: Float, blueAt: Float, palette: IntArray, noData: Int): Int {
    if (value == null) return noData
    val t = ((value - redAt) / (blueAt - redAt)).coerceIn(0f, 1f)
    return palette[(t * RAMP_STEPS).roundToInt()]
}

private fun rampColoring(
    values: List<Float?>,
    redAt: Float,
    blueAt: Float,
    unit: String,
    mode: ColorMode,
    dark: Boolean,
): TrackColoring {
    // Resolved once per coloring, not per point — Color.hsl is a real conversion.
    val noData = noDataArgb(dark)
    if (values.all { it == null }) {
        return TrackColoring(IntArray(values.size) { noData }, Legend.None(mode.noDataRes), values, unit)
    }
    val palette = rampPalette(rampLuminance(dark))
    val colors = IntArray(values.size) { rampColor(values[it], redAt, blueAt, palette, noData) }
    fun num(v: Float) = "%.0f".format(v)
    // Unit only on the rightmost label, else three "… unit" labels overflow the fixed-width legend.
    val right = num(blueAt).let { if (unit.isEmpty()) it else "$it $unit" }
    return TrackColoring(colors, Legend.Ramp(num(redAt), num((redAt + blueAt) / 2f), right), values, unit)
}

/**
 * The per-point value series for [mode] (null where a point lacks the metric) and its display
 * unit — the single mode→series/unit mapping, feeding both the graph and the map coloring.
 */
// The display-system conversions at the precision a plotted series holds. Here rather than on
// [UnitSystem], which measures in doubles: the float is the graph's concern, not the system's.
private fun UnitSystem.plotSpeed(kmh: Float): Float = speedFrom(kmh.toDouble()).toFloat()

private fun UnitSystem.plotShort(meters: Double): Float = shortFrom(meters).toFloat()

private fun metricSeries(
    points: List<TrackPoint>,
    mode: ColorMode,
    speedsKmh: FloatArray,
    measures: Measures,
): Pair<List<Float?>, String> {
    val units = measures.system
    val symbols = measures.symbols
    return when (mode) {
        ColorMode.SPEED ->
            List(points.size) { units.plotSpeed(speedsKmh[it]) } to symbols.of(units.speedUnitId)
        ColorMode.ELEVATION ->
            points.map { it.altitude?.let(units::plotShort) } to symbols.of(units.shortUnitId)
        ColorMode.ACCURACY ->
            points.map { it.accuracy?.toDouble()?.let(units::plotShort) } to symbols.of(units.shortUnitId)
        ColorMode.SATELLITES ->
            points.map { it.satellitesInFix?.toFloat() } to symbols.of(FixUnit.SATELLITES)
        ColorMode.CN0 -> points.map { it.cn0 } to symbols.of(FixUnit.CARRIER_TO_NOISE)
    }
}

/**
 * [metricSeries] as everything that *shows* it reads it — the graph's line and readout, and the
 * colors the line and the map are drawn in. Speed alone is smoothed, and the reason is the line
 * above that computes it: it is the one metric derived per fix rather than reported about one — the
 * receiver's own figure where it gives one, the seam's where it doesn't — so it jitters by several
 * km/h between neighbours taken at one steady pace. The other four are what the receiver said about
 * that single moment, and a moment is what is being asked for when they are on screen.
 *
 * Exhaustive on purpose: a metric added later has to answer this, as it must answer for its series,
 * its ramp and its label.
 */
private fun plottedSeries(
    points: List<TrackPoint>,
    mode: ColorMode,
    values: List<Float?>,
): List<Float?> = when (mode) {
    ColorMode.SPEED -> MetricSmoother.timeAveraged(points, values)
    ColorMode.ELEVATION, ColorMode.ACCURACY, ColorMode.SATELLITES, ColorMode.CN0 -> values
}

/**
 * Per-point colors + legend for [mode]. Ramps go red→green→blue between two anchor values; where an
 * anchor is "worse" it's placed at red (e.g. accuracy: 50 m = red, 0 m = blue). Points missing the
 * metric are gray.
 *
 * Colors the series a graph would *plot* ([plottedSeries]), not the raw one: where a metric smooths,
 * a fix's hue and the height drawn above it have to be the same reading of the same moment, or the
 * line's colour argues with its own shape — and the map, which has no shape to argue with, would be
 * showing a jitter the graph says isn't there.
 */
internal fun trackColoring(
    points: List<TrackPoint>,
    speedsKmh: FloatArray,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    measures: Measures,
): TrackColoring {
    val units = measures.system
    // Anchors are hand-rounded in the display unit (see SpeedScale), so the legend reads round
    // numbers in every system; the colors may sit a hair apart between systems as a result.
    val (raw, unit) = metricSeries(points, mode, speedsKmh, measures)
    val values = plottedSeries(points, mode, raw)
    return when (mode) {
        ColorMode.SPEED -> {
            val s = speedScaleFor(activity ?: ActivityType.UNKNOWN, units)
            rampColoring(values, s.min, s.max, unit, mode, dark)
        }
        ColorMode.ELEVATION -> {
            // Anchors come from the track's own range; a zero-width span would make a flat track a
            // single hue. With no elevation at all the anchors go unread — rampColoring returns the
            // all-gray Legend.None for an all-null series.
            val present = values.filterNotNull()
            val lo = present.minOrNull() ?: 0f
            val span = ((present.maxOrNull() ?: 0f) - lo).coerceAtLeast(1f)
            rampColoring(values, lo, lo + span, unit, mode, dark)
        }
        // Lower accuracy radius is better, so zero sits at the blue (good) end. The red anchor is
        // hand-rounded per display unit like the speed scales: 150 ft, not the converted 164.
        ColorMode.ACCURACY ->
            rampColoring(values, units.byShortUnit(meters = 50f, feet = 150f), 0f, unit, mode, dark)
        ColorMode.SATELLITES -> rampColoring(values, 0f, 12f, unit, mode, dark)
        ColorMode.CN0 -> rampColoring(values, 15f, 45f, unit, mode, dark)
    }
}

/**
 * Horizontally-scrollable chips to pick how the track line is colored, with [caption] held at the
 * row's trailing edge where the track has something to say about itself.
 *
 * A caption rather than a chip, and deliberately not tappable: it is here because this is the row an
 * imported track comes up short in, so the word explaining that belongs beside the gap and not on a
 * screen of its own — and not in the top bar, which carries what the track *is* rather than what its
 * fixes are. It sits **outside** the scroll — a trailing element inside one has no fixed edge to sit
 * at, and would be reachable only by scrolling the chips to their end.
 */
@Composable
internal fun ColorModeSelector(
    selected: ColorMode,
    modes: List<ColorMode>,
    caption: String? = null,
    onSelect: (ColorMode) -> Unit,
) {
    Row(
        // No inset of its own: the row sits on the page beside cards, not inside one, so its own
        // padding would push the chips in from the edge every neighbour lines up on.
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // The weight is what keeps the chips from pushing the caption off the edge: they scroll
            // within what is left over, rather than the row growing to fit them.
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (mode in modes) {
                FilterToggleChip(
                    selected = mode == selected,
                    label = stringResource(mode.labelRes),
                    onClick = { onSelect(mode) },
                )
            }
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TrackLegend(legend: Legend, modifier: Modifier) {
    when (legend) {
        is Legend.None ->
            LegendSurface(modifier) {
                Text(stringResource(legend.messageRes), style = MaterialTheme.typography.labelSmall)
            }
        is Legend.Ramp ->
            LegendSurface(modifier) {
                val luminance = rampLuminance(isSystemInDarkTheme())
                // Dense stops along the same HSL ramp the track uses: the brush blends
                // neighbors in RGB, and RGB midpoints of red/green and green/blue are muddy
                // brown/gray — 30° hue steps stay on-hue.
                val rampBrush = remember(luminance) {
                    Brush.horizontalGradient(
                        (0..8).map { Color.hsl(bandedHue(it / 8f), SPEED_SATURATION, luminance) },
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

/** [LegendSurface]'s corners, shared so a caller that has to clip to them — a tappable one, for its
 *  ripple — cannot be left clipping to the shape this used to have. */
internal val legendShape = RoundedCornerShape(8.dp)

@Composable
internal fun LegendSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = legendShape,
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
