package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
import kotlinx.coroutines.test.runTest
import org.junit.After
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

    @Test fun `the query returns everything the rule keeps, for every window`() = runTest {
        val dao = test.db.livenessDao()
        log.forEach { dao.insert(it) }
        val all = dao.allEvents().mapNotNull { it.toLiveness() }
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
