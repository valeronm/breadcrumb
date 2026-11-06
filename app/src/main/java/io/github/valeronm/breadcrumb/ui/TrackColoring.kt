package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MetricSmoother
import io.github.valeronm.breadcrumb.domain.PathSmoother
import io.github.valeronm.breadcrumb.domain.SeaLevel
import io.github.valeronm.breadcrumb.domain.SpeedSmoother
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
 * *fixes* — it falls back to position over time (`TrackQuality.pointSpeeds`), so a track that
 * draws a line has it — but a writer whose motion is implied rather than observed
 * ([TrackOrigin.measuresMotion]) rules it out as a kind too, which is how a manual track ends up
 * with no metrics at all and the screens that consult this render no selector for one.
 * Elevation is decided by [altitudes], the ones shown ([SeaLevel.altitudes]), rather than by the
 * stored ones a recording keeps where its geoid height is unknown.
 */
internal fun availableColorModes(points: List<TrackPoint>, altitudes: List<Double?>, source: TrackOrigin?): List<ColorMode> =
    ColorMode.entries.filter { mode ->
        !(mode.recorderOnly && source?.measuresFixQuality == false) &&
            !(mode == ColorMode.SPEED && source?.measuresMotion == false) &&
            mode.carriedBy(points, altitudes)
    }

/** Exhaustive on purpose, like [plottedSeries] it mirrors: a metric added later answers this too. */
private fun ColorMode.carriedBy(points: List<TrackPoint>, altitudes: List<Double?>): Boolean = when (this) {
    ColorMode.SPEED -> true
    ColorMode.ELEVATION -> altitudes.any { it != null }
    ColorMode.ACCURACY -> points.any { it.accuracy != null }
    ColorMode.SATELLITES -> points.any { it.satellitesInFix != null }
    ColorMode.CN0 -> points.any { it.cn0 != null }
}

/** The gray for what a map draws with no color of its own. */
internal fun mapNeutral(dark: Boolean): Color = Color.hsl(0f, 0f, if (dark) 0.6f else 0.45f)

/** An activity's line on a map, [mapNeutral] where the type is unknown. [dark] is [isMapDark], read
 *  by the caller since a batch of lines reads it once. */
internal fun mapActivityArgb(activity: ActivityType?, dark: Boolean): Int =
    activityColorOr(activity, mapNeutral(dark)).toArgb()

private fun noDataArgb(dark: Boolean) = mapNeutral(dark).toArgb()

/**
 * A track's overrun, drawn beside the measured path rather than on it. The dark basemap needs a
 * darker gray than the track to read as receding, the light basemap a lighter one.
 */
internal fun offPathArgb(dark: Boolean): Int = if (dark) OFF_PATH_DARK else OFF_PATH_LIGHT

private const val OFF_PATH_DARK = 0xD9424242.toInt()
private const val OFF_PATH_LIGHT = 0xD9BDBDBD.toInt()

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

    /** One color along the whole line, standing for no metric, so there is nothing to explain. */
    data object Solid : Legend
}

internal fun solidColoring(size: Int, argb: Int) =
    TrackColoring(IntArray(size) { argb }, Legend.Solid, List(size) { null }, unit = "")

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
    private val paint: RampPaint? = null,
) {
    /** The ramp's color for [value]; null on a solid coloring, which has no ramp. */
    fun colorOf(value: Float): Int? = paint?.let {
        rampColor(value, it.redAt, it.blueAt, rampPalette(rampLuminance(it.dark)), noDataArgb(it.dark))
    }

    /** A solid coloring comes back unchanged, having no ramp to repaint. */
    fun inShade(dark: Boolean): TrackColoring =
        if (paint == null || paint.dark == dark) {
            this
        } else {
            rampColoring(values, unit, paint.copy(dark = dark))
        }
}

internal data class RampPaint(val redAt: Float, val blueAt: Float, @StringRes val noDataRes: Int, val dark: Boolean)

/** The plot's own label precision — ungrouped digits, unlike [Measures]' stat rows, and the unit only where given. */
internal fun plotLabel(value: Float, unit: String = ""): String =
    if (unit.isEmpty()) "%.0f".format(value) else "%.0f %s".format(value, unit)

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
    unit: String,
    paint: RampPaint,
): TrackColoring {
    // Resolved once per coloring, not per point — Color.hsl is a real conversion.
    val noData = noDataArgb(paint.dark)
    if (values.all { it == null }) {
        return TrackColoring(IntArray(values.size) { noData }, Legend.None(paint.noDataRes), values, unit, paint)
    }
    val palette = rampPalette(rampLuminance(paint.dark))
    val colors = IntArray(values.size) { rampColor(values[it], paint.redAt, paint.blueAt, palette, noData) }
    // Unit only on the rightmost label, else three "… unit" labels overflow the fixed-width legend.
    val legend = Legend.Ramp(
        plotLabel(paint.redAt),
        plotLabel((paint.redAt + paint.blueAt) / 2f),
        plotLabel(paint.blueAt, unit),
    )
    return TrackColoring(colors, legend, values, unit, paint)
}

// The display-system conversions at the precision a plotted series holds. Here rather than on
// [UnitSystem], which measures in doubles: the float is the graph's concern, not the system's.
private fun UnitSystem.plotSpeed(kmh: Float): Float = speedFrom(kmh.toDouble()).toFloat()

private fun UnitSystem.plotShort(meters: Double): Float = shortFrom(meters).toFloat()

/**
 * The per-point value series for [mode] (null where a point lacks the metric) and its display unit, as everything
 * that *shows* it reads it — the graph's line and readout, and the colors the line and the map are drawn in. Where
 * [smoothed] and the track is not [unmeasured] ([PathSmoother.moves]), speed is smoothed ([SpeedSmoother]) because a
 * whole trip's second-to-second changes crowd out its stops and pulls, and elevation because a GNSS altitude wanders
 * by meters between neighbors on level ground. The rest are what the receiver said about that single moment, and a
 * moment is what is being asked for when they are on screen. Elevation plots [PlottedPoints.altitudes].
 *
 * Exhaustive on purpose: a metric added later has to answer this, as it must answer for its ramp and its label.
 */
internal fun plottedSeries(
    plotted: PlottedPoints,
    mode: ColorMode,
    measures: Measures,
    unmeasured: Boolean,
    smoothed: Boolean,
): PlottedSeries {
    val smooth = smoothed && PathSmoother.moves(unmeasured)
    val seams = plotted.seams
    val points = seams.points
    val units = measures.system
    val symbols = measures.symbols
    return when (mode) {
        ColorMode.SPEED -> {
            val speeds = TrackQuality.pointSpeeds(seams)
            val kmh = if (smooth) SpeedSmoother.smoothedKmh(points, speeds.kmh, speeds.errorKmh, seams.graphBreaks) else speeds.kmh
            PlottedSeries(mode, units, List(points.size) { units.plotSpeed(kmh[it]) }, symbols.of(units.speedUnitId))
        }
        ColorMode.ELEVATION -> {
            val meters = plotted.altitudes.map { it?.let(units::plotShort) }
            val plotted = if (smooth) MetricSmoother.timeAveraged(points, meters, seams.graphBreaks) else meters
            PlottedSeries(mode, units, plotted, symbols.of(units.shortUnitId))
        }
        ColorMode.ACCURACY ->
            PlottedSeries(mode, units, points.map { it.accuracy?.toDouble()?.let(units::plotShort) }, symbols.of(units.shortUnitId))
        ColorMode.SATELLITES ->
            PlottedSeries(mode, units, points.map { it.satellitesInFix?.toFloat() }, symbols.of(FixUnit.SATELLITES))
        ColorMode.CN0 -> PlottedSeries(mode, units, points.map { it.cn0 }, symbols.of(FixUnit.CARRIER_TO_NOISE))
    }
}

/** [seams] and the altitudes its points are shown at ([SeaLevel.altitudes]), one per point. */
internal class PlottedPoints(val seams: TrackQuality.Seams, val altitudes: List<Double?>) {
    init {
        require(altitudes.size == seams.points.size) { "${altitudes.size} altitudes for ${seams.points.size} points" }
    }
}

/** [mode]'s per-point series in [units], null where a point lacks the metric, and the unit symbol its values carry. */
internal class PlottedSeries(val mode: ColorMode, val units: UnitSystem, val values: List<Float?>, val unit: String)

/**
 * Per-point colors + legend for [mode]. Ramps go red→green→blue between two anchor values; where an
 * anchor is "worse" it's placed at red (e.g. accuracy: 50 m = red, 0 m = blue). Points missing the
 * metric are gray.
 *
 * [series] is the one a graph plots ([plottedSeries]), not the raw one: where a metric smooths,
 * a fix's hue and the height drawn above it have to be the same reading of the same moment, or the
 * line's colour argues with its own shape — and the map, which has no shape to argue with, would be
 * showing a jitter the graph says isn't there.
 */
internal fun trackColoring(series: PlottedSeries, activity: ActivityType?, dark: Boolean): TrackColoring {
    // Anchors are hand-rounded in the display unit (see SpeedScale), so the legend reads round
    // numbers in every system; the colors may sit a hair apart between systems as a result.
    val mode = series.mode
    val units = series.units
    val values = series.values
    val unit = series.unit
    return when (mode) {
        ColorMode.SPEED -> {
            val s = speedScaleFor(activity ?: ActivityType.UNKNOWN, units)
            rampColoring(values, unit, RampPaint(s.min, s.max, mode.noDataRes, dark))
        }
        ColorMode.ELEVATION -> {
            // Anchors come from the track's own range; a zero-width span would make a flat track a
            // single hue. With no elevation at all the anchors go unread — rampColoring returns the
            // all-gray Legend.None for an all-null series.
            val present = values.filterNotNull()
            val lo = present.minOrNull() ?: 0f
            val span = ((present.maxOrNull() ?: 0f) - lo).coerceAtLeast(1f)
            rampColoring(values, unit, RampPaint(lo, lo + span, mode.noDataRes, dark))
        }
        // Lower accuracy radius is better, so zero sits at the blue (good) end. The red anchor is
        // hand-rounded per display unit like the speed scales: 150 ft, not the converted 164.
        ColorMode.ACCURACY ->
            rampColoring(values, unit, RampPaint(units.byShortUnit(meters = 50f, feet = 150f), 0f, mode.noDataRes, dark))
        ColorMode.SATELLITES -> rampColoring(values, unit, RampPaint(0f, 12f, mode.noDataRes, dark))
        ColorMode.CN0 -> rampColoring(values, unit, RampPaint(15f, 45f, mode.noDataRes, dark))
    }
}

@Composable
internal fun ColorModeSelector(
    selected: ColorMode,
    modes: List<ColorMode>,
    onSelect: (ColorMode) -> Unit,
) {
    Row(
        // A chip scrolled toward an edge runs on past the inset to the sheet's edge.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
}

@Composable
internal fun TrackLegend(legend: Legend) {
    when (legend) {
        Legend.Solid -> Unit
        is Legend.None ->
            LegendSurface {
                Text(stringResource(legend.messageRes), style = MaterialTheme.typography.labelSmall)
            }
        is Legend.Ramp ->
            LegendSurface {
                val luminance = rampLuminance(isMapDark())
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

/** The fill of what floats over a map, translucent so the basemap still reads through it. */
internal val mapOverlayColor: Color
    @Composable get() = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

@Composable
internal fun LegendSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = legendShape,
        color = mapOverlayColor,
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
