package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.domain.StaySuppress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SuppressedStaysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val plan = StaySuppress.Plan(1, 2)
    private val now = 1700000000000L

    @After fun clear() = Settings.clearSuppressedStays(context)

    @Test fun `local deletion persists and can be undone for fourteen days`() = runTest {
        Settings.suppressStay(context, plan, now)
        val deleted = Settings.observeSuppressedStays(context).first().single()
        assertEquals(StaySuppress.Deleted(plan, now), deleted)
        Settings.unsuppressStay(context, deleted, now + 13 * 86_400_000L)
        assertTrue(Settings.suppressedStays(context).isEmpty())
        Settings.suppressStay(context, plan, now)
        Settings.unsuppressStay(context, deleted, now + 14 * 86_400_000L)
        assertEquals(listOf(deleted), Settings.suppressedStays(context))
        assertFalse(deleted.recoverable(now + 14 * 86_400_000L))
    }

    @Test fun `clearing the bin keeps hiding and leaves newer deletions recoverable`() {
        val later = StaySuppress.Plan(2, 3)
        Settings.suppressStay(context, plan, now)
        Settings.suppressStay(context, later, now + 1)
        Settings.clearStayBin(context, now)
        val rows = Settings.suppressedStays(context).associateBy { it.plan }
        assertFalse(rows.getValue(plan).recoverable(now))
        assertTrue(rows.getValue(later).recoverable(now))
        Settings.unsuppressStay(context, rows.getValue(plan), now)
        assertEquals(2, Settings.suppressedStays(context).size)
    }

    @Test fun `stale undo leaves a newer deletion alone`() {
        Settings.suppressStay(context, plan, now)
        val old = Settings.suppressedStays(context).single()
        Settings.suppressStay(context, plan, now + 1)
        Settings.unsuppressStay(context, old, now + 2)
        assertEquals(now + 1, Settings.suppressedStays(context).single().deletedAt)
    }
}
