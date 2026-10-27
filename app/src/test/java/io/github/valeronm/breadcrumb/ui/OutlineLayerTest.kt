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
                "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[2,1],[3,1],[3,4],[2,1]]]}}]}",
            outlinesGeoJson(borders),
        )
    }

    @Test fun `a coordinate is written in decimal degrees, its sign kept below one degree`() {
        val borders = CountryBorders.parse(
            bordersBytes(listOf(TestOutline("PT", listOf(listOf(-0.5 to -123.45678, 0.25 to 10.0, 1.0 to 3.00001))))),
            0,
        )

        val coordinates = outlinesGeoJson(borders).substringAfter("\"coordinates\":").substringBefore("}")
        assertEquals("[[[-123.45678,-0.5],[10,0.25],[3.00001,1],[-123.45678,-0.5]]]", coordinates)
    }
}
