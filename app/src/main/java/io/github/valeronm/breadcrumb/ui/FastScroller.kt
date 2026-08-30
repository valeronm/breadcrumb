package io.github.valeronm.breadcrumb.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * How many screenfuls a list must run to before a scrubber earns its place. Counted in screenfuls
 * rather than rows because that is the thing the reader feels — a hundred rows are nothing to a
 * flick if they are all short, and thirty tall ones are a journey. Measured, so a list that reaches
 * the bar on a phone need not on a tablet, which is the same answer given honestly.
 */
private const val SCREENFULS_BEFORE_SCRUBBER = 3

/**
 * One position a fast scroller can put a list in: the [itemIndex] it scrolls to and the [band] it
 * belongs to.
 *
 * **A band is the structure the list is showing, so that a click means something the eye can also
 * see.** A grouped list bands by its groups — the timeline's days, a place's months — and a click
 * lands where a heading passes. A list with no groups on screen has only its rows, and there each
 * stop is its own band: the rhythm is then honest about being a rhythm, which a rule invented for
 * the occasion would not be, having nothing on screen to correspond to.
 *
 * The band is also what the bubble is drawn from, which is why it is the group itself rather than a
 * key standing for one — a month, a year, the place at that row — and why it carries its type. Only
 * the stop under a moving thumb is ever read, so a list states what its stops *are* and leaves the
 * wording to be asked for at the one place it is wanted.
 */
internal class ScrollStop<out T>(val band: T, val itemIndex: Int)

/**
 * Stops for a list that draws a heading and then that group's rows, given each group's band and how
 * many rows follow it: one stop per emitted item, so the drag travels at the list's own resolution
 * while the tick still marks a group boundary.
 *
 * **The order is the contract** — this counts what a `LazyListScope` block elsewhere emits, and
 * nothing checks the two agree, so a header, footer or banner added to that block belongs here too
 * or every stop after it points a row short.
 */
internal fun <T> groupedScrollStops(groups: List<Pair<T, Int>>): List<ScrollStop<T>> = buildList {
    var index = 0
    for ((band, rows) in groups) {
        repeat(rows + 1) { add(ScrollStop(band, index++)) }
    }
}

/**
 * A finger-sized handle that fades in while a list scrolls and can be grabbed and dragged through
 * it. The drag lands on [stops], a bubble reads [label] of the one under the thumb, and crossing
 * into another band ticks like the track scrubber.
 *
 * A list with headings can stop on those alone (the timeline's days), which lands the drag on a
 * heading and never inside one; a list without them stops per row and bands them by whatever it is
 * ordered by. So the caller decides both what the scrubber is a scale *of* and how finely it moves,
 * and nothing here knows what a row holds.
 *
 * [label] is asked only for the stop being shown, and only while a drag is in flight — a list of
 * thousands would otherwise format a date or a plural per row, on entering the screen and again
 * whenever the rows change, for text no one is looking at.
 */
@Composable
internal fun <T> BoxScope.FastScroller(
    state: LazyListState,
    stops: List<ScrollStop<T>>,
    contentDescription: String,
    label: @Composable (T) -> String,
) {
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Tick when the drag crosses into another band (never on the one under the initial grab).
    val bandTick = remember { ThrottledTick(view, tickOnFirst = false) }
    // Linger after the scroll stops so there's time to reach for the handle before it fades.
    var shown by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1_500.milliseconds)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 100 else 500),
        label = "fastScrollerAlpha",
    )
    // Whether the list runs far enough to be worth a handle. Latched, because the measurement moves
    // under the reader: rows here are of unequal height by design, so a list near the bar fits fewer
    // of them over a run of tall ones, and a handle appearing and vanishing mid-scroll fails during
    // the very gesture it exists to replace.
    var longEnough by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.size to state.layoutInfo.totalItemsCount }
            .first { (visible, total) -> visible > 0 && total >= visible * SCREENFULS_BEFORE_SCRUBBER }
            .let { longEnough = true }
    }
    // A single stop is a handle that can only ever land where it already is.
    if (alpha == 0f || stops.size < 2 || !longEnough) return

    // Where the thumb sits when the finger isn't driving it: the stop currently at the top, on the
    // same scale the drag uses (so grabbing the handle doesn't jump). Read off
    // `firstVisibleItemIndex` rather than `layoutInfo`, which notifies on every measure pass and
    // would re-run this per frame of a fling; the search is a seek because a stop list ascends.
    val listFraction by remember(state, stops) {
        derivedStateOf {
            val first = state.firstVisibleItemIndex
            val found = stops.binarySearch { it.itemIndex.compareTo(first) }
            val stopIdx = (if (found >= 0) found else -found - 2).coerceAtLeast(0)
            stopIdx.toFloat() / (stops.size - 1)
        }
    }
    val density = LocalDensity.current
    val thumbHeight = 56.dp
    val thumbWidth = 32.dp
    val touchPad = 12.dp
    val thumbPx = with(density) { thumbHeight.toPx() }
    val touchPadPx = with(density) { touchPad.toPx() }
    val bubbleGap = with(density) { (thumbWidth + 12.dp).roundToPx() }
    val bubbleLift = with(density) { 16.dp.roundToPx() }
    // The track, measured rather than subcomposed: `BoxWithConstraints` would put every read below
    // inside a `SubcomposeLayout`, and only the height is wanted.
    var trackPx by remember { mutableFloatStateOf(1f) }

    fun stopAt(f: Float): ScrollStop<T> =
        stops[(f * (stops.size - 1)).roundToInt().coerceIn(stops.indices)]

    // Where the thumb is, read as a function rather than held as a value: called from the offset
    // lambdas it is a layout-phase read, so a drag moves the handle without recomposing it.
    fun fraction(): Float = if (dragging) dragFraction else listFraction

    fun thumbTop(): Float = trackPx * fraction()

    fun applyFraction(f: Float) {
        dragFraction = f.coerceIn(0f, 1f)
        val stop = stopAt(dragFraction)
        bandTick.onChange(stop.band)
        // Requested rather than scrolled to: this runs per pointer event, and a suspending scroll
        // would take the scroll mutex and force a relayout outside the frame for each one. A
        // request folds into the frame's own measure pass.
        state.requestScrollToItem(stop.itemIndex)
    }

    Box(
        Modifier
            .matchParentSize()
            .onSizeChanged { trackPx = (it.height - thumbPx).coerceAtLeast(1f) },
    ) {
        // The handle: a half-circle hugging the edge inside a larger touch box that captures on
        // first touch-down — no slop wait, so grabs aren't eaten by drag detection (which loses
        // slow or slightly diagonal starts). Only the handle area takes input; the rest of the
        // edge scrolls the list as usual.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (thumbTop() - touchPadPx).roundToInt()) }
                .size(width = thumbWidth + touchPad, height = thumbHeight + touchPad * 2)
                .pointerInput(stops.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragFraction = fraction()
                        dragging = true
                        bandTick.reset()
                        // This box moves with the thumb, so map local positions to track space
                        // through the thumb's current offset; anchor the grab point so the
                        // handle doesn't jump under the finger.
                        fun trackY(localY: Float) = localY + trackPx * dragFraction - touchPadPx
                        val grabDelta = (trackPx * dragFraction + thumbPx / 2) - trackY(down.position.y)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                val center = trackY(change.position.y) + grabDelta
                                applyFraction((center - thumbPx / 2) / trackPx)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier.size(width = thumbWidth, height = thumbHeight).alpha(alpha),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = if (dragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = contentDescription,
                        tint = if (dragging) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        // Which stop is being named, derived so the bubble recomposes when the answer changes
        // rather than on every event of the drag that asks it.
        val naming by remember(stops) {
            derivedStateOf { if (dragging) stopAt(dragFraction) else null }
        }
        naming?.let { stop ->
            val shown = label(stop.band)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(-bubbleGap, (thumbTop() + thumbPx / 2).roundToInt() - bubbleLift)
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    shown,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
