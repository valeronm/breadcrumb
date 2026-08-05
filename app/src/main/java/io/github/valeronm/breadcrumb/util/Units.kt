package io.github.valeronm.breadcrumb.util

import kotlin.math.roundToInt

private const val KM_PER_MI = 1.609344
private const val M_PER_FT = 0.3048
private const val FT_PER_M = 1.0 / M_PER_FT

/**
 * The persisted display-units choice. Storage and domain logic stay metric throughout — this only
 * decides how numbers are rendered. The entry name is what persists; what each one reads as is
 * `UnitChoice.labelRes` in the UI layer, where language belongs.
 */
enum class UnitChoice {
    SYSTEM,
    METRIC,
    IMPERIAL,
    UK,
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

/**
 * Which unit a system measures in — an identity, with no text attached. The display tables are
 * keyed by these, so a translated symbol can't pick the wrong ladder the way `shortUnit == "m"`
 * would the moment a locale renders that as `"м"`; and METRIC and UK name the same short unit once.
 */
enum class BigUnit { KILOMETERS, MILES }

/** See [BigUnit] — the unit short distances are measured in. */
enum class ShortUnit { METERS, FEET }

/** See [BigUnit] — the unit speeds are measured in. */
enum class SpeedUnit { KMH, MPH }

/**
 * What a receiver reports about a fix of its own, in units no display system converts — a count and
 * a ratio measure the same everywhere. They are still *spelled* by a language, which is the whole
 * reason they are named here rather than written into the one screen that plots them.
 */
enum class FixUnit { SATELLITES, CARRIER_TO_NOISE }

/**
 * How the host spells each unit the app writes beside a number — the seam that keeps this file free
 * of language, so a locale writing `"км"` needs no change here. The display-system units are three
 * of the four; [FixUnit] is spelled by the same host for the same reason, and asking it here is what
 * keeps a caller from writing the word itself for want of anywhere to look it up.
 *
 * Deliberately not a formatter: the rounding rules below are this app's own (a dropped zero tenth,
 * grouped whole units past 100, a padded minute in the duration ladder), and a measure formatter
 * would re-decide all of them.
 */
interface UnitSymbols {
    fun of(unit: BigUnit): String

    fun of(unit: ShortUnit): String

    fun of(unit: SpeedUnit): String

    fun of(unit: FixUnit): String
}

/**
 * A display system and the words for it. The two are chosen from different halves of the same
 * locale — the country picks the system, the language picks the symbols — and everything that
 * renders a measure needs both, so **rendering lives here rather than on either half**. That is what
 * makes the pairing an invariant instead of a claim: there is no signature left that takes a system
 * and a set of symbols separately, so there is nowhere to pass a mismatched pair.
 *
 * [UnitSystem] keeps the arithmetic and the unit identities, which are the half a graph series and a
 * ramp anchor need without any words at all.
 */
class Measures(val system: UnitSystem, val symbols: UnitSymbols) {

    /**
     * A track-length distance in the big unit (km or mi). One decimal below 100 (dropped when it's
     * zero: "4 km", not "4,0 km"); beyond that the tenth is noise, so whole (locale-grouped) units.
     */
    fun distance(meters: Double): String {
        val value = system.bigFrom(meters)
        val unit = symbols.of(system.bigUnitId)
        if (value >= 100) return "%,.0f $unit".format(value)
        // The decimal separator is locale-dependent — strip either form of a zero tenth.
        val s = "%.1f".format(value).removeSuffix(",0").removeSuffix(".0")
        return "$s $unit"
    }

    /** A whole-number speed (km/h or mph), from the km/h value. */
    fun speedFromKmh(kmh: Double): String =
        "%.0f ${symbols.of(system.speedUnitId)}".format(system.speedFrom(kmh))

    /** A whole-number short distance (m or ft) — elevations, accuracy radii, place radii. */
    fun shortDistance(meters: Double): String =
        "%,.0f ${symbols.of(system.shortUnitId)}".format(system.shortFrom(meters))

    /**
     * The scale for a distance slider that stores meters: metric users get the [metric] stops,
     * imperial users the round-feet [feet] ones. The two should span roughly the same range;
     * they need not (and round numbers mean they can't) match stop for stop.
     */
    fun sliderScale(
        metric: SliderStops,
        feet: SliderStops,
        /** What a zero stop reads as where zero means off; null when zero is a real value. */
        offLabel: String? = null,
    ): DistanceSliderScale = DistanceSliderScale(
        system.byShortUnit(metric, feet),
        system.byShortUnit(1.0, M_PER_FT),
        symbols.of(system.shortUnitId),
        offLabel,
    )
}

/** Plain ASCII, for tests and for any surface with no resources to reach — every unit, not just the
 *  converted ones, since an implementer that answered only some would be a half-spelled host. */
object AsciiUnits : UnitSymbols {
    override fun of(unit: BigUnit) = if (unit == BigUnit.KILOMETERS) "km" else "mi"

    override fun of(unit: ShortUnit) = if (unit == ShortUnit.METERS) "m" else "ft"

    override fun of(unit: SpeedUnit) = if (unit == SpeedUnit.KMH) "km/h" else "mph"

    override fun of(unit: FixUnit) = if (unit == FixUnit.SATELLITES) "sat" else "dB"
}

/**
 * The arithmetic and the unit identities of one display system; all inputs are the metric values as
 * stored. No words and no formatting — those need the symbols too, so they live on [Measures].
 */
enum class UnitSystem(
    private val metersPerBig: Double,
    val bigUnitId: BigUnit,
    private val shortPerMeter: Double,
    val shortUnitId: ShortUnit,
    private val kmhPerSpeedUnit: Double,
    val speedUnitId: SpeedUnit,
) {
    METRIC(1000.0, BigUnit.KILOMETERS, 1.0, ShortUnit.METERS, 1.0, SpeedUnit.KMH),
    IMPERIAL(1000.0 * KM_PER_MI, BigUnit.MILES, FT_PER_M, ShortUnit.FEET, KM_PER_MI, SpeedUnit.MPH),

    // The British mix: miles and mph on the road, meters for everything short-range.
    UK(1000.0 * KM_PER_MI, BigUnit.MILES, 1.0, ShortUnit.METERS, KM_PER_MI, SpeedUnit.MPH),
    ;

    /** A meters value as this system's track-length number. */
    fun bigFrom(meters: Double): Double = meters / metersPerBig

    /** A km/h value as this system's speed number. */
    fun speedFrom(kmh: Double): Double = kmh / kmhPerSpeedUnit

    /** A meters value as this system's short-distance number. */
    fun shortFrom(meters: Double): Double = meters * shortPerMeter

    /**
     * Picks the hand-rounded display table for this system's short-distance unit. Round ladders
     * and anchors are authored once per unit, and every selection derives from the unit the
     * system already declares — so a new system needs no call-site edits.
     */
    fun <T> byShortUnit(meters: T, feet: T): T = if (shortUnitId == ShortUnit.METERS) meters else feet

    /** Picks the hand-rounded display table for this system's speed unit; see [byShortUnit]. */
    fun <T> bySpeedUnit(kmh: T, mph: T): T = if (speedUnitId == SpeedUnit.KMH) kmh else mph
}

/** One unit system's stops for a distance slider: [min]..[max] in that system's unit, by [step]. */
data class SliderStops(val min: Int, val max: Int, val step: Int)

/**
 * A distance slider's stops in the display system's own unit — round meters for metric users, round
 * feet for imperial ones — mapped to and from the stored meters. Drag and label both use the display
 * value directly (a round trip through meters drifts: 50 ft → 15 m → "49 ft"); only the committed
 * value converts. Switching systems snaps the stored meters to the new scale's nearest stop.
 */
class DistanceSliderScale internal constructor(
    private val stops: SliderStops,
    private val metersPerUnit: Double,
    private val unit: String,
    private val offLabel: String?,
) {
    val range: ClosedFloatingPointRange<Float> = stops.min.toFloat()..stops.max.toFloat()

    /** The nearest stop to a raw drag position (display units). */
    fun snap(raw: Float): Float = snapToStep(raw, stops.step, range)

    /** The stop a stored meters value lands on. */
    fun displayOf(meters: Int): Float = snap((meters / metersPerUnit).toFloat())

    /** The meters to store for a display-unit stop. */
    fun metersOf(display: Float): Int = (display * metersPerUnit).roundToInt()

    /** The stop's label: the off wording where zero means off, else a grouped whole number + unit. */
    fun label(display: Float): String =
        if (offLabel != null && display <= 0f) offLabel else "%,d $unit".format(display.roundToInt())
}
