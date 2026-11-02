package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R

/**
 * A page whose map fills the screen under a sheet about [subject]: lowered, the sheet shows [peek],
 * measured so the map frames its content above it; raised, [raised] follows. A null [subject] leaves
 * the sheet its handle. A change of [lowerKey] after the first composition lowers the sheet to its
 * peek, and [map] is handed the peek's height as last measured with the sheet settled at it and the
 * count of lowerings that have settled, as keys for its frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Any> MapSheetPage(
    topBar: @Composable () -> Unit,
    subject: T?,
    lowerKey: Any? = null,
    peek: @Composable ColumnScope.(T) -> Unit,
    raised: @Composable ColumnScope.(T) -> Unit,
    map: @Composable BoxScope.(peekKey: Int, lowered: Int, obscured: () -> MapInsets) -> Unit,
) {
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
    val scaffoldState = rememberBottomSheetScaffoldState(
        rememberBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
        ),
    )
    val sheetState = scaffoldState.bottomSheetState
    var lowered by remember { mutableIntStateOf(0) }
    var seenLowerKey by remember { mutableStateOf(lowerKey) }
    LaunchedEffect(lowerKey) {
        if (lowerKey != seenLowerKey) {
            seenLowerKey = lowerKey
            sheetState.partialExpand()
            lowered++
        }
    }
    var settledPeekPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(sheetState) {
        snapshotFlow {
            peekPx.takeIf {
                sheetState.currentValue == SheetValue.PartiallyExpanded && sheetState.targetValue == SheetValue.PartiallyExpanded
            }
        }.collect { px -> if (px != null) settledPeekPx = px }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        containerColor = MaterialTheme.colorScheme.background,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = {
            SlimDragHandle(
                Modifier
                    .onSizeChanged { handlePx = it.height }
                    .onGloballyPositioned { cover.sheetTop = it.boundsInWindow().top },
            )
        },
        sheetContent = {
            val shown = subject ?: return@BottomSheetScaffold
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Column(Modifier.onSizeChanged { peekPx = it.height }) { peek(shown) }
                raised(shown)
            }
        },
        topBar = topBar,
    ) { inner ->
        // A map resized once the peek is measured would stretch its last frame.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    cover.mapTop = bounds.top
                    cover.mapBottom = bounds.bottom
                },
        ) { map(settledPeekPx, lowered, cover::insets) }
    }
}

/**
 * The sheet drags from anywhere on it, and wraps its handle in the tap and the expand and collapse
 * actions itself.
 */
@Composable
private fun SlimDragHandle(modifier: Modifier) {
    val description = stringResource(R.string.common_drag_handle)
    Box(
        modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
    }
}
