package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **`LivenessDao.eventsAround` must contain `StayDeriver.bearingOn`**, which is the only thing that
 * makes the query an implementation of the rule rather than a second statement of it.
 *
 * The rule says which events can bear on a window; the query is the shape an index can serve. They
 * are not identical and are not meant to be — the query is generous where being exact would cost a
 * scan (the last outage before the window comes back whether or not it was still open) — so what is
 * owed is containment in one direction, and `DerivationStore.evidenceOver` passes what comes back
 * through the rule before using it. A *missing* event is the fault this catches, and it is the one
 * that matters: the fold cannot tell an event that was filtered out from one that never happened, so
 * an under-fetch reads as a stay the app attested when it did not.
 *
 * Asked over a grid rather than at a few hand-placed windows, because the arms differ exactly at
 * boundaries — a window opening on an event, closing on one, or sitting wholly inside an outage.
 */
@RunWith(RobolectricTestRunner::class)
class LivenessWindowTest {

    private val test = TestDb()

    @After fun tearDown() = test.close()

    /** Well past every event below, so a clamp to it never changes an answer. */
    private val NOW = 10_000L

    /** The stored log as the domain reads it — the input side of every comparison here. */
    private suspend fun logAsLiveness() =
        test.db.livenessDao().allEvents().mapNotNull { it.toLiveness() }

    /** Every shape the fold carries state across, at instants a window boundary can land on. */
    private val log = listOf(
        LivenessEvent(type = LivenessEvent.TYPE_ARMED, at = 100),
        LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = 300),
        LivenessEvent(type = LivenessEvent.TYPE_ARMED, at = 500),
        LivenessEvent(type = LivenessEvent.TYPE_OUTAGE, at = 700, until = 900),
        LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = 1100),
        LivenessEvent(type = LivenessEvent.TYPE_ARMED, at = 1500),
        // Long enough to still be open over the windows that follow it.
        LivenessEvent(type = LivenessEvent.TYPE_OUTAGE, at = 1700, until = 2600),
        LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = 3000),
    )

    /**
     * **The other half of what the read path stopped folding the log for.** The trailing stay closes
     * at the disarm the app has not re-armed from, and that is now two seeks rather than a walk — so
     * the query has to say what the fold says, on a log ending every way it can: armed, disarmed,
     * disarmed then re-armed, and a run of disarms whose *first* opens it.
     */
    @Test fun `the disarm query says what folding the whole log says`() = runTest {
        val dao = test.db.livenessDao()
        // Asserted after each insert rather than over rebuilt prefixes: the log only ever grows, so
        // every state this passes through is one a running app passes through, and between them they
        // end every way one can — armed, disarmed, re-armed, dead.
        for ((seen, event) in log.withIndex()) {
            dao.insert(event)
            assertEquals(
                "after ${seen + 1} events",
                StayDeriver.disarmedSince(logAsLiveness(), NOW),
                dao.disarmedSince()?.coerceAtMost(NOW),
            )
        }
    }

    @Test fun `a log holding only disarms is disarmed from the first of them`() = runTest {
        // The case SQL gets wrong on its own: with no ARMED row, `at > (SELECT MAX(at) …)` compares
        // against null and matches nothing, which would report a disarmed app as armed.
        val dao = test.db.livenessDao()
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = 400))
        dao.insert(LivenessEvent(type = LivenessEvent.TYPE_DISARMED, at = 800))

        assertEquals(400L, dao.disarmedSince())
        assertEquals(StayDeriver.disarmedSince(logAsLiveness(), NOW), dao.disarmedSince())
    }

    @Test fun `the query returns everything the rule keeps, for every window`() = runTest {
        val dao = test.db.livenessDao()
        log.forEach { dao.insert(it) }
        val all = logAsLiveness()
        val grid = (0..34).map { it * 100L }

        for (from in grid) {
            for (until in grid) {
                if (until <= from) continue
                val over = from..until
                val fetched = dao.eventsAround(from, until).mapNotNull { it.toLiveness() }.toSet()
                val needed = StayDeriver.bearingOn(all, over)
                val missing = needed.filterNot { it in fetched }
                assertTrue("$over is missing $missing", missing.isEmpty())
            }
        }
    }
}
