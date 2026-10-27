package io.github.valeronm.breadcrumb.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.floor

/**
 * The country outlines (`assets/borders.bin`), packed by `tools/pack_borders.py`, whose docstring is
 * the format's specification and says why the edges of the seas are simplified and the borders are not.
 *
 * Decoded at parse into one coordinate array per ring, with each outline's edges bucketed by the latitude
 * bands they span, since a large country's outline runs to tens of thousands of vertices.
 */
class CountryBorders private constructor(private val outlines: List<Outline>) {

    /** Rings as interleaved lat, lon in [UNITS_PER_DEGREE] units. */
    private class Outline(val country: String, val rings: List<IntArray>) {
        val lats: Extent
        val lons: Extent

        init {
            var minLat = Int.MAX_VALUE
            var maxLat = Int.MIN_VALUE
            var minLon = Int.MAX_VALUE
            var maxLon = Int.MIN_VALUE
            for (ring in rings) {
                for (i in ring.indices step 2) {
                    if (ring[i] < minLat) minLat = ring[i]
                    if (ring[i] > maxLat) maxLat = ring[i]
                    if (ring[i + 1] < minLon) minLon = ring[i + 1]
                    if (ring[i + 1] > maxLon) maxLon = ring[i + 1]
                }
            }
            lats = Extent(minLat.toDouble(), maxLat.toDouble())
            lons = Extent(minLon.toDouble(), maxLon.toDouble())
        }

        private val firstBand = Math.floorDiv(lats.min.toInt(), BAND)

        /** Where each band's edges start in [bandEdges], one past the last band at the end. */
        private val bandStarts: IntArray

        /** Each edge as `ring shl EDGE_BITS or vertex`, the edge running from the vertex before it to it. */
        private val bandEdges: IntArray

        init {
            require(rings.size < 1 shl (Int.SIZE_BITS - 1 - EDGE_BITS)) { "borders file holds an outline of ${rings.size} rings" }
            val bands = Math.floorDiv(lats.max.toInt(), BAND) - firstBand + 1
            val counts = IntArray(bands + 1)
            forEachEdge { _, band -> counts[band + 1]++ }
            for (band in 1..bands) counts[band] += counts[band - 1]
            bandStarts = counts.copyOf()
            bandEdges = IntArray(counts[bands])
            forEachEdge { edge, band -> bandEdges[counts[band]++] = edge }
        }

        private inline fun forEachEdge(visit: (edge: Int, band: Int) -> Unit) {
            for ((r, ring) in rings.withIndex()) {
                require(ring.size / 2 < 1 shl EDGE_BITS) { "borders file holds a ring of ${ring.size / 2} vertices" }
                var j = ring.size - 2
                for (i in ring.indices step 2) {
                    val from = j
                    j = i
                    // A level edge crosses no line of latitude.
                    if (ring[i] == ring[from]) continue
                    val low = Math.floorDiv(minOf(ring[i], ring[from]), BAND) - firstBand
                    val high = Math.floorDiv(maxOf(ring[i], ring[from]), BAND) - firstBand
                    for (band in low..high) visit(r shl EDGE_BITS or (i / 2), band)
                }
            }
        }

        /** The format marks no ring as a hole: a point is inside when it is inside an odd number of rings. */
        fun holds(lat: Double, lon: Double): Boolean {
            if (lat !in lats || lon !in lons) return false
            val band = Math.floorDiv(floor(lat).toInt(), BAND) - firstBand
            var odd = false
            for (at in bandStarts[band] until bandStarts[band + 1]) {
                val ring = rings[bandEdges[at] ushr EDGE_BITS]
                val i = 2 * (bandEdges[at] and EDGE_MASK)
                val j = if (i == 0) ring.size - 2 else i - 2
                val latI = ring[i].toDouble()
                val latJ = ring[j].toDouble()
                if ((latI > lat) != (latJ > lat)) {
                    val lonI = ring[i + 1].toDouble()
                    val lonJ = ring[j + 1].toDouble()
                    if (lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) odd = !odd
                }
            }
            return odd
        }
    }

    private class Extent(val min: Double, val max: Double) {
        operator fun contains(value: Double) = value in min..max

        fun gap(value: Double) = maxOf(0.0, min - value, value - max)
    }

    /**
     * The country of the first outline in the file holding [lat]/[lon], or null in none. Outlines overlap,
     * and the file is in precedence order.
     */
    fun countryAt(lat: Double, lon: Double): String? {
        val y = lat * UNITS_PER_DEGREE
        val x = lon * UNITS_PER_DEGREE
        return outlines.firstOrNull { it.holds(y, x) }?.country
    }

    /** An outline's rings, each as interleaved lat, lon in [UNITS_PER_DEGREE] units, shared with the lookup and not to be written. */
    class Shape(val country: String, val rings: List<IntArray>)

    /** Every outline, in file order. */
    fun shapes(): List<Shape> = outlines.map { Shape(it.country, it.rings) }

    /** An outline's country and how far its edge is from the coordinate that found it. */
    class Nearest(val country: String, val distanceM: Double)

    /**
     * The outline whose edge is nearest [lat]/[lon], with the distance to that edge by [distance], or
     * null with no outline at all. The edge is chosen on a plane scaled to the query's latitude, then
     * measured by [distance]. The source cuts its outlines at the antimeridian and longitude is not
     * wrapped, so a point beside it is measured only to the outlines on its side.
     */
    fun nearestOutline(lat: Double, lon: Double, distance: DistanceFn): Nearest? {
        val search = EdgeSearch(lat * UNITS_PER_DEGREE, lon * UNITS_PER_DEGREE, cos(Math.toRadians(lat)))
        // The outline with the nearest box is scanned first, so its edge prunes the rest by their boxes.
        val seed = outlines.minByOrNull(search::boxSq) ?: return null
        search.scan(seed)
        for (outline in outlines) {
            if (outline !== seed && search.boxSq(outline) < search.bestSq) search.scan(outline)
        }
        val country = search.country ?: return null
        return Nearest(country, distance.meters(lat, lon, search.nearLat / UNITS_PER_DEGREE, search.nearLon / UNITS_PER_DEGREE))
    }

    /**
     * The point on the edges scanned so far nearest the query at [lat]/[lon], in [UNITS_PER_DEGREE] units, judged
     * on a plane where a unit of longitude counts [lonScale] of a unit of latitude.
     */
    private class EdgeSearch(private val lat: Double, private val lon: Double, private val lonScale: Double) {
        var bestSq = Double.MAX_VALUE
        var nearLat = 0.0
        var nearLon = 0.0
        var country: String? = null

        /** No edge of [outline] is nearer than its box. */
        fun boxSq(outline: Outline): Double {
            val dLat = outline.lats.gap(lat)
            val dLon = outline.lons.gap(lon) * lonScale
            return dLat * dLat + dLon * dLon
        }

        fun scan(outline: Outline) {
            for (ring in outline.rings) {
                var j = ring.size - 2
                for (i in ring.indices step 2) {
                    consider(ring[j], ring[j + 1], ring[i], ring[i + 1], outline.country)
                    j = i
                }
            }
        }

        private fun consider(fromLat: Int, fromLon: Int, toLat: Int, toLon: Int, edgeCountry: String) {
            val aLat = fromLat - lat
            val aLon = (fromLon - lon) * lonScale
            val dLat = (toLat - fromLat).toDouble()
            val dLon = (toLon - fromLon) * lonScale
            val lengthSq = dLat * dLat + dLon * dLon
            val t = if (lengthSq == 0.0) 0.0 else (-(aLat * dLat + aLon * dLon) / lengthSq).coerceIn(0.0, 1.0)
            val offLat = aLat + t * dLat
            val offLon = aLon + t * dLon
            val sq = offLat * offLat + offLon * offLon
            if (sq >= bestSq) return
            bestSq = sq
            nearLat = lat + offLat
            nearLon = lon + offLon / lonScale
            country = edgeCountry
        }
    }

    /** Unsigned LEB128, where running off the end throws [IllegalArgumentException] like any other
     *  malformed file. */
    private class Reader(private val bytes: ByteArray, private var at: Int) {
        fun country(): String {
            require(at + 2 <= bytes.size) { "borders file is truncated" }
            val code = String(bytes, at, 2, StandardCharsets.US_ASCII)
            require(code.all { it in 'A'..'Z' }) { "borders file holds the country code '$code'" }
            at += 2
            return code
        }

        fun count(): Int = varint().also { require(it <= Int.MAX_VALUE) { "borders file holds a bad count" } }.toInt()

        fun ring(): IntArray {
            val vertices = count()
            require(vertices >= 3) { "borders file holds a ring of $vertices vertices" }
            val ring = IntArray(vertices * 2)
            var lat = 0L
            var lon = 0L
            for (v in 0 until vertices) {
                lat += zigzag()
                lon += zigzag()
                ring[2 * v] = lat.toInt()
                ring[2 * v + 1] = lon.toInt()
            }
            return ring
        }

        private fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(at < bytes.size) { "borders file is truncated" }
                val byte = bytes[at++].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                require(shift < Long.SIZE_BITS) { "borders file holds a malformed number" }
            }
        }

        private fun zigzag(): Long {
            val encoded = varint()
            return (encoded ushr 1) xor -(encoded and 1)
        }
    }

    companion object {
        /** Units of a coordinate per degree, as packed. */
        const val UNITS_PER_DEGREE = 100_000

        /** A latitude band of the edge index, in [UNITS_PER_DEGREE] units: a tenth of a degree. */
        private const val BAND = 10_000

        /** The bits of an indexed edge naming its vertex within its ring; the rest name the ring. */
        private const val EDGE_BITS = 21
        private const val EDGE_MASK = (1 shl EDGE_BITS) - 1

        private val MAGIC = "BCBR1".toByteArray(StandardCharsets.US_ASCII)

        /** Throws [IllegalArgumentException] on anything that isn't a borders file labeled against an
         *  atlas of [rowCount] rows — a build error, both files shipping inside the APK. */
        fun parse(bytes: ByteArray, rowCount: Int): CountryBorders {
            require(bytes.size >= MAGIC.size + 8 && MAGIC.indices.all { bytes[it] == MAGIC[it] }) {
                "not a borders file"
            }
            val header = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
            val forRows = header.int
            require(forRows == rowCount) { "borders were labeled against $forRows atlas rows, the atlas has $rowCount" }
            val outlineCount = header.int
            require(outlineCount >= 0) { "borders file declares $outlineCount outlines" }
            val reader = Reader(bytes, header.position())
            val outlines = List(outlineCount) {
                val country = reader.country()
                val rings = List(reader.count()) { reader.ring() }
                require(rings.isNotEmpty()) { "borders file holds an outline with no rings" }
                Outline(country, rings)
            }
            return CountryBorders(outlines)
        }
    }
}
