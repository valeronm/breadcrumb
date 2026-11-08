package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.NearbyPlaces
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.isNamed
import io.github.valeronm.breadcrumb.domain.pin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.launch

@Composable
internal fun StayDetailScreen(
    afterTrackId: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onShowOnTimeline: (StayDeriver.Stay) -> Unit,
    onOpenPlace: (String) -> Unit,
    onStayAt: (StayClaim, Place) -> Unit,
    onNewPlace: (StayClaim, middle: Coordinate) -> Unit,
    onNameSpot: (PlaceResolver.PlaceSummary) -> Unit,
    onMerge: (TrackMerge.Plan) -> Unit,
    onHide: (HiddenStay) -> Unit,
    onHideAll: (List<HiddenStay>) -> Unit,
) {
    val flow = remember(afterTrackId) { viewModel.stayDetail(afterTrackId) }
    val state by flow.collectAsStateWithLifecycle(initialValue = null)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(state == StayState.Gone) {
        if (state == StayState.Gone) back()
    }
    val d = state as? StayDetail
    var changingPlace by remember { mutableStateOf(false) }
    var confirmingHideAll by remember { mutableStateOf(false) }
    var findingSpot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val onAction = { action: StayAction ->
        when (action) {
            is StayAction.Merge -> onMerge(action.plan)
            StayAction.ChangePlace -> changingPlace = true
            is StayAction.Hide -> onHide(action.stay)
            is StayAction.HideAll -> confirmingHideAll = true
        }
    }

    MapSheetPage(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.stay_title)) },
                subtitle = {
                    if (d != null) {
                        val r = d.resolved
                        Text(spanText(r.stay.start, r.stay.end, r.clocks), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (d != null) ShowOnTimelineAction { onShowOnTimeline(d.resolved.stay) }
                },
            )
        },
        subject = d,
        peek = { detail ->
            val place = detail.resolved.place
            val duration = reportableDurationText(detail.resolved.stay)
            // An unnamed spot is no place yet to be measured from, so its visits stand in.
            val where = detail.unnamedSpot?.let { visitCountLabel(it.visitCount) }
                ?: detail.placeDistanceM?.let { stringResource(R.string.stay_place_distance, fineDistanceText(it)) }
            val subtitle = listOfNotNull(duration, where).joinToString(" · ").takeIf { it.isNotEmpty() }?.let(::AnnotatedString)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StayRowCard(
                    place,
                    groupedRowShape(0, 1),
                    mergeable = false,
                    highlighted = false,
                    onClick = place?.let { { onOpenPlace(it.key) } },
                    subtitle = subtitle,
                    trailing = place?.let {
                        {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                detail.unnamedSpot?.let { spot ->
                    Button(
                        onClick = {
                            if (!findingSpot) {
                                findingSpot = true
                                scope.launch {
                                    try {
                                        viewModel.placeSummary(spot)?.let(onNameSpot)
                                    } finally {
                                        findingSpot = false
                                    }
                                }
                            }
                        },
                        enabled = !findingSpot,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { BusyLabel(stringResource(namingActionRes(spot.place)), findingSpot) }
                }
            }
            // In the peek, so a lowered sheet still shows that more options follow.
            if (detail.actionsAsButtons) SheetHeading(R.string.stay_other_options)
        },
        raised = { detail ->
            val actions = detail.actions
            if (detail.actionsAsButtons) {
                actions.forEach { StayActionButton(it, onAction) }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    GroupedRows(
                        *actions.map<StayAction, @Composable GroupedRowScope.() -> Unit> { action ->
                            { ActionRow(action.label(), action.icon()) { onAction(action) } }
                        }.toTypedArray(),
                    )
                }
            }
        },
        map = { peekKey, _, obscured ->
            if (d != null) StayMap(d, viewModel, peekKey != 0, obscured)
        },
    )

    if (d != null && changingPlace) {
        ChangePlaceSheet(
            detail = d,
            onPick = { place ->
                changingPlace = false
                onStayAt(d.claim, place)
            },
            onNewPlace = { middle ->
                changingPlace = false
                onNewPlace(d.claim, middle)
            },
            onDismiss = { changingPlace = false },
        )
    }

    val hideAll = d?.actions?.firstNotNullOfOrNull { (it as? StayAction.HideAll)?.stays }.orEmpty()
    if (confirmingHideAll && hideAll.isNotEmpty()) {
        ConfirmDialog(
            icon = Icons.Filled.VisibilityOff,
            title = pluralStringResource(R.plurals.stay_hide_all_confirm_title, hideAll.size, hideAll.size),
            text = stringResource(R.string.stay_hide_all_confirm_body),
            confirmLabel = stringResource(R.string.stay_hide_confirm),
            onConfirm = {
                confirmingHideAll = false
                onHideAll(hideAll)
            },
            onDismiss = { confirmingHideAll = false },
        )
    }
}

private val StayDetail.actionsAsButtons: Boolean get() = unnamedSpot != null

@Composable
private fun StayActionButton(action: StayAction, onAction: (StayAction) -> Unit) {
    OutlinedButton(
        onClick = { onAction(action) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(action.label()) }
}

@Composable
private fun StayAction.label(): String = when (this) {
    is StayAction.Merge -> stringResource(R.string.gap_fix_merge)
    StayAction.ChangePlace -> stringResource(R.string.stay_change_place)
    is StayAction.Hide -> stringResource(R.string.timeline_hide_stay)
    is StayAction.HideAll -> pluralStringResource(R.plurals.stay_hide_all, stays.size, stays.size)
}

private fun StayAction.icon(): ImageVector = when (this) {
    is StayAction.Merge -> Icons.AutoMirrored.Filled.CallMerge
    StayAction.ChangePlace -> Icons.Filled.EditLocationAlt
    is StayAction.Hide, is StayAction.HideAll -> Icons.Filled.VisibilityOff
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePlaceSheet(
    detail: StayDetail,
    onPick: (Place) -> Unit,
    onNewPlace: (middle: Coordinate) -> Unit,
    onDismiss: () -> Unit,
) {
    val middle = detail.resolved.middle
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (detail.nearby.isNotEmpty()) {
                SectionHeading(stringResource(R.string.stay_nearby_places))
                NearbyPlaceRows(detail.nearby, onPick)
            }
            Button(
                onClick = { middle?.let(onNewPlace) },
                enabled = middle != null,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.places_new_here))
            }
        }
    }
}

@Composable
private fun StayMap(detail: StayDetail, viewModel: TrackListViewModel, frameKey: Any, obscured: () -> MapInsets) {
    val resolved = detail.resolved
    val touching by rememberTouchingTracks(viewModel, resolved.stay.afterTrackId, resolved.afterId)
    val field = remember(detail.nearby, resolved.place) { stayMapPlaces(detail) }
    val summaries by viewModel.places.collectAsStateWithLifecycle()
    val placeKey = resolved.place?.key
    // An unnamed spot starts with its endpoints shown, since the page offers to name or hide it.
    var showEndpoints by remember(resolved.stay.afterTrackId) { mutableStateOf(detail.unnamedSpot != null) }
    val placeId = resolved.place?.place?.id
    // Waited for, so a stated end is never drawn as a dot and then redrawn as a ring.
    val stated by produceState<PlaceClusterer.Stated?>(null, showEndpoints, placeId) {
        value = when {
            !showEndpoints -> null
            placeId == null -> PlaceClusterer.Stated.None
            else -> viewModel.statedEnds(placeId, elsewhere = emptyList())
        }
    }
    val (statedEndpoints, endpoints) = remember(stated, placeKey, summaries) {
        stated?.partitionHere(summaries?.firstOrNull { it.key == placeKey }?.endpoints.orEmpty())
            ?: (emptyList<Coordinate>() to emptyList())
    }
    MapLibreStayMap(
        arrival = resolved.arrival,
        departure = resolved.departure,
        places = field.places,
        highlightKey = field.highlightKey,
        touching = touching,
        frameKey = frameKey,
        obscured = obscured,
        modifier = Modifier.fillMaxSize(),
        endpoints = endpoints,
        statedEndpoints = statedEndpoints,
        topStart = placeKey?.let {
            {
                MapFilterChip(selected = showEndpoints, label = stringResource(R.string.stay_filter_endpoints)) {
                    showEndpoints = !showEndpoints
                }
            }
        },
    )
}

internal class StayMapPlaces(val places: List<OverviewPlace>, val highlightKey: String?)

internal fun stayMapPlaces(detail: StayDetail): StayMapPlaces {
    val rows = detail.nearby.map { (place) ->
        OverviewPlace(
            marker = PlaceMarker(place.pin, place),
            key = PlaceResolver.keyOf(place.id),
            visits = 0,
            dot = UnnamedDot.of(place, brief = false),
            radiusM = place.radiusM,
        )
    }
    val organic = detail.resolved.place?.takeIf { it.place == null }?.let { cluster ->
        OverviewPlace(
            marker = PlaceMarker(cluster.pin),
            key = cluster.key,
            visits = 0,
            radiusM = cluster.radiusM,
        )
    }
    val current = detail.nearby.firstOrNull { it.current }?.let { PlaceResolver.keyOf(it.place.id) }
    return StayMapPlaces(rows + listOfNotNull(organic), highlightKey = organic?.key ?: current)
}

@Composable
internal fun NearbyPlaceRows(nearby: List<NearbyPlaces.Nearby>, onPick: (Place) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        nearby.forEachIndexed { i, (place, distanceM, current) ->
            val category = place.placeCategory
            ListRowCard(
                shape = groupedRowShape(i, nearby.size),
                icon = category.discIcon,
                disc = placeDiscStyle(category),
                iconDescription = category?.let { stringResource(it.labelRes) },
                title = place.label ?: stringResource(R.string.timeline_unnamed_place),
                titleColor = statedTitleColor(place.isNamed),
                subtitle = AnnotatedString(fineDistanceText(distanceM)),
                marks = listOfNotNull(place.mark),
                onClick = { onPick(place) }.takeUnless { current },
                trailing = if (current) {
                    {
                        Text(
                            stringResource(R.string.stay_current_place),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
