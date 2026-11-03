package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendDegreesTest {

    private fun degrees(value: Double) = StringBuilder().appendDegrees(value).toString()

    @Test fun `a coordinate is written to seven decimals`() {
        assertEquals("12.3456789", degrees(12.3456789))
        assertEquals("-123.4567891", degrees(-123.4567891))
    }

    @Test fun `the decimals keep their leading and trailing zeros`() {
        assertEquals("1.0000500", degrees(1.00005))
        assertEquals("180.0000000", degrees(180.0))
        assertEquals("0.0000001", degrees(0.0000001))
    }

    @Test fun `a coordinate is rounded to the nearest ten-millionth, and one rounding to zero loses its sign`() {
        assertEquals("1.2345679", degrees(1.23456789))
        assertEquals("-0.5000000", degrees(-0.49999999))
        assertEquals("0.0000000", degrees(-0.00000004))
    }
}
