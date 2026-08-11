package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The regression guard for why the timeline queries look the way they do. Room invalidates per
 * table, not per row: a query reading `track_points` is re-run on *every* fix of a live recording —
 * a scan of the whole point history, once a second, screen on or off, for a result that cannot have
 * changed (an open track has no `endedAt`, so no observed query selects it) — a cost invisible in
 * the query itself. So the observed queries read `tracks` only, and the recorder writes nothing to
 * `tracks` while it records. Room's Flow re-emits on any invalidation of a table its query reads —
 * it doesn't compare results — so counting emissions counts re-runs: a re-introduced join or subquery
 * over `track_points`, or a per-fix write to the track row, wakes these collectors and fails here.
 *
 * The stored derivation joins that contract rather than sitting outside it. Its tables are observed
 * too, and now written by the mutation paths rather than by one pass per version — so the question
 * "may a recorded fix reach the timeline" is asked of them on the same terms, and answered the same
 * way: a fix writes points, a finish writes the derivation.
 */
@RunWith(RobolectricTestRunner::class)
class TimelineInvalidationTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val derivation = DerivationStore(ApplicationProvider.getApplicationContext(), test.db)
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), test.db)

    @After fun tearDown() = test.close()

    /**
     * Recording 30 fixes into an open track — the shape of a 30-second walk at 1 Hz — while the
     * timeline is on screen. Not one of them may reach the timeline's queries.
     */
    @Test fun `points recorded into the open track never re-run the timeline queries`() = runBlocking {
        // A finished track, so the timeline has something to emit and the collectors settle.
        val done = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(done, it) })
        repository.finishTrack(done, TEST_START + 60_000)

        val emissions = AtomicInteger()
        val jobs = listOf<Job>(
            launch(Dispatchers.IO) { repository.observeSummaries().collect { emissions.incrementAndGet() } },
            launch(Dispatchers.IO) { repository.observeEndpoints().collect { emissions.incrementAndGet() } },
            // The stored stays, read as one snapshot however many tables they take — so a fix that
            // reached any of them counts here exactly as a fix reaching `tracks` does above.
            launch(Dispatchers.IO) { derivation.observeStored().collect { emissions.incrementAndGet() } },
        )
        awaitUntil { emissions.get() >= 3 }

        // Opening a track inserts a row into `tracks`, which does wake them — once per track, which
        // is fine. The contract under test is per *fix*, so wait out the open's own re-emission
        // before measuring, then hold still long enough that a late one cannot be mistaken for it.
        val recording = repository.startTrack(ActivityType.WALKING, TEST_START + 120_000)
        awaitUntil { emissions.get() >= 5 }
        val before = awaitQuiet(emissions::get)

        repeat(30) { i -> repository.addPoints(listOf(test.point(recording, i))) }
        // Room's invalidation refresh is asynchronous — give it every chance to fire.
        delay(SETTLE_MS)

        assertEquals("a recorded fix must not reach the timeline queries", before, emissions.get())

        // Finishing the track *must* reach them — that's the write the UI is waiting for.
        // awaitUntil throws on timeout, so reaching the end is the assertion.
        repository.finishTrack(recording, TEST_START + 180_000)
        awaitUntil { emissions.get() > before }

        jobs.forEach { it.cancel() }
    }

    /**
     * **Every reading holds a place and the clusters derived from it together**, and naming one
     * yields exactly one such reading.
     *
     * A place row and the derivation seeded from it are written in a single transaction, so no
     * reader should ever hold the first without the second — a named place no cluster points at is a
     * place with no visits and no scatter, which is what the screen that just asked for the name
     * would render for a frame. Two emissions here would mean that frame exists; an emission whose
     * place has no cluster would mean it exists *and* is what the screen settles on.
     *
     * A rename is the same question from the other side: `places` is in this flow's invalidation set
     * or a new label never reaches the screens at all.
     */
    @Test fun `a place and the derivation seeded from it arrive together`() = runBlocking {
        val done = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(done, it) })
        repository.finishTrack(done, TEST_START + 60_000)

        // Collected on IO and read here, so it has to be safe across the two — the counter the test
        // above keeps is atomic for the same reason.
        val readings = CopyOnWriteArrayList<StoredDerivation>()
        val job = launch(Dispatchers.IO) { derivation.observeStored().collect { readings += it } }
        awaitUntil { readings.isNotEmpty() }
        val before = awaitQuiet(readings::size)

        val id = places.create(test.place("Home", 1.0, -2.0))
        awaitUntil { readings.size > before }
        assertEquals("one transaction, one reading", before + 1, awaitQuiet(readings::size))
        val named = readings.last()
        assertEquals("the place is in it", listOf(id), named.places.map { it.id })
        assertEquals("and so is its cluster", listOf(id), named.clusters.mapNotNull { it.placeId })

        // A rename writes only `places`, and must still reach a reader — this flow is the only one
        // the screens have. It must also *not* re-read the derived rows, which are one per endpoint
        // and one per interval in the whole history for a write that cannot have moved any of them;
        // carrying the very lists is what proves the read was skipped rather than merely quick.
        places.save(test.place("Home base", 1.0, -2.0).copy(id = id))
        awaitUntil { readings.last().places.single().label == "Home base" }
        val renamed = readings.last()
        assertSame("the clusters were re-read", named.clusters, renamed.clusters)
        assertSame("the members were re-read", named.members, renamed.members)
        assertSame("the intervals were re-read", named.intervals, renamed.intervals)

        // And a write that *does* move a seed reads them again — a carried-over derivation must not
        // outlive the rows it was carried past.
        places.save(test.place("Home base", 1.0, -2.0).copy(id = id, radiusM = 250.0))
        awaitUntil { readings.last().clusters.single { it.placeId == id }.radiusM == 250.0 }

        job.cancel()
    }

    /** Polls until [condition] holds, so the test doesn't race Room's async invalidation refresh. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) delay(20)
        }
    }

    /**
     * The count once it has stopped moving — a baseline taken while a straggler was still in flight
     * would read as a fix that reached the queries. Waited for rather than slept through: the
     * emissions being counted arrive within a poll or two of the write, so a fixed settle would
     * spend its whole length on every run to cover a case that has already passed.
     */
    private suspend fun awaitQuiet(count: () -> Int): Int {
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

    private companion object {
        const val SETTLE_MS = 500L
        const val AWAIT_TIMEOUT_MS = 5_000L

        /** Unchanged polls (of [awaitUntil]'s 20 ms) that count as quiet — the same order as the
         *  settle below, bar that it is paid only when something really is still arriving. */
        const val QUIET_POLLS = 5
    }
}
