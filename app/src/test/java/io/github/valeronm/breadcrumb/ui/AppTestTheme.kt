package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem

/**
 * [AppTheme] with the locals `MainActivity` provides around every screen, in metric units. They are
 * built from the application context a suite builds its expectations from, since two contexts can
 * resolve a localized case differently.
 */
@Composable
internal fun AppTestTheme(content: @Composable () -> Unit) {
    val context: Context = ApplicationProvider.getApplicationContext()
    CompositionLocalProvider(
        LocalMeasures provides Measures(UnitSystem.METRIC, unitSymbols(context)),
        LocalDurationSymbols provides durationSymbols(context),
        LocalReaderClock provides readerClockOf(context),
    ) {
        AppTheme(content = content)
    }
}
