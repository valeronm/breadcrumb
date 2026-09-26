package io.github.valeronm.breadcrumb.data.export

import org.junit.Assert.assertEquals
import org.junit.Test

class LogExporterTest {

    private val stamp = exportFileStamp(0L)

    @Test fun `the phone name joins the file name with its punctuation dashed`() {
        assertEquals("breadcrumb-logs-Val-s-Pixel-8-$stamp.txt", LogExporter.fileName("Val’s Pixel 8", 0L))
    }

    @Test fun `letters of any alphabet survive`() {
        assertEquals("breadcrumb-logs-Телефон-$stamp.txt", LogExporter.fileName(" Телефон! ", 0L))
    }

    @Test fun `a name with nothing file-safe leaves the phone out`() {
        assertEquals("breadcrumb-logs-$stamp.txt", LogExporter.fileName("’ ?", 0L))
    }
}
