package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch.Outcome
import io.github.valeronm.breadcrumb.ui.OnlineLookup.Searching
import io.github.valeronm.breadcrumb.ui.OnlineLookup.Settled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLookupTest {

    private val earlier = listOf(OnlinePlaceSearch.Hit("Harbor Hotel", "Town", 0.0, 0.0))
    private val fresh = listOf(OnlinePlaceSearch.Hit("Harbor Hostel", "Town", 0.0, 0.0))

    private class Service(private val answer: Outcome) {
        var requests = 0
        val search: suspend () -> Outcome = {
            requests++
            answer
        }
    }

    @Test fun `a blank query sends nothing and clears the rows`() = runTest {
        val service = Service(Outcome.Found(fresh))
        val states = onlineLookups("  ", on = true, previous = earlier, service.search).toList()
        assertEquals(listOf<OnlineLookup>(Settled(Outcome.Found(emptyList()))), states)
        assertEquals(0, service.requests)
    }

    @Test fun `with the switch off the answer is immediate and nothing is sent`() = runTest {
        val service = Service(Outcome.Found(fresh))
        val states = onlineLookups("harbor", on = false, previous = emptyList(), service.search).toList()
        assertEquals(listOf<OnlineLookup>(Settled(Outcome.Off)), states)
        assertEquals(0L, currentTime)
        assertEquals(0, service.requests)
    }

    @Test fun `a typed query is searching over the previous rows until its own land`() = runTest {
        val service = Service(Outcome.Found(fresh))
        val states = onlineLookups("harbor", on = true, previous = earlier, service.search).toList()
        assertEquals(listOf(Searching(earlier), Settled(Outcome.Found(fresh))), states)
    }

    @Test fun `it is searching from the keystroke`() = runTest {
        val service = Service(Outcome.Found(fresh))
        val states = mutableListOf<OnlineLookup>()
        val typing = launch { onlineLookups("harbor", on = true, previous = earlier, service.search).collect(states::add) }
        runCurrent()
        assertEquals(listOf<OnlineLookup>(Searching(earlier)), states)
        typing.cancelAndJoin()
    }

    @Test fun `a query replaced before typing settles is never sent`() = runTest {
        val service = Service(Outcome.Found(fresh))
        val typing = launch { onlineLookups("harbor", on = true, previous = earlier, service.search).collect {} }
        advanceTimeBy(399)
        typing.cancelAndJoin()
        assertEquals(0, service.requests)
    }

    @Test fun `only an answer with results has rows`() {
        assertEquals(fresh, Settled(Outcome.Found(fresh)).hits)
        assertEquals(emptyList<OnlinePlaceSearch.Hit>(), Settled(Outcome.Failed).hits)
        assertEquals(emptyList<OnlinePlaceSearch.Hit>(), Settled(Outcome.Off).hits)
    }

    @Test fun `while searching, that is the only note, whether or not anything else matched`() {
        assertEquals(listOf(SearchNote.SEARCHING_ONLINE), searchNotes(Searching(earlier), anyRows = true))
        assertEquals(listOf(SearchNote.SEARCHING_ONLINE), searchNotes(Searching(emptyList()), anyRows = false))
    }

    @Test fun `an answer with rows needs no note`() {
        assertEquals(emptyList<SearchNote>(), searchNotes(Settled(Outcome.Found(fresh)), anyRows = true))
    }

    @Test fun `an answer that leaves the menu empty is no matches`() {
        assertEquals(listOf(SearchNote.NO_MATCHES), searchNotes(Settled(Outcome.Found(emptyList())), anyRows = false))
    }

    @Test fun `a failure is said whether or not anything else matched`() {
        assertEquals(listOf(SearchNote.ONLINE_FAILED), searchNotes(Settled(Outcome.Failed), anyRows = true))
        assertEquals(
            listOf(SearchNote.NO_MATCHES, SearchNote.ONLINE_FAILED),
            searchNotes(Settled(Outcome.Failed), anyRows = false),
        )
    }

    @Test fun `the switch being off is said whether or not anything else matched`() {
        assertEquals(listOf(SearchNote.ONLINE_OFF), searchNotes(Settled(Outcome.Off), anyRows = true))
        assertEquals(
            listOf(SearchNote.NO_MATCHES, SearchNote.ONLINE_OFF),
            searchNotes(Settled(Outcome.Off), anyRows = false),
        )
    }
}
