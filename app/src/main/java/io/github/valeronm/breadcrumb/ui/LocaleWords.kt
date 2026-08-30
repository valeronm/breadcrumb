package io.github.valeronm.breadcrumb.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TravelLabel
import io.github.valeronm.breadcrumb.util.PerLocale
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Epoch millis → the local calendar date in [zone]. */
internal fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** How much room a relative day has where it renders. */
internal enum class RelativeDayStyle {
    /** An abbreviated month: "29 Nov 2025". */
    SHORT,

    /** The month spelled out: "29 November 2025", for a line with nothing else on it. */
    FULL,
}

/** Coarse relative day for "last seen": today / yesterday / N days ago / a date. */
@Composable
@ReadOnlyComposable
internal fun relativeDay(epochMs: Long, style: RelativeDayStyle = RelativeDayStyle.SHORT): String {
    val zone = ZoneId.systemDefault()
    val then = epochMs.toLocalDate(zone)
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(then, today)
    val full = style == RelativeDayStyle.FULL
    return when {
        days <= 0 -> stringResource(R.string.relative_today)
        days == 1L -> stringResource(R.string.relative_yesterday)
        days < 7 -> pluralStringResource(R.plurals.relative_days_ago, days.toInt(), days.toInt())
        then.year == today.year -> then.format(if (full) fullDayFormat else compactDayFormat)
        else -> then.format(if (full) fullDayYearFormat else compactDayYearFormat)
    }
}

/** What heads a journey, worded: its destinations, or how many nights it ran. */
@Composable
@ReadOnlyComposable
internal fun travelTitle(label: TravelLabel): String = when (label) {
    is TravelLabel.Destinations -> label.title
    is TravelLabel.NightsAway ->
        pluralStringResource(R.plurals.timeline_nights_away, label.nights, label.nights)
}

/**
 * Title-cases a date that stands on its own — a section header, not a date inside a sentence.
 * Portuguese and its neighbours write months and weekdays lowercase, being common nouns, and the
 * platform capitalizes them in this position (the status bar reads "Terça, 4/08"). ICU does it from
 * the stand-alone display context; `java.time` has no equivalent, so headers do it on the way out.
 *
 * Never apply this to a date inside a phrase, where the lowercase form is the correct one.
 *
 * Takes the current default locale, which is where [PerLocale] gets the formatter's — so the
 * casing rule and the words it applies to can never come from two different languages.
 */
internal fun String.standaloneCase(): String =
    replaceFirstChar { it.titlecase(Locale.getDefault()) }

/**
 * A pattern the *locale* chooses, from a skeleton naming only which fields it should contain. Field
 * order and separators differ by language — day before month here, month before day there — so a
 * literal pattern hands every language English conventions.
 *
 * A skeleton naming an hour picks `H` or `h` explicitly rather than `j` — see [ReaderClock], which
 * is the only code here that formats one, and which says why the locale is the wrong authority.
 *
 * **This reaches the Android framework**, so nothing a plain-JVM test can call may format a date.
 * That is why the timeline's grouping returns dates and the screen renders them: a grouping that
 * produced header *text* dragged this call into `TimelineDayGroupingTest`, where it is not mocked.
 */
internal fun localizedDateFormat(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

internal val compactDayFormat by PerLocale { localizedDateFormat("dMMM", it) }

internal val compactDayYearFormat by PerLocale { localizedDateFormat("dMMMy", it) }

internal val fullDayFormat by PerLocale { localizedDateFormat("dMMMM", it) }

internal val fullDayYearFormat by PerLocale { localizedDateFormat("dMMMMy", it) }

/**
 * The flag emoji for an ISO 3166-1 alpha-2 code — two regional-indicator code points, which is the
 * mechanical mapping every flag rests on, so every country the atlas knows has one without a
 * table to keep in step. A device with no glyph for a given pair renders the two letters instead,
 * which is the same information. Empty for anything that is not a two-letter upper-case code.
 */
internal fun flagOf(country: String): String {
    if (country.length != 2 || !country.all { it in 'A'..'Z' }) return ""
    return country.map { Character.toChars(REGIONAL_INDICATOR_A + (it - 'A')).concatToString() }
        .joinToString("")
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6

/** The device-locale display name of an ISO 3166-1 alpha-2 code, empty when it resolves to
 *  nothing — each caller decides what an unresolvable country should read as. */
internal fun countryNameOf(code: String, locale: Locale): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale)

/** [countryNameOf] remembered per code — the ICU lookup would otherwise re-run on every
 *  recomposition of every row naming one — falling back to the raw code where it resolves to
 *  nothing. */
@Composable
internal fun countryDisplayName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(code, locale) { countryNameOf(code, locale).ifEmpty { code } }
}

// `LLLL`, not `MMMM`: a month named on its own takes the stand-alone form, which in the Slavic
// languages is the nominative — `MMMM` there yields the genitive a full date needs ("of July").
private val monthFormat by PerLocale { DateTimeFormatter.ofPattern("LLLL", it) }

// A month *with* its year is a phrase, not a bare noun, so this one keeps the format form — pt
// writes "julho de 2026", and the connecting word arrives with the locale's own pattern.
private val monthYearFormat by PerLocale { localizedDateFormat("yMMMM", it) }

/**
 * A month and its year, both always stated — for a heading whose reader has no other date on screen
 * to place it by. Stands on its own, so it takes the capital its language gives it there.
 */
internal fun monthYearLabel(month: YearMonth): String =
    month.format(monthYearFormat).standaloneCase()

/** A month heading beside dates that already say the year, so this one drops it where it is [today]'s. */
internal fun monthLabel(month: YearMonth, today: LocalDate): String =
    if (month.year == today.year) {
        month.format(monthFormat).standaloneCase()
    } else {
        monthYearLabel(month)
    }
