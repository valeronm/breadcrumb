package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * How far [zone]'s clock sat from the reader's own at [epochMs] — `+8h`, `-5h30`, hours written
 * with the language's own symbol ([ReaderClock.shiftHourSymbol]) — or null when they agree and
 * there is nothing to say.
 *
 * **Both zones are read at that instant, not today.** A trip last July is compared against what the
 * reader's own clock said last July, so summer time on either side is already in the answer and a
 * past row does not shift when either place next changes its clocks.
 *
 * A difference, deliberately, and not the UTC offset: `+8h` answers "how much later than me was it
 * there", which is the question someone reading their own history has. `+09:00` answers a question
 * about UTC that nobody asked, and leaves the arithmetic to the reader.
 */
internal fun zoneShiftLabel(epochMs: Long, zone: ZoneId, reader: ZoneId, hourSymbol: String): String? {
    // The common case by far — a history mostly spent where its reader is — and the offsets below
    // reach the same conclusion the long way, once per row per recomposition.
    if (zone == reader) return null
    val at = Instant.ofEpochMilli(epochMs)
    val minutes = (zone.rules.getOffset(at).totalSeconds - reader.rules.getOffset(at).totalSeconds) / 60
    if (minutes == 0) return null
    val sign = if (minutes > 0) "+" else "−"
    val hours = abs(minutes) / 60
    val rest = abs(minutes) % 60
    return if (rest == 0) "$sign$hours$hourSymbol" else "$sign$hours$hourSymbol$rest"
}

/** [zoneShiftLabel] for a composable caller, with the symbol fetched from the reader's own clock
 *  so it never travels by hand — a hardcoded one would compile and read wrong in one language. */
@Composable
@ReadOnlyComposable
internal fun zoneShiftLabel(epochMs: Long, zone: ZoneId, reader: ZoneId): String? =
    zoneShiftLabel(epochMs, zone, reader, LocalReaderClock.current.shiftHourSymbol)

/** The muted treatment a zone shift wears wherever it appears — quieter than the time it trails,
 *  because it answers a question the reader only sometimes has. */
internal val zoneShiftColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

/**
 * A resource whose placeholders take **styled** text — a clock time carrying its zone-shift marker,
 * which is drawn rather than interpolated and so cannot be handed to [stringResource].
 *
 * This is what lets a line built around a drawn value stay one whole sentence. Written as a prefix
 * and a suffix instead, the value's position is frozen at the seam between them, and every language
 * inherits the word order of the one the fragments were written in; a translation that opens with the
 * time, or drops the preposition entirely, has nowhere to say so. Here it moves `%1$s` and is done.
 *
 * Arguments bind by position, so a translation may reorder or repeat them. An [AnnotatedString] keeps
 * its spans; anything else is appended as plain text.
 */
@Composable
@ReadOnlyComposable
internal fun annotatedStringResource(
    @StringRes id: Int,
    vararg args: CharSequence,
): AnnotatedString {
    // Formatted twice: once by the platform, which puts each mark where *this language* wants it,
    // then once by [spliceMarks], which swaps the marks for text no format string could have carried.
    val marks = Array<Any>(args.size) { "$MARK_EDGE$it$MARK_EDGE" }
    return spliceMarks(stringResource(id, *marks), args)
}

/**
 * [annotatedStringResource]'s half with no resource table behind it, so a plain-JVM test can drive
 * it — and it is the half worth pinning: the marks are numbered precisely because a translation may
 * reorder them, and an index read back wrongly puts the right words in the wrong places.
 */
internal fun spliceMarks(template: String, args: Array<out CharSequence>): AnnotatedString =
    buildAnnotatedString {
        var from = 0
        SLOT_MARK.findAll(template).forEach { mark ->
            append(template.substring(from, mark.range.first))
            when (val arg = args[mark.groupValues[1].toInt()]) {
                is AnnotatedString -> append(arg)
                else -> append(arg.toString())
            }
            from = mark.range.last + 1
        }
        append(template.substring(from))
    }

/** What delimits a numbered mark: the one character no resource can contain. */
private const val MARK_EDGE = Char.MIN_VALUE

private val SLOT_MARK = Regex("$MARK_EDGE(\\d+)$MARK_EDGE")

/**
 * A clock time in [zone] with its offset from [reader] raised against it — **the one way a time
 * reaches a screen**, and therefore the whole rule: a thing that is a time is marked, and a thing
 * that is not is not.
 *
 * A duration takes no marker under it, which is the point. An hour is an hour in every zone, so an
 * offset trailing "2h 11m" qualifies something that has no clock behind it and reads as nonsense;
 * marking one of two times instead only says the *other* end was somewhere else. The cost is a row
 * abroad repeating the same offset twice, which is the honest shape of a row whose two ends really
 * do both sit on that clock.
 *
 * Raised and shrunk rather than set level with the text: the offset annotates the time it follows
 * the way a footnote marker attaches to a word. Level with it, it reads as one more figure among the
 * duration and the visit count, and where two times each carry one the eye cannot tell which belongs
 * to which. Raised, it attaches, and needs no separator to do it.
 *
 * **[color] is passed rather than read**, so that nothing `@Composable` is called inside
 * [buildAnnotatedString]. Kotlin allows it — the builder lambda is inline, so it inherits the
 * composable context — but the composition groups it generates do not line up with the ones the
 * enclosing scope expects, and the symptom is a span absent on first paint that appears once the row
 * is rebuilt. Resolve [zoneShiftColor] in the composable body and hand it in.
 */
internal fun AnnotatedString.Builder.appendTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
) = appendMarked(
    readerClock.time(epochMs, zone),
    zoneShiftLabel(epochMs, zone, reader, readerClock.shiftHourSymbol),
    color,
)

/** [appendTime]'s longer form, for a screen naming one moment — see [ReaderClock.dateTime]. */
internal fun AnnotatedString.Builder.appendDateTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
) = appendMarked(
    readerClock.dateTime(epochMs, zone),
    zoneShiftLabel(epochMs, zone, reader, readerClock.shiftHourSymbol),
    color,
)

/**
 * [appendTime]'s value form, for a line assembled by [annotatedStringResource] rather than by a
 * builder — the marked time as a thing that can be handed to a sentence, instead of a thing appended
 * at a position the code chose.
 *
 * Unlike [appendTime] this one owns its builder, so it *could* read [LocalReaderClock] itself. It
 * stays non-composable so that the three of them keep one calling convention — and so a caller
 * assembling a line inside `remember` can still reach it.
 */
internal fun markedTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
): AnnotatedString =
    buildAnnotatedString { appendTime(epochMs, zone, reader, color, readerClock) }

private fun AnnotatedString.Builder.appendMarked(text: String, shift: String?, color: Color) {
    append(text)
    if (shift == null) return
    withStyle(SpanStyle(color = color, baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)) {
        append(shift)
    }
}
