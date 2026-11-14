package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where the online half of a search stands for the query as typed. */
internal sealed interface OnlineLookup {
    val hits: List<OnlinePlaceSearch.Hit>

    /** [hits] are the previous query's, which stay up until this one's land. */
    data class Searching(override val hits: List<OnlinePlaceSearch.Hit>) : OnlineLookup

    data class Settled(val outcome: Outcome) : OnlineLookup {
        override val hits: List<OnlinePlaceSearch.Hit> get() = (outcome as? Outcome.Found)?.hits.orEmpty()
    }

    companion object {
        /** A blank query's: nothing was asked. */
        val Blank: OnlineLookup = Settled(Outcome.Found(emptyList()))
    }
}

/** A longer settle than a local scan's: this one puts the query on the wire. */
private const val SETTLE_MS = 400L

/** [on] and [previous] are the Privacy switch and the rows on screen as they stand at the keystroke. */
internal fun onlineLookups(
    query: String,
    on: Boolean,
    previous: List<OnlinePlaceSearch.Hit>,
    search: suspend () -> Outcome,
): Flow<OnlineLookup> = flow {
    when {
        query.isBlank() -> emit(OnlineLookup.Blank)
        !on -> emit(OnlineLookup.Settled(Outcome.Off))
        else -> {
            emit(OnlineLookup.Searching(previous))
            delay(SETTLE_MS)
            emit(OnlineLookup.Settled(search()))
        }
    }
}

internal enum class SearchNote { SEARCHING_ONLINE, NO_MATCHES, ONLINE_FAILED, ONLINE_OFF }

/** [anyRows] counts every source's rows, the online ones included. */
internal fun searchNotes(lookup: OnlineLookup, anyRows: Boolean): List<SearchNote> = when (lookup) {
    // A request still out can fill a menu the local sources left empty.
    is OnlineLookup.Searching -> listOf(SearchNote.SEARCHING_ONLINE)
    is OnlineLookup.Settled -> listOfNotNull(
        SearchNote.NO_MATCHES.takeUnless { anyRows },
        when (lookup.outcome) {
            Outcome.Off -> SearchNote.ONLINE_OFF
            Outcome.Failed -> SearchNote.ONLINE_FAILED
            is Outcome.Found -> null
        },
    )
}
