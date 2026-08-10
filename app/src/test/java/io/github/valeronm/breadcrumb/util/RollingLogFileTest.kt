package io.github.valeronm.breadcrumb.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The disk half of the log: what survives, what rolls away, and the order a snapshot reads back.
 * A tiny byte cap stands in for the shipped one — the rule under test is "roll past the cap, keep
 * the previous file, drop the one before it", which no realistic cap could exercise in a test.
 */
class RollingLogFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun snapshotOf(log: RollingLogFile): List<String> {
        val target = File(folder.root, "snapshot.txt")
        log.snapshotTo(target)
        return target.readLines()
    }

    @Test fun `lines come back in the order they were appended`() {
        val log = RollingLogFile(folder.newFolder(), maxFileBytes = 1_000_000)
        log.append("first")
        log.append("second")
        assertEquals(listOf("first", "second"), snapshotOf(log))
    }

    @Test fun `appending resumes where the last process stopped`() {
        val dir = folder.newFolder()
        RollingLogFile(dir).append("earlier life")
        val nextLife = RollingLogFile(dir)
        nextLife.append("this life")
        assertEquals(listOf("earlier life", "this life"), snapshotOf(nextLife))
    }

    @Test fun `rolling keeps the previous file and drops the one before it`() {
        // Cap of two lines' worth ("aa\n" + "bb\n" = 6 > 5): the file rolls on its second line.
        val log = RollingLogFile(folder.newFolder(), maxFileBytes = 5)
        log.append("aa")
        log.append("bb")
        assertEquals("the rolled file still reads back", listOf("aa", "bb"), snapshotOf(log))

        log.append("cc")
        log.append("dd")
        assertEquals("the second roll drops the first file", listOf("cc", "dd"), snapshotOf(log))
    }

    @Test fun `clear drops the whole history, rolled file included`() {
        val dir = folder.newFolder()
        val log = RollingLogFile(dir, maxFileBytes = 3)
        log.append("one")
        log.append("two")
        log.clear()
        assertEquals(emptyList<String>(), snapshotOf(log))
        assertFalse(File(dir, RollingLogFile.ROLLED_NAME).exists())

        log.append("fresh start")
        assertEquals(listOf("fresh start"), snapshotOf(log))
    }
}
