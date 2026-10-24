package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.Speed
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GapDetailScreen(
    afterTrackId: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onAddTrip: (TripDraft) -> Unit,
    onStayedHere: (beforeId: Long, afterId: Long, side: PlaceResolver.ResolvedStay) -> Unit,
    onMerge: (TrackMerge.Plan) -> Unit,
    onShowOnTimeline: (StayDeriver.Gap) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val flow = remember(afterTrackId) { viewModel.gapDetail(afterTrackId) }
    val state by flow.collectAsStateWithLifecycle(initialValue = null)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(state == GapState.Gone) {
        if (state == GapState.Gone) back()
    }
    val d = state as? GapDetail
    var asking by remember(afterTrackId) { mutableStateOf<StayedHereChoice.Ask?>(null) }

    fun onFix(chosen: GapDiagnosis.Fix) {
        val detail = d ?: return
        when (val action = gapAction(chosen, detail)) {
            is GapAction.AddTrip -> onAddTrip(action.draft)
            is GapAction.Merge -> onMerge(action.plan)
            is StayedHereChoice.Direct -> onStayedHere(detail.before.id, detail.after.id, action.side)
            is StayedHereChoice.Ask -> asking = action
        }
    }

    val density = LocalDensity.current
    val cover = remember { MapCover() }
    var handlePx by remember { mutableIntStateOf(0) }
    var peekPx by remember { mutableIntStateOf(0) }
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peekHeight = if (peekPx == 0) {
        BottomSheetDefaults.SheetPeekHeight
    } else {
        with(density) { (handlePx + peekPx).toDp() } + navigationBar
    }
    // The first fit can land before the peek is measured, which frames the gap under the sheet.
    var frameKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(peekPx != 0) { if (peekPx != 0) frameKey++ }

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(
            rememberBottomSheetState(
                initialValue = SheetValue.PartiallyExpanded,
                enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
            ),
        ),
        containerColor = MaterialTheme.colorScheme.background,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                Modifier
                    .onSizeChanged { handlePx = it.height }
                    .onGloballyPositioned { cover.sheetTop = it.boundsInWindow().top },
            )
        },
        sheetContent = {
            val detail = d ?: return@BottomSheetScaffold
            val verdict = detail.verdict
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Column(Modifier.onSizeChanged { peekPx = it.height }) {
                    GapFacts(detail, onOpenPlace)
                    GapSuggestion(verdict, ::onFix)
                }
                if (verdict.others.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeading(stringResource(R.string.gap_other_fixes))
                    }
                    verdict.others.forEach { other ->
                        ListItem(modifier = Modifier.clickable { onFix(other) }) {
                            Text(stringResource(other.labelRes()))
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.gap_title)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { onShowOnTimeline(d.gap) }) {
                            Icon(Icons.Filled.Route, contentDescription = stringResource(R.string.gap_show_on_timeline))
                        }
                    }
                },
            )
        },
    ) { inner ->
        val detail = d ?: return@BottomSheetScaffold
        // Full height under the sheet rather than padded above it: the peek is measured, and a map
        // resized once it is would stretch its last frame.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    cover.mapTop = bounds.top
                    cover.mapBottom = bounds.bottom
                },
        ) {
            GapMap(detail, viewModel, frameKey, cover::insets, onReframe = { frameKey++ })
        }
    }

    val choice = asking
    if (d != null && choice != null) {
        StayedHereSheet(
            choice = choice,
            onPick = { side ->
                asking = null
                onStayedHere(d.before.id, d.after.id, side)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun GapMap(
    detail: GapDetail,
    viewModel: TrackListViewModel,
    frameKey: Int,
    obscured: () -> MapInsets,
    onReframe: () -> Unit,
) {
    val beforeId = detail.before.id
    val afterId = detail.after.id
    val touching by produceState(emptyList<TouchingTrack>(), beforeId, afterId) {
        value = viewModel.touchingTracks(beforeId, afterId).toList().filterNotNull()
    }
    val places = remember(detail.fromPlace, detail.toPlace) {
        listOfNotNull(detail.fromPlace, detail.toPlace).distinctBy { it.key }.map { side ->
            OverviewPlace(
                marker = PlaceMarker(side.pin, side.place),
                key = side.key,
                visits = side.visitCount,
                dot = UnnamedDot.of(side.place, brief = false),
                radiusM = side.radiusM,
            )
        }
    }
    MapLibreTripMap(
        origin = detail.gap.from?.at,
        destination = detail.gap.to?.at,
        places = places,
        shape = emptyList(),
        activity = null,
        touching = touching,
        continuing = setOf(ManualPath.End.ORIGIN, ManualPath.End.DESTINATION),
        onLongPress = {},
        onPlaceTap = { _, _ -> },
        frameKey = frameKey,
        obscured = obscured,
        modifier = Modifier.fillMaxSize(),
        controls = { FrameControl(stringResource(R.string.gap_show_both_ends), onReframe) },
    )
}

@Composable
internal fun GapFacts(detail: GapDetail, onOpenPlace: (String) -> Unit) {
    val gap = detail.gap
    val verdict = detail.verdict
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    val line = listOfNotNull(
        StayDeriver.reportable(gap.end - gap.start)?.let { durationText(it) },
        verdict.apartMeters?.let { fineDistanceText(it) },
        verdict.atLeastMps?.let { stringResource(R.string.gap_at_least_speed, speedText(Speed.mps(it).kmh)) },
    ).joinToString(" · ")
    // Departure above arrival: one gap on its own reads in time order, unlike the newest-first timeline.
    Column(Modifier.padding(vertical = 8.dp)) {
        GapEndLine(
            detail.fromPlace,
            markedTime(gap.start, detail.departureZone, reader, shiftColor, readerClock),
            onOpenPlace,
        )
        if (line.isNotEmpty()) {
            Text(
                text = line,
                modifier = Modifier.padding(start = GAP_TEXT_INDENT, end = 16.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GapEndLine(
            detail.toPlace,
            markedTime(gap.end, detail.arrivalZone, reader, shiftColor, readerClock),
            onOpenPlace,
        )
    }
}

@Composable
private fun GapEndLine(place: PlaceResolver.ResolvedStay?, at: AnnotatedString, onOpenPlace: (String) -> Unit) {
    val time = @Composable {
        Text(
            text = at,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (place == null) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = GAP_TEXT_INDENT, end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) { time() }
        return
    }
    val category = place.category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(place.key) }
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
            Spacer(Modifier.width(12.dp))
            time()
        }
        place.place?.mark?.let {
            Spacer(Modifier.width(8.dp))
            OriginGlyph(it)
        }
    }
}

@Composable
internal fun GapSuggestion(verdict: GapDiagnosis.Verdict, onFix: (GapDiagnosis.Fix) -> Unit) {
    Button(
        onClick = { onFix(verdict.suggested) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(verdict.suggested.labelRes()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StayedHereSheet(
    choice: StayedHereChoice.Ask,
    onPick: (PlaceResolver.ResolvedStay) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(choice) { mutableStateOf(choice.preselected) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Text(
                stringResource(R.string.gap_stayed_where),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Departure first, as the facts list them.
            listOf(choice.from, choice.to).forEach { side ->
                OptionRow(
                    icon = side.category.discIcon,
                    label = side.name ?: stringResource(R.string.timeline_unnamed_place),
                    tint = categoryGlyphTint(side.category),
                    labelColor = statedTitleColor(stated = side.isNamed),
                    selected = side.key == picked.key,
                    onClick = { picked = side },
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onPick(picked) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.gap_fix_stayed_here))
            }
        }
    }
}

@StringRes
internal fun GapDiagnosis.Fix.labelRes(): Int = when (this) {
    GapDiagnosis.Fix.MISSED_START -> R.string.gap_fix_missed_start
    GapDiagnosis.Fix.ADD_TRIP -> R.string.gap_fix_add_trip
    GapDiagnosis.Fix.STAYED_HERE -> R.string.gap_fix_stayed_here
    GapDiagnosis.Fix.MERGE -> R.string.gap_fix_merge
}

/** The 16.dp gutter, the 36.dp place disc and the 16.dp after it: where an end's text starts. */
private val GAP_TEXT_INDENT = 68.dp
