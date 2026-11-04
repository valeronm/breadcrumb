package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class CasedLineJsonTest {

    @Test fun `a band writes every stroke with its stretch's coordinates`() = runTest {
        val turning = CasedLine(
            listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.001), Coordinate(0.001, 0.001), Coordinate(0.001, 0.002)),
            intArrayOf(RED, RED, BLUE, BLUE),
            BooleanArray(4),
            unmeasured = false,
            greatCircleLegs = false,
        )
        val crossing = CasedLine(
            listOf(Coordinate(0.0005, -0.001), Coordinate(0.0005, 0.003)),
            intArrayOf(BLUE),
            BooleanArray(2),
            unmeasured = true,
            greatCircleLegs = false,
        )

        val written = StringWriter().also { casedLineJson(listOf(turning, crossing), zoom = 20, it) }.toString()

        val features = listOf(
            feature("[[0,0],[0.001,0]]", """"rgba(0, 0, 0, 1)","width":6.5"""),
            feature("[[0.001,0],[0.001,0.001]]", """"rgba(0, 0, 0, 1)","width":6.5"""),
            feature("[[0,0],[0.001,0]]", """"rgba(255, 255, 255, 1)","width":5.0"""),
            feature("[[0.001,0.001],[0.002,0.001]]", """"rgba(0, 0, 0, 1)","width":6.5"""),
            feature("[[0.001,0],[0.001,0.001]]", """"rgba(255, 255, 255, 1)","width":5.0"""),
            feature("[[0,0],[0.001,0]]", """"rgba(255, 0, 0, 1)","width":3.0"""),
            feature("[[-0.001,0.0005],[0.003,0.0005]]", """"rgba(0, 0, 0, 1)","width":6.5"""),
            feature("[[0.001,0.001],[0.002,0.001]]", """"rgba(255, 255, 255, 1)","width":5.0"""),
            feature("[[0.001,0],[0.001,0.001]]", """"rgba(0, 0, 255, 1)","width":3.0"""),
            feature("[[-0.001,0.0005],[0.003,0.0005]]", """"rgba(255, 255, 255, 1)","width":5.0"""),
            feature("[[0.001,0.001],[0.002,0.001]]", """"rgba(0, 0, 255, 1)","width":3.0"""),
            feature("[[-0.001,0.0005],[0.003,0.0005]]", """"rgba(0, 0, 255, 1)","width":3.0,"unmeasured":true"""),
        )
        assertEquals(features.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}"), written)
    }

    private fun feature(coordinates: String, properties: String) =
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":$coordinates},"properties":{"color":$properties}}"""

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
