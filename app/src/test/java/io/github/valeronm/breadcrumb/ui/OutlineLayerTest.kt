package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.CountryBorders
import io.github.valeronm.breadcrumb.domain.TestOutline
import io.github.valeronm.breadcrumb.domain.bordersBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineLayerTest {

    @Test fun `an outline is written longitude first, its ring closed`() {
        val borders = CountryBorders.parse(
            bordersBytes(listOf(TestOutline("PT", listOf(listOf(1.0 to 2.0, 1.0 to 3.0, 4.0 to 3.0))))),
            0,
        )

        assertEquals(
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"country\":\"PT\"}," +
                "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[2.0,1.0],[3.0,1.0],[3.0,4.0],[2.0,1.0]]]}}]}",
            outlinesGeoJson(borders),
        )
    }
}
