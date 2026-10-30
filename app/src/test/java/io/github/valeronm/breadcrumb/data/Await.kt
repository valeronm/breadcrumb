package io.github.valeronm.breadcrumb.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

internal const val SETTLE_MS = 500L
private const val AWAIT_TIMEOUT_MS = 5_000L
private const val POLL_MS = 20L

private const val QUIET_POLLS = 5

/** Throws once [AWAIT_TIMEOUT_MS] passes with [condition] still false. */
internal suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(AWAIT_TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }
}

internal suspend fun <T> Flow<T>.awaitFirst(until: (T) -> Boolean = { true }): T =
    withTimeout(AWAIT_TIMEOUT_MS) { first(until) }

/** The list fills from IO, concurrently with the caller's reads, until the receiver scope ends. */
internal fun <T> CoroutineScope.observe(flow: Flow<T>): List<T> {
    val seen = CopyOnWriteArrayList<T>()
    launch(Dispatchers.IO) { flow.collect { seen += it } }
    return seen
}

/** The count once it has stopped moving, so a baseline is not taken while an emission is on its way. */
internal suspend fun awaitQuiet(count: () -> Int): Int {
    var last = count()
    var quietPolls = 0
    awaitUntil {
        val seen = count()
        quietPolls = if (seen == last) quietPolls + 1 else 0
        last = seen
        quietPolls >= QUIET_POLLS
    }
    return last
}
