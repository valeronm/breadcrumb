package io.github.valeronm.breadcrumb.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MergePreview
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.launch

/**
 * Choosing a merge among [members] — the opened place and the rows overlapping it, fixed when the
 * page opened. [stated] maps each member's id to the ends stated to it, null until loaded, and the
 * map draws no visits until then, since a guess would tint a stated end as a measured one.
 */
@Composable
internal fun MergePlacesScreen(
    opened: Place,
    members: List<PlaceResolver.PlaceSummary>,
    stated: Map<Long, List<Coordinate>>?,
    places: List<Place>,
    onBack: () -> Unit,
    onMerge: (keep: Place, absorbed: List<Place>) -> Unit,
) {
    val rows = members.mapNotNull { s -> s.place?.let { it to s } }
    var keeperId by remember(rows.isNotEmpty()) {
        mutableLongStateOf(
            MergePreview.initialKeeper(opened, rows.map { it.first }.filter { it.id != opened.id }, AndroidDistance).id,
        )
    }
    var absorbed by remember(rows.isNotEmpty()) {
        mutableStateOf(if (keeperId == opened.id) emptySet() else setOf(opened.id))
    }
    // Read off the live rows: a member removed elsewhere while the page is open drops out of
    // `keeper` and `picked`.
    val keeper = rows.firstOrNull { it.first.id == keeperId }?.first
    val picked = rows.map { it.first }.filter { it.id in absorbed && it.id != keeperId }
    val dots = remember(rows, stated, keeperId, absorbed, places) {
        if (stated == null) {
            emptyList()
        } else {
            MergePreview.dots(
                rows.map { (p, s) -> MergePreview.Member(p, s.endpoints, stated[p.id].orEmpty()) },
                keeperId,
                absorbed,
                places,
                AndroidDistance,
            )
        }
    }
    val mapPlaces = remember(rows, keeperId, absorbed) {
        rows.map { (p, _) ->
            val role = when {
                p.id == keeperId -> MergeRole.KEEPER
                p.id in absorbed -> MergeRole.MERGING
                else -> MergeRole.LEFT
            }
            MergeMapPlace(p, p.label, role)
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var highlighted by remember { mutableStateOf<Long?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.places_merge)) },
                navigationIcon = { BackNavIcon(onBack) },
            )
        },
        bottomBar = {
            Button(
                enabled = keeper != null && picked.isNotEmpty(),
                onClick = { keeper?.let { onMerge(it, picked) } },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) { Text(pluralStringResource(R.plurals.places_merge_confirm, picked.size, picked.size)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MapLibreMergeMap(
                places = mapPlaces,
                dots = dots,
                openedId = opened.id,
                onTapPlace = { id ->
                    highlighted = id
                    val index = rows.indexOfFirst { it.first.id == id }
                    // +1 skips the body text heading the list.
                    if (index >= 0) scope.launch { listState.animateScrollToItem(index + 1) }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            MergeLegend(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                item {
                    Text(
                        stringResource(R.string.places_merge_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(rows, key = { it.first.id }) { (place, summary) ->
                    MergeRow(
                        place = place,
                        summary = summary,
                        meters = AndroidDistance.meters(opened.lat, opened.lon, place.lat, place.lon),
                        isOpened = place.id == opened.id,
                        isKeeper = place.id == keeperId,
                        isAbsorbed = place.id in absorbed,
                        highlighted = place.id == highlighted,
                        onKeep = {
                            keeperId = place.id
                            absorbed = absorbed - place.id
                        },
                        onToggle = {
                            absorbed = if (place.id in absorbed) absorbed - place.id else absorbed + place.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MergeRow(
    place: Place,
    summary: PlaceResolver.PlaceSummary,
    meters: Double,
    isOpened: Boolean,
    isKeeper: Boolean,
    isAbsorbed: Boolean,
    highlighted: Boolean,
    onKeep: () -> Unit,
    onToggle: () -> Unit,
) {
    val category = place.placeCategory
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val keep = stringResource(R.string.places_merge_keep)
        RadioButton(
            selected = isKeeper,
            onClick = onKeep,
            modifier = Modifier.semantics { contentDescription = keep },
        )
        Spacer(Modifier.width(8.dp))
        IconDisc(category.discIcon, placeDiscStyle(category), contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                summary.name ?: stringResource(summary.unnamedTitleRes),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isOpened) {
                    pluralStringResource(R.plurals.places_merge_opened_visits, summary.visitCount, summary.visitCount)
                } else {
                    pluralStringResource(R.plurals.place_row_visits, summary.visitCount, summary.visitCount) +
                        " · " + distanceText(meters)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        place.mark?.let {
            Spacer(Modifier.width(8.dp))
            OriginGlyph(it)
        }
        if (!isKeeper) {
            val mergeIn = stringResource(R.string.places_merge_in)
            Checkbox(
                checked = isAbsorbed,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = mergeIn },
            )
        }
    }
}

@Composable
private fun MergeLegend(modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendEntry(R.drawable.ic_marker_endpoint, R.string.places_merge_lands_keeper)
        LegendEntry(R.drawable.ic_marker_neighbor, R.string.places_merge_lands_other)
        LegendEntry(R.drawable.ic_marker_endpoint_brief, R.string.places_merge_lands_none)
    }
}

@Composable
private fun LegendEntry(@DrawableRes icon: Int, @StringRes label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
    }
}
