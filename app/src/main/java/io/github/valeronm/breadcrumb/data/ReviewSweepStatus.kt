package io.github.valeronm.breadcrumb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide progress of the review-mark sweep, observed by the timeline — the same bridge
 * pattern as `TrackingStatus`, and for the same reason: the sweep runs from `App.onCreate`, long
 * before any ViewModel exists, and outlives whatever screen happens to be open.
 *
 * It exists because the sweep is otherwise invisible work that changes what the user sees: it
 * walks the whole point history for half a minute and badges start appearing on rows behind them.
 */
object ReviewSweepStatus {

    data class Progress(val done: Int, val total: Int)

    private val _state = MutableStateFlow<Progress?>(null)

    /** Non-null only while a sweep is running. */
    val state: StateFlow<Progress?> = _state

    fun start(total: Int) {
        _state.value = Progress(0, total)
    }

    fun advance(done: Int) {
        _state.value = _state.value?.copy(done = done)
    }

    fun finish() {
        _state.value = null
    }
}
