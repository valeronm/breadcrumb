package io.github.valeronm.breadcrumb.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chips occupy an invisible touch target (48dp minimum) around their 32dp visual height; an inset
 * that should read from a chip's *visible* edge subtracts this overshoot.
 */
private val chipHalo: Dp
    @Composable get() = ((LocalMinimumInteractiveComponentSize.current - FilterChipDefaults.Height) / 2)
        .coerceAtLeast(0.dp)

/** Single-choice/filter chip: checkmark when selected. */
@Composable
internal fun FilterToggleChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = selectedCheck(selected),
    )
}

/**
 * A filter over what a map draws, in the map's top-left corner — the whole thing, corner and inset
 * included, because *where* it sits is as much the idiom as how it looks. The Places map's rare
 * stops and the track map's noisy points are the same control over different maps.
 *
 * Elevated on an opaque surface: the default chip tones all but vanish against a basemap. The inset
 * subtracts [chipHalo] so the visible gap is the 12dp it looks like rather than 12dp from the
 * invisible touch target.
 */
@Composable
internal fun BoxScope.MapFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp - chipHalo)) {
        ElevatedFilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = selectedCheck(selected),
            colors = FilterChipDefaults.elevatedFilterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 3.dp),
        )
    }
}

private fun selectedCheck(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
    } else {
        null
    }

/**
 * A list's search box, shaped like the compact header it sits in, not like a form field: a Material
 * text field's 56dp minimum and heavy outline read as borrowed from another screen — hence
 * [BasicTextField] in a pill of the app's own making, height and shape this composable's to set.
 * Filtering is live; with nothing to submit, the keyboard's Done dismisses itself.
 */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Present only with something to clear — an always-there X on an empty field invites a
            // tap that does nothing. Sized down like the day header's share action, for the same
            // reason: a full 48dp target would outweigh the 40dp control holding it.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.places_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** A list section's heading, in the primary tint the section titles wear across the stat screens. */
@Composable
internal fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The bar for work that has a unit and a count of it: how far along that work is, or that it is
 * under way while the count is still unknown. A screen waiting on its own contents has neither,
 * and stays circular.
 *
 * Both states are one function because a total is something work arrives at differently — counted
 * before starting, read off a file's header partway in, or never established. What the reader is
 * told follows from which of those holds at this moment, and not from which part of the app is
 * doing the work.
 */
@Composable
internal fun OperationProgressBar(done: Int, total: Int?, modifier: Modifier = Modifier) {
    // A total of nothing is unknown for this purpose too — there is no fraction of nothing.
    if (total == null || total <= 0) {
        LinearWavyProgressIndicator(modifier)
    } else {
        LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = modifier)
    }
}

/** The screens' shared top-bar back arrow. */
@Composable
internal fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
        )
    }
}

/**
 * The shared top-bar commit for the editors that hold their changes — the trip form and the place
 * editor. A word rather than a tick, because the control that ends an editor is the one saying what
 * becomes of what was typed, and a glyph leaves that to be guessed at. [enabled] is the editor's own
 * test of whether it has enough to write.
 */
@Composable
internal fun SaveAction(enabled: Boolean, onSave: () -> Unit) {
    TextButton(onClick = onSave, enabled = enabled) {
        Text(stringResource(R.string.common_save))
    }
}

/**
 * Long enough that a derivation which beats it shows nothing at all — an indicator that appears and
 * vanishes within a few frames reads as a glitch, and most cold starts are over before this elapses.
 */
private const val DERIVING_SPINNER_DELAY_MS = 300L

/**
 * Placeholder for a screen whose contents are still being derived — **not** interchangeable with
 * [EmptyState]. The stay derivation walks the whole history, so until it lands a full history is
 * indistinguishable from an empty one, and the messages [EmptyState] carries here ("no places yet",
 * and on the Timeline an offer to restore a backup) are then wrong in the alarming direction.
 */
@Composable
internal fun DerivingState(modifier: Modifier = Modifier) {
    val visible by produceState(false) {
        delay(DERIVING_SPINNER_DELAY_MS)
        value = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator()
    }
}

/**
 * Centered placeholder for a list with nothing to show, plus optional content below the message.
 *
 * **[message] reads "No <things> yet. They appear here <when>."** — the fact, then the thing that
 * fills it. A screen that only states the fact leaves the reader to guess whether they are waiting
 * on the app or the app is waiting on them, and every one of these lists fills itself as a
 * consequence of something: recording capturing movement, a place being tagged Home, a track ending
 * inside a capture area.
 *
 * Two kinds of message deliberately do *not* take that shape, and both are on the Places tab: a list
 * emptied by a **filter or a search** says which filter emptied it, because the history behind it is
 * not empty at all and "no places yet" would be a lie. Say what the reader can act on.
 */
@Composable
internal fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

/**
 * Top bars sit on the scaffold canvas instead of the default lighter surface — visible since
 * the light theme dips the canvas below the cards; identical tones in dark.
 *
 * The scrolled colour is the same one. M3 tints a bar that has content under it, which would make a
 * collapsing bar a different colour from a fixed one on the screen beside it — and on the one screen
 * carrying both, a place whose name fits would sit on the canvas while a longer-named neighbour sat
 * on the tint. The canvas is what these bars are, in every state.
 */
@Composable
internal fun canvasTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/**
 * The tab row over a pager, on the scaffold canvas: the default surface reads as a pale stripe
 * between the top bar and the content on the light theme's dipped canvas ([canvasTopBarColors] is
 * the same rule one bar up). A tab row promises a swipe, which is why this takes the pager it
 * indicates rather than a bare index — an indicator that slides while a drag does nothing is the
 * promise broken.
 */
@Composable
internal fun PagerTabRow(pager: PagerState, labels: List<String>) {
    val scope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = pager.currentPage,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = pager.currentPage == index,
                onClick = { scope.launch { pager.animateScrollToPage(index) } },
                text = { Text(label) },
            )
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A hairline between two figures in a row of them — the height of the cells beside it, not of the
 * row's padding, so they read as separated without the card gaining a grid that looks like it
 * continues past its own edge.
 *
 * The row it sits in must be measured at [IntrinsicSize.Min] for `fillMaxHeight` to mean the
 * tallest cell rather than the whole row: one rule sized to each row's own type scale, instead of a
 * constant tuned against one of them and overhanging the other.
 */
@Composable
internal fun StatSeparator() {
    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The detail screens' stats header: equal-width label→value cells in one padded row. */
@Composable
internal fun StatHeaderRow(vararg stats: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) StatSeparator()
            HeaderStat(label, value, Modifier.weight(1f))
        }
    }
}

/**
 * Haptic CLOCK_TICK when a scrubbed value crosses keys, throttled (30 ms) so a fast drag feels like
 * a picker, not a buzz; a plain holder because gesture lambdas capture a composition and go stale.
 * [tickOnFirst]: does the first non-null key after construction (or a [reset]) tick.
 */
internal class ThrottledTick(private val view: View, private val tickOnFirst: Boolean) {
    private var last: Any? = null
    private var lastTickAt = 0L

    fun onChange(key: Any?) {
        val changedKey = key != null && key != last
        val firstKeyTicks = last != null || tickOnFirst
        if (changedKey && firstKeyTicks) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTickAt >= 30) {
                lastTickAt = now
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        last = key
    }

    fun reset() {
        last = null
    }
}

/**
 * Consumes taps without click semantics or ripple — for an opaque sticky header floating over
 * tappable rows. Hit testing ignores a background, so without this a tap on the header falls
 * through to whatever row has scrolled beneath it. Scrolls still work from the header: the
 * detector releases the gesture the moment the finger moves.
 */
internal fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures {} }

/**
 * The switch over a tab whose views are the same content drawn two ways — a connected button group
 * rather than a tab row, because a tab promises different content over there, and a pager's swipe
 * fights a map for every horizontal drag. The selection is the caller's state; nothing here
 * scrolls or settles, so a map view owns every gesture that reaches it. Tabs remain the right
 * control where the pages genuinely differ, which is [PagerTabRow]'s job.
 *
 * Connected rather than the segmented row it reads as, that being the control Material retired in
 * favour of this one, and connected rather than a *standard* button group because the join is the
 * whole statement: spaced buttons say several things can be pressed, a shared outline says one of
 * these is chosen. The shapes carry it — each button is told whether it leads, follows or sits
 * between — so the row must stay in position order, and wants two labels at least: a lone one is
 * drawn as a leading button, there being no standalone shape in the connected set to give it.
 *
 * Re-selecting the chosen button never calls back, so callers switch unguarded. Labels arrive as
 * string resources and resolve here, so a caller can hand over one static list and the row skips
 * with it.
 */
@Composable
internal fun ViewSwitchRow(labelsRes: List<Int>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    // An unchecked button's own default is the role this app's light scheme puts on `background`,
    // which leaves it the exact colour of the page it sits on — no container, no join, just a word.
    // Stated as the role the cards take instead, so it reads as raised in both schemes rather than
    // only in the dark one.
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labelsRes.forEachIndexed { index, labelRes ->
            ToggleButton(
                checked = index == selectedIndex,
                // Toggling reports the value asked for, so the chosen button reports false and
                // drops out: the "never calls back on a re-select" rule, stated once.
                onCheckedChange = { if (it) onSelect(index) },
                modifier = Modifier.weight(1f),
                colors = colors,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labelsRes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(stringResource(labelRes)) }
        }
    }
}
