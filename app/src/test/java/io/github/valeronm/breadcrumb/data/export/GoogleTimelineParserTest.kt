package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader
import java.time.Instant

class GoogleTimelineParserTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun parse(json: String) = GoogleTimelineParser.parse(StringReader(json))

    private fun doc(vararg segments: String) =
        """{"semanticSegments":[${segments.joinToString(",")}],"rawSignals":[],"userLocationProfile":{}}"""

    private fun activity(
        start: String = "2024-01-01T10:00:00.000+01:00",
        end: String = "2024-01-01T10:30:00.000+01:00",
        from: String = "1.0°, -2.0°",
        to: String = "1.01°, -2.0°",
        type: String = "WALKING",
    ) = """{"startTime":"$start","endTime":"$end","startTimeTimezoneUtcOffsetMinutes":60,""" +
        """"endTimeTimezoneUtcOffsetMinutes":60,"activity":{"start":{"latLng":"$from"},""" +
        """"end":{"latLng":"$to"},"distanceMeters":1111.0,"topCandidate":{"type":"$type","probability":0.9}}}"""

    private fun visit(
        start: String = "2024-01-01T08:00:00.000+01:00",
        placeId: String = "p1",
        semanticType: String = "HOME",
        at: String = "1.0°, -2.0°",
        level: Int = 0,
    ) = """{"startTime":"$start","endTime":"2024-01-01T09:00:00.000+01:00","visit":{"hierarchyLevel":$level,""" +
        """"probability":0.9,"topCandidate":{"placeId":"$placeId","semanticType":"$semanticType",""" +
        """"probability":0.9,"placeLocation":{"latLng":"$at"}}}}"""

    private fun path(vararg samples: Pair<String, String>) =
        """{"startTime":"2024-01-01T10:00:00.000+01:00","endTime":"2024-01-01T12:00:00.000+01:00",""" +
            """"timelinePath":[${samples.joinToString(",") { """{"point":"${it.second}","time":"${it.first}"}""" }}]}"""

    @Test fun `an activity reads its instants, endpoints and type`() {
        val a = parse(doc(activity(type = "IN_BUS"))).activities.single()
        assertEquals(ms("2024-01-01T09:00:00Z"), a.startMs)
        assertEquals(ms("2024-01-01T09:30:00Z"), a.endMs)
        assertEquals(Coordinate(1.0, -2.0), a.start)
        assertEquals(Coordinate(1.01, -2.0), a.end)
        assertEquals("IN_BUS", a.type)
    }

    @Test fun `the offset written into a time is honored`() {
        val a = parse(doc(activity(start = "2024-01-01T09:00:00.000+00:00", end = "2024-01-01T11:30:00.000+02:00")))
            .activities.single()
        assertEquals(ms("2024-01-01T09:00:00Z"), a.startMs)
        assertEquals(ms("2024-01-01T09:30:00Z"), a.endMs)
    }

    @Test fun `an escaped degree sign reads like a literal one`() {
        val a = parse(doc(activity(from = "1.0\\u00b0, -2.0\\u00b0"))).activities.single()
        assertEquals(Coordinate(1.0, -2.0), a.start)
    }

    @Test fun `a visit reads its place, label and pin`() {
        val v = parse(doc(visit(placeId = "abc", semanticType = "INFERRED_WORK", at = "1.002°, -2.003°")))
            .visits.single()
        assertEquals("abc", v.placeId)
        assertEquals("INFERRED_WORK", v.semanticType)
        assertEquals(Coordinate(1.002, -2.003), v.at)
        assertEquals(ms("2024-01-01T07:00:00Z"), v.startMs)
        assertEquals(ms("2024-01-01T08:00:00Z"), v.endMs)
        assertEquals(true, v.topLevel)
    }

    @Test fun `a nested visit reads as not top-level`() {
        assertEquals(false, parse(doc(visit(level = 1))).visits.single().topLevel)
    }

    @Test fun `path samples from every path segment are collected, an unreadable one dropped alone`() {
        val export = parse(
            doc(
                path("2024-01-01T10:01:00.000+01:00" to "1.001°, -2.0°", "not a time" to "1.002°, -2.0°"),
                path("2024-01-01T10:03:00.000+01:00" to "garbage"),
                path("2024-01-01T10:02:00.000+01:00" to "1.002°, -2.0°"),
            ),
        )
        assertArrayEquals(
            longArrayOf(ms("2024-01-01T09:01:00Z"), ms("2024-01-01T09:02:00Z")),
            export.path.times,
        )
        assertEquals(0, export.skipped)
    }

    @Test fun `an activity that cannot be placed is skipped and counted`() {
        val export = parse(
            doc(
                activity(start = "yesterday"),
                activity(to = ""),
                activity(start = "2024-01-01T11:00:00.000+01:00", end = "2024-01-01T10:00:00.000+01:00"),
                activity(),
            ),
        )
        assertEquals(1, export.activities.size)
        assertEquals(3, export.skipped)
    }

    @Test fun `a field of the wrong shape skips its segment instead of failing the file`() {
        val broken = """{"startTime":"2024-01-01T10:00:00.000+01:00","endTime":"2024-01-01T10:30:00.000+01:00",""" +
            """"activity":{"start":null,"end":[1,2],"topCandidate":"WALKING"}}"""
        val export = parse(doc(broken, activity()))
        assertEquals(1, export.activities.size)
        assertEquals(1, export.skipped)
    }

    @Test fun `an activity without a type keeps a null type`() {
        val noType = activity().replace(""""topCandidate":{"type":"WALKING","probability":0.9}""", """"topCandidate":{}""")
        assertNull(parse(doc(noType)).activities.single().type)
    }

    @Test fun `a memory segment is neither read nor counted as skipped`() {
        val memory = """{"startTime":"2024-01-01T00:00:00.000+01:00","endTime":"2024-01-03T00:00:00.000+01:00",""" +
            """"timelineMemory":{"trip":{"distanceFromOriginKms":800}}}"""
        val export = parse(doc(memory))
        assertEquals(0, export.activities.size)
        assertEquals(0, export.skipped)
    }

    @Test fun `a file without semantic segments is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parse("""{"rawSignals":[]}""") }
    }

    @Test fun `a bare array, the iOS export's shape, is rejected`() {
        assertThrows(IllegalStateException::class.java) { parse("""[{"startTime":"2024-01-01T10:00:00Z"}]""") }
    }

    @Test fun `repeated timestamps keep one sample and firstAfter finds the next one strictly later`() {
        val samples = PathSamples()
        samples.add(30, 1.3, -2.0)
        samples.add(10, 1.1, -2.0)
        samples.add(20, 1.2, -2.0)
        samples.add(10, 9.9, -2.0)
        val sorted = samples.sortedUnique()
        assertArrayEquals(longArrayOf(10, 20, 30), sorted.times)
        assertEquals(1.1, sorted.lats[0], 0.0)
        assertEquals(1, sorted.firstAfter(10))
        assertEquals(0, sorted.firstAfter(5))
        assertEquals(3, sorted.firstAfter(30))
    }
}
