package io.github.valeronm.breadcrumb.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class BandFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "bands") }
    private val files by lazy { BandFiles(dir, Executor(Runnable::run)) }

    private suspend fun written(text: String) = checkNotNull(files.write { it.write(text) })

    @Test fun `a band's file holds what was written to it`() = runTest {
        assertEquals("{}", written("{}").readText())
    }

    @Test fun `a band's file stays until the band's next one replaces it`() = runTest {
        val first = written("1")
        files.hold(3, first)
        val second = written("2")
        assertTrue(first.exists())

        files.hold(3, second)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test fun `closing deletes every file written, held or not`() = runTest {
        files.hold(1, written("1"))
        written("2")

        files.close()
        assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList())
    }

    @Test fun `a write finished after closing leaves no file`() = runTest {
        files.close()

        assertNull(files.write { it.write("1") })
        assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList())
    }

    @Test fun `a write that fails leaves no file behind`() = runTest {
        val failure = runCatching { files.write { throw IOException("disk full") } }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList())
    }

    @Test fun `files an earlier process left are cleared before the first write`() = runTest {
        dir.mkdirs()
        val left = File(dir, "band0.geojson").apply { writeText("{}") }

        val first = written("1")
        assertFalse(left.exists())
        written("2")
        assertTrue(first.exists())
    }
}
