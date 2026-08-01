package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the lock screen says about an import it is holding back. Null is the load-bearing case:
 * the note is drawn only when there is something to name, so a plain lock never grows a line
 * about files that aren't there.
 */
class PendingImportNoteTest {

    @Test
    fun `nothing waiting says nothing`() {
        assertNull(pendingImportNote(0))
        // Not reachable from the pending list, but a count is an Int and silence is the safe read.
        assertNull(pendingImportNote(-1))
    }

    @Test
    fun `one file is named in the singular`() {
        assertEquals("A GPX file is waiting to be imported.", pendingImportNote(1))
    }

    @Test
    fun `several files are counted`() {
        assertEquals("3 GPX files are waiting to be imported.", pendingImportNote(3))
    }
}
