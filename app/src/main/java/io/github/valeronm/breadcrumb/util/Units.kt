package io.github.valeronm.breadcrumb.util

import kotlin.math.roundToInt

private const val KM_PER_MI = 1.609344
private const val M_PER_FT = 0.3048
private const val FT_PER_M = 1.0 / M_PER_FT

/**
 * The persisted display-units choice. Storage and domain logic stay metric throughout — this only
 * decides how numbers are rendered.
 */
enum class UnitChoice(val label: String) {
    SYSTEM("Automatic"),
    METRIC("Kilometres"),
    IMPERIAL("Miles"),
    UK("Miles + metres"),
    ;

    /** The system to format with; SYSTEM resolves by [country] (ISO 3166, `Locale.country`). */
    fun resolve(country: String): UnitSystem = when (this) {
        METRIC -> UnitSystem.METRIC
        IMPERIAL -> UnitSystem.IMPERIAL
        UK -> UnitSystem.UK
        // The non-metric countries (US, Liberia, Myanmar) and the UK's mixed system — the same
        // three-plus-one split as ICU's MeasurementSystem data.
        SYSTEM -> when (country.uppercase()) {
            "US", "LR", "MM" -> UnitSystem.IMPERIAL
            "GB" -> UnitSystem.UK
            else -> UnitSystem.METRIC
        }
    }

    companion object {
        /** The stored name back to a choice; unknown or absent falls back to SYSTEM. */
        fun fromName(name: String?): UnitChoice = entries.find { it.name == name } ?: SYSTEM
    }
}

/** Every user-visible distance/speed rendering; all inputs are the metric values as stored. */
enum class UnitSystem(
    private val metersPerBig: Double,
    private val bigUnit: String,
    private val shortPerMeter: Double,
    val shortUnit: String,
    private val kmhPerSpeedUnit: Double,
    val speedUnit: String,
) {
    METRIC(1000.0, "km", 1.0, "m", 1.0, "km/h"),
    IMPERIAL(1000.0 * KM_PER_MI, "mi", FT_PER_M, "ft", KM_PER_MI, "mph"),

    // The British mix: miles and mph on the road, metres for everything short-range.
    UK(1000.0 * KM_PER_MI, "mi", 1.0, "m", KM_PER_MI, "mph"),
    ;

    /**
     * A track-length distance in the big unit (km or mi). One decimal below 100 (dropped when it's
     * zero: "4 km", not "4,0 km"); beyond that the tenth is noise, so whole (locale-grouped) units.
     */
    fun distance(meters: Double): String = bigFormat(meters / metersPerBig, bigUnit)

    /** A whole-number speed (km/h or mph), from the km/h value. */
    fun speedFromKmh(kmh: Double): String = "%.0f $speedUnit".format(kmh / kmhPerSpeedUnit)

    /** A whole-number short distance (m or ft) — elevations, accuracy radii, place radii. */
    fun shortDistance(meters: Double): String = "%,.0f $shortUnit".format(meters * shortPerMeter)

    /**
     * The scale for a distance slider that stores metres: metric users get the [metric] stops,
     * imperial users the round-feet [feet] ones. The two should span roughly the same range;
     * they need not (and round numbers mean they can't) match stop for stop.
     */
    fun sliderScale(
        metric: SliderStops,
        feet: SliderStops,
        zeroIsOff: Boolean = false,
    ): DistanceSliderScale =
        DistanceSliderScale(byShortUnit(metric, feet), byShortUnit(1.0, M_PER_FT), shortUnit, zeroIsOff)

    /**
     * Picks the hand-rounded display table for this system's short-distance unit. Round ladders
     * and anchors are authored once per unit, and every selection derives from the unit the
     * system already declares — so a new system needs no call-site edits.
     */
    fun <T> byShortUnit(metres: T, feet: T): T = if (shortUnit == "m") metres else feet

    /** Picks the hand-rounded display table for this system's speed unit; see [byShortUnit]. */
    fun <T> bySpeedUnit(kmh: T, mph: T): T = if (speedUnit == "km/h") kmh else mph

    /** A km/h value as this system's speed number — for graph series and ramp anchors. */
    fun fromKmh(kmh: Float): Float = (kmh / kmhPerSpeedUnit).toFloat()

    /** A metres value as this system's short-distance number — for graph series and ramp anchors. */
    fun fromMeters(m: Float): Float = (m * shortPerMeter).toFloat()

    private fun bigFormat(value: Double, unit: String): String {
        if (value >= 100) return "%,.0f $unit".format(value)
        // The decimal separator is locale-dependent — strip either form of a zero tenth.
        val s = "%.1f".format(value).removeSuffix(",0").removeSuffix(".0")
        return "$s $unit"
    }
}

/** One unit system's stops for a distance slider: [min]..[max] in that system's unit, by [step]. */
data class SliderStops(val min: Int, val max: Int, val step: Int)

/**
 * A distance slider's stops in the display system's own unit — round metres for metric users,
 * round feet for imperial ones — mapped to and from the stored metres. The slider drags in
 * display units and the label comes from the display value directly (never round-tripped
 * through metres, which would drift: 50 ft → 15 m → "49 ft"); only the committed value is
 * converted. Switching systems snaps the stored metres to the nearest stop of the new scale.
 */
class DistanceSliderScale internal constructor(
    private val stops: SliderStops,
    private val metersPerUnit: Double,
    private val unit: String,
    private val zeroIsOff: Boolean,
) {
    val range: ClosedFloatingPointRange<Float> = stops.min.toFloat()..stops.max.toFloat()

    /** The nearest stop to a raw drag position (display units). */
    fun snap(raw: Float): Float = snapToStep(raw, stops.step, range)

    /** The stop a stored metres value lands on. */
    fun displayOf(meters: Int): Float = snap((meters / metersPerUnit).toFloat())

    /** The metres to store for a display-unit stop. */
    fun metersOf(display: Float): Int = (display * metersPerUnit).roundToInt()

    /** The stop's label: "Off" where zero means off, else a grouped whole number + unit. */
    fun label(display: Float): String =
        if (zeroIsOff && display <= 0f) "Off" else "%,d $unit".format(display.roundToInt())
}
