package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valeronm.breadcrumb.data.Settings

internal object DebugTools {

    var enabled by mutableStateOf(false)
        private set

    fun load(context: Context) {
        enabled = Settings.debugTools(context)
        if (!enabled) TrackReplayer.stop()
    }

    fun setEnabled(context: Context, value: Boolean) {
        Settings.setDebugTools(context, value)
        load(context)
    }
}
