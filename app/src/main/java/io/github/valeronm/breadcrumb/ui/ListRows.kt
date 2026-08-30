package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
