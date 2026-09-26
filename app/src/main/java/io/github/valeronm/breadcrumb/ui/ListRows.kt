package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.domain.Coordinate
import kotlinx.coroutines.delay

/**
 * Corner shape for a row in a day group: large outer corners on the group's first/last edge,
 * small inner corners between neighbors — the rows read as one grouped block.
 */
internal fun groupedRowShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 12.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * The lists' shared row skeleton — category disc, title and subtitle on a card — shared by Timeline track
 * and stay rows and the Places list so padding, disc placement and type scale can't drift. [onClick] is a
 * plain tap; richer gestures (long press) go in [modifier] with [onClick] null. Title color is explicit:
 * dynamic color dims the inherited card color to onSurfaceVariant (contentColorFor matches surfaceVariant first).
 */
@Composable
internal fun ListRowCard(
    shape: RoundedCornerShape,
    icon: ImageVector,
    disc: DiscStyle,
    title: String,
    titleColor: Color,
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
    /** A second fact about the row, marked on the disc's corner instead of replacing its glyph. */
    badge: ImageVector? = null,
    badgeDescription: String? = null,
    /** What the badge *means* is the caller's, so its color is too — the default is only a default. */
    badgeColor: Color = MaterialTheme.colorScheme.tertiary,
    badgeContentColor: Color = MaterialTheme.colorScheme.onTertiary,
    colors: CardColors? = null,
    onClick: (() -> Unit)? = null,
) {
    val cardColors = colors ?: CardDefaults.cardColors()
    val rowContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconDisc(
                icon,
                disc,
                contentDescription = iconDescription,
                badge = badge,
                badgeDescription = badgeDescription,
                badgeColor = badgeColor,
                badgeContentColor = badgeContentColor,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = cardColors,
            content = rowContent,
        )
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = cardColors, content = rowContent)
    }
}

/**
 * How an icon disc is painted: the circle's fill (with [fillAlpha]) and its glyph's ink. The named
 * recipes — [DiscStyle.tonal]'s wash, [placeDiscStyle]'s solid pin fill — are the vocabulary; a
 * surface picks one rather than re-deciding weights.
 */
internal data class DiscStyle(val fill: Color, val fillAlpha: Float, val glyph: Color) {
    companion object {
        /** A soft wash of [tint] under a glyph in the same color (M3 "tonal") — one home for the
         *  weight, so retuning the wash can't miss a surface. */
        fun tonal(tint: Color) = DiscStyle(fill = tint, fillAlpha = 0.22f, glyph = tint)
    }
}

/**
 * The list rows' category token: a glyph on a circle, painted per [DiscStyle].
 * [badge] marks a *second*, unrelated fact about the row without spending the glyph on it: it rides
 * the bottom-end corner the circle leaves empty inside its own square (so a badged disc takes no
 * more room), saturated rather than tonal — at this size a soft fill reads as a smudge on the edge.
 */
@Composable
internal fun IconDisc(
    icon: ImageVector,
    style: DiscStyle,
    contentDescription: String?,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    badge: ImageVector? = null,
    badgeDescription: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.tertiary,
    badgeContentColor: Color = MaterialTheme.colorScheme.onTertiary,
) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(style.fill.copy(alpha = style.fillAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = style.glyph,
                modifier = Modifier.size(iconSize),
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * BADGE_FRACTION)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = badge,
                    contentDescription = badgeDescription,
                    tint = badgeContentColor,
                    modifier = Modifier.size(size * BADGE_FRACTION * 0.68f),
                )
            }
        }
    }
}

/** Badge diameter as a share of the disc's: big enough to read, small enough to stay a badge. */
private const val BADGE_FRACTION = 0.42f

/**
 * Row with a swipe-left action revealed behind it. The swipe *completes*: [onDismiss] performs the
 * action immediately and the caller offers an Undo snackbar. The row stays swiped away until the
 * action drops it from the list (an undo brings it back as a fresh, un-swiped row).
 */
@Composable
internal fun SwipeActionRow(
    shape: RoundedCornerShape,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    iconDescription: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Plain remember, NOT rememberSwipeToDismissBoxState: that saves the dismissed state under the
    // lazy item's key, and an undone row returns under the same key — it would come back already
    // dismissed and re-fire onDismiss, deleting itself again on the spot.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDismiss() },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(containerColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(icon, contentDescription = iconDescription, tint = contentColor)
            }
        },
    ) { content() }
}

@Composable
internal fun SearchResultRow(
    icon: ImageVector,
    label: String,
    detail: String?,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            // A floor rather than more padding: a row carrying a locality line under its name is
            // tall enough already, and one without it would otherwise stand at two thirds of a
            // finger — in a stacked, scrolling list, which is where a mis-hit picks the wrong result.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        // Stacked, not side by side: a hotel's name and a spelled-out locality routinely overrun
        // one line between them, and two texts sharing a row collide instead of wrapping.
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A search [field] with its results in a menu anchored under it, shown while [open]. The menu is a
 * popup and takes no space in the layout around the field. A tap outside closes it until [query]
 * changes.
 */
@Composable
internal fun SearchDropdown(
    query: String,
    open: Boolean,
    field: @Composable (anchor: Modifier) -> Unit,
    results: @Composable ColumnScope.() -> Unit,
) {
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val expanded = open && query != dismissedFor
    // A tap on the field is typing, not a toggle.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {}) {
        field(Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable))
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { dismissedFor = query },
            modifier = Modifier.heightIn(max = 260.dp),
            // A step above the surfaces a field sits on, which the menu would otherwise match.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = results,
        )
    }
}

/** ODbL's credit, owed at every list holding [OnlinePlaceSearch] results. */
@Composable
internal fun OsmCredit() {
    Text(
        stringResource(R.string.common_osm_credit),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * [OnlinePlaceSearch] results for [query], biased toward [near] as it stands when the request goes
 * out, and bounded to [withinM] of it when given.
 */
@Composable
internal fun rememberOnlineHits(
    query: String,
    near: Coordinate?,
    viewModel: TrackListViewModel,
    withinM: Double? = null,
): State<List<OnlinePlaceSearch.Hit>> {
    val latestNear by rememberUpdatedState(near)
    return produceState(emptyList(), query) {
        value = if (query.isBlank()) {
            emptyList()
        } else {
            // A longer settle than a local scan's: this one puts the query on the wire.
            delay(400)
            viewModel.searchOnline(query, latestNear, withinM)
        }
    }
}
