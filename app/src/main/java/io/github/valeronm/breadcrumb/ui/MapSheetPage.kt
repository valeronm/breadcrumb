package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * A page whose map fills the screen under a sheet about [subject]: lowered, the sheet shows [peek],
 * measured so the map frames its content above it; raised, [raised] follows. A null [subject] leaves
 * the sheet its handle. [map] is handed a key that changes once the peek is measured, for its frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Any> MapSheetPage(
    topBar: @Composable () -> Unit,
    subject: T?,
    peek: @Composable ColumnScope.(T) -> Unit,
    raised: @Composable ColumnScope.(T) -> Unit,
    map: @Composable BoxScope.(peekKey: Int, obscured: () -> MapInsets) -> Unit,
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
    // The first fit can land before the peek is measured.
    var peekKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(peekPx != 0) { if (peekPx != 0) peekKey++ }

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
        ) { map(peekKey, cover::insets) }
    }
}
