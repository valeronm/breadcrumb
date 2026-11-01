package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.PlaceResolver

/** One span on its own reads in time order, departure above arrival, unlike the newest-first timeline. */
@Composable
internal fun SpanEnds(
    from: PlaceResolver.ResolvedStay?,
    fromAt: AnnotatedString,
    figures: String,
    to: PlaceResolver.ResolvedStay?,
    toAt: AnnotatedString,
    onOpenPlace: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        EndLine(from, fromAt, onOpenPlace)
        if (figures.isNotEmpty()) {
            Text(
                text = figures,
                modifier = Modifier.padding(start = LIST_ROW_TEXT_INDENT, end = 16.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EndLine(to, toAt, onOpenPlace)
    }
}

/**
 * Where no place holds the end, [at] stands alone. [at] trails the name rather than leading it: the
 * place is what the row is for, and the time qualifies it.
 */
@Composable
internal fun EndLine(place: PlaceResolver.ResolvedStay?, at: AnnotatedString?, onOpenPlace: ((String) -> Unit)?) {
    val time = @Composable {
        if (at != null) {
            Text(
                text = at,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    if (place == null) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = LIST_ROW_TEXT_INDENT, end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) { time() }
        return
    }
    val category = place.category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpenPlace != null) Modifier.clickable { onOpenPlace(place.key) } else Modifier)
            // More vertical padding would push the discs away from the connector the timeline's gap
            // card draws between two of these rows.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDisc(
            icon = category.discIcon,
            style = placeDiscStyle(category),
            contentDescription = category?.let { stringResource(it.labelRes) },
        )
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = place.name ?: stringResource(R.string.timeline_unnamed_place),
                style = MaterialTheme.typography.titleMedium,
                color = statedTitleColor(stated = place.isNamed),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (at != null) {
                Spacer(Modifier.width(12.dp))
                time()
            }
        }
        place.place?.mark?.let {
            Spacer(Modifier.width(8.dp))
            OriginGlyph(it)
        }
    }
}
