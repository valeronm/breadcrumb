package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.provider.Settings as SystemSettings

/**
 * How a reader's own device writes a clock time — **the app's only clock format**, so a change to it
 * lands everywhere at once, and [dateTime] is the only date-and-time one. The debug log's timestamps
 * are the one thing on screen that does not come through here, deliberately: a log line is never
 * localized and never follows a display setting, so it keeps its own fixed pattern.
 *
 * Two halves of one question, from two different places, which is why they are paired here rather
 * than asked separately: the **locale** arranges the fields and separators, while the **device's
 * 12/24 setting** decides the hour cycle. `getBestDateTimePattern` only ever sees a `Locale`, so a
 * skeleton asking for `j` — the locale's *preferred* cycle — answers the wrong question and reads
 * 12-hour for a reader in an en-US region who has set their phone to 24. The setting is the answer,
 * and it is the one Material's own time picker already reads.
 *
 * Deliberately not a shared [java.text.SimpleDateFormat] with a zone set on it: that class carries a
 * mutable calendar, so one instance retimed per row is a data race between two rows in different
 * zones and a wrong time in whichever loses. [DateTimeFormatter] is immutable, which is what makes
 * one instance safe across rows that disagree about the clock.
 *
 * **Build one per reader, not one per render.** [LocalReaderClock] is a static local, so handing it a
 * fresh instance invalidates every composition that reads it — which is most of the app. The two
 * entry points below both keep that in mind; a third should too.
 */
internal class ReaderClock(locale: Locale, hour24: Boolean) {
    private val hourMinute = localizedDateFormat(if (hour24) "Hm" else "hm", locale)

    private val dayAndHourMinute = localizedDateFormat(if (hour24) "dMMMHm" else "dMMMhm", locale)

    /** A clock time in [zone]. Callers with no place behind them pass the device's own. */
    fun time(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(hourMinute)

    /** [time]'s longer form, for a screen naming one moment. */
    fun dateTime(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(dayAndHourMinute)
}

/**
 * The clock [context] is configured to keep, for a caller with no composition to take
 * [LocalReaderClock] from — read per call rather than captured, for the reason every string in the
 * recorder's vocabulary is: that process outlives the UI by weeks, and a captured clock would go on
 * writing 24-hour times after the reader had switched their phone to 12.
 *
 * Both halves are asked of [context], so a caller cannot answer them for two different
 * configurations. A composition asks [rememberReaderClock] instead, which takes the locale from the
 * configuration composition itself observes.
 */
internal fun readerClockOf(context: Context): ReaderClock =
    ReaderClock(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))

/**
 * [readerClockOf] for a composition, watching both halves so a reader who changes either sees the
 * screen follow.
 *
 * The hour cycle needs watching for a reason the language does not: **changing 12/24 is not a
 * configuration change.** Android recreates nothing and recomposes nothing, so a clock read once at
 * the root would keep writing the old cycle for as long as this process lives — which here is weeks.
 * Observed rather than re-read on resume, which is how this screen refreshes its other out-of-band
 * system state: the flip is made in another app and can land while this one is visible behind it,
 * and an observer costs one registration for the composition's life.
 *
 * Remembered on both halves, because the value feeds a static local: a new instance per
 * recomposition would recompose everything under the provider.
 */
@Composable
internal fun rememberReaderClock(): ReaderClock {
    val context = LocalContext.current
    var hour24 by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                hour24 = DateFormat.is24HourFormat(context)
            }
        }
        context.contentResolver.registerContentObserver(
            SystemSettings.System.getUriFor(SystemSettings.System.TIME_12_24),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, hour24) { ReaderClock(locale, hour24) }
}

/**
 * The reader's clock in composition. **No default**, for the reason [LocalMeasures] has none: a
 * fallback would fire exactly where the provider is missing and render a plausible wrong time there,
 * which is the failure this seam exists to make impossible.
 */
internal val LocalReaderClock = staticCompositionLocalOf<ReaderClock> {
    error("no ReaderClock provided — every composition sits under MainActivity's provider")
}

/** A clock time in [zone], on the reader's own clock — the [distanceText] of times. */
@Composable
@ReadOnlyComposable
internal fun timeText(epochMs: Long, zone: ZoneId): String =
    LocalReaderClock.current.time(epochMs, zone)

/** [timeText]'s longer form, for a screen naming one moment. */
@Composable
@ReadOnlyComposable
internal fun dateTimeText(epochMs: Long, zone: ZoneId): String =
    LocalReaderClock.current.dateTime(epochMs, zone)
