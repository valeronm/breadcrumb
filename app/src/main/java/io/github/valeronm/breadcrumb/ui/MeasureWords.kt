package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.util.BigUnit
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.ShortUnit
import io.github.valeronm.breadcrumb.util.SpeedUnit
import io.github.valeronm.breadcrumb.util.UnitChoice
import io.github.valeronm.breadcrumb.util.UnitSymbols

/**
 * The Android half of [UnitSymbols] and [DurationSymbols] — the resources behind every unit the app
 * writes beside a number. Resolved per call rather than captured: the recorder's process outlives
 * the UI by weeks, and a captured symbol would keep speaking the language the process started in.
 */
internal fun unitSymbols(context: Context): UnitSymbols = object : UnitSymbols {
    override fun of(unit: BigUnit) = context.getString(
        if (unit == BigUnit.KILOMETERS) R.string.unit_kilometers else R.string.unit_miles,
    )

    override fun of(unit: ShortUnit) = context.getString(
        if (unit == ShortUnit.METERS) R.string.unit_meters else R.string.unit_feet,
    )

    override fun of(unit: SpeedUnit) = context.getString(
        if (unit == SpeedUnit.KMH) R.string.unit_kmh else R.string.unit_mph,
    )
}

/**
 * What each unit-system choice reads as, here rather than on [UnitChoice] for the reason a place
 * category's name is: the entry name persists, the wording is language.
 */
internal val UnitChoice.labelRes: Int
    @StringRes get() = when (this) {
        UnitChoice.SYSTEM -> R.string.units_choice_automatic
        UnitChoice.METRIC -> R.string.units_choice_metric
        UnitChoice.IMPERIAL -> R.string.units_choice_imperial
        UnitChoice.UK -> R.string.units_choice_uk
    }

/** See [unitSymbols] — the same for the duration ladder's rungs. */
internal fun durationSymbols(context: Context): DurationSymbols = object : DurationSymbols {
    override val year get() = context.getString(R.string.duration_year)
    override val month get() = context.getString(R.string.duration_month)
    override val day get() = context.getString(R.string.duration_day)
    override val hour get() = context.getString(R.string.duration_hour)
    override val minute get() = context.getString(R.string.duration_minute)
    override val second get() = context.getString(R.string.duration_second)
    override val recording get() = context.getString(R.string.duration_recording)
}

/**
 * The system and its symbols in composition, as the one object that pairs them — providing them
 * apart is the mismatch [Measures] exists to rule out, and a composition local is the one place a
 * caller could still have done it.
 *
 * **No default.** A readable fallback would only ever fire where the provider is missing, and there
 * it renders silently wrong — kilometres to a reader who measures in miles, English symbols in every
 * language — which is the failure this whole seam exists to make impossible. The app provides these
 * once, at the root of its one composition (`TimelineRowTest` provides them again around a bare row);
 * a composable reached under neither is a wiring mistake and should say so.
 */
internal val LocalMeasures = staticCompositionLocalOf<Measures> {
    error("no Measures provided — every composition sits under MainActivity's provider")
}

internal val LocalDurationSymbols = staticCompositionLocalOf<DurationSymbols> {
    error("no DurationSymbols provided — every composition sits under MainActivity's provider")
}

/** A track-length distance in the display system's big unit, spelled by the current locale. */
@Composable
@ReadOnlyComposable
internal fun distanceText(meters: Double): String = LocalMeasures.current.distance(meters)

/** A speed, from the km/h value. */
@Composable
@ReadOnlyComposable
internal fun speedText(kmh: Double): String = LocalMeasures.current.speedFromKmh(kmh)

/** A short distance — elevations, accuracy radii, place radii. */
@Composable
@ReadOnlyComposable
internal fun shortDistanceText(meters: Double): String = LocalMeasures.current.shortDistance(meters)

/** A duration on the ladder, spelled by the current locale. */
@Composable
@ReadOnlyComposable
internal fun durationText(durationMs: Long): String =
    formatDurationMs(durationMs, LocalDurationSymbols.current)

/** A track's length, or the recording wording while it is still open. */
@Composable
@ReadOnlyComposable
internal fun durationText(startedAt: Long, endedAt: Long?): String =
    formatDuration(startedAt, endedAt, LocalDurationSymbols.current)
