package io.github.valeronm.breadcrumb.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The offline gazetteer: every populated place of 1,000 or more and every administrative seat
 * whatever its size, with its country and IANA time zone, as one 4 MB asset. What lets a coordinate be *named* on a device that never asks anything
 * of the network — the app's one hard constraint, and the reason a hosted geocoder is not an option
 * however much better its answers would be.
 *
 * Packed by `tools/pack_cities.py`, whose docstring is the format's specification; this reads it and
 * nothing else writes it. Rows are fixed-width and sorted by latitude, so a lookup binary-searches
 * to the query's latitude and walks outward only while a row's latitude alone could still beat the
 * best distance found — over 160,000 cities that settles in a few dozen distance calls.
 *
 * Held as the raw bytes rather than decoded into objects: a row is six fields read by offset when
 * one is wanted, and 160,000 of them as instances would cost more heap than the file. Only the
 * winning row's name is ever turned into a String.
 */
class CityAtlas private constructor(
    private val bytes: ByteArray,
    private val rowCount: Int,
    private val rowsAt: Int,
    private val namesAt: Int,
    /** Start of each row's name within the names blob; one longer than [rowCount]. */
    private val nameStarts: IntArray,
    private val zones: List<String>,
) {

    /** A named place, with how far it sat from the coordinate that found it. */
    class City(
        val name: String,
        /** ISO 3166-1 alpha-2. */
        val country: String,
        /** IANA zone id, e.g. `Europe/Lisbon` — a `java.time.ZoneId` without this module parsing one. */
        val zoneId: String,
        val population: Int,
        val distanceM: Double,
    )

    val size: Int get() = rowCount

    /**
     * The city that should *name* this coordinate: the most populous one within [NAME_WINDOW_M] of
     * the nearest, which is not the same as the nearest itself.
     *
     * A gazetteer of settlements is also a gazetteer of their parts — an arrondissement, a borough,
     * a district — each a real administrative place with its own row and its own small population.
     * Standing in the middle of Paris, the nearest row is the arrondissement, and a journey named
     * after it tells the traveller nothing they meant.
     *
     * **Both bounds are needed, and each catches what the other misses.** Without the window, a city
     * renames the villages for miles around it; without [NAME_DOMINANCE], any slightly larger town
     * renames the one next door, and nothing on screen explains why.
     *
     * **Neither can be pushed further, and the pair does not separate every case.** Real districts
     * sit up to ~5.5 km from their city centre while real neighbouring towns sit under 6 km apart,
     * so one window cannot admit every district and exclude every neighbour. This is a heuristic
     * over a gazetteer that does not mark which of its rows are parts of others — the codes are the
     * same for a capital's arrondissement and for a village — and the way out is better data, not
     * better numbers.
     */
    fun naming(lat: Double, lon: Double, distance: DistanceFn): City? {
        val anyNearest = nearest(lat, lon, distance) ?: return null
        val neighbourhood = around(lat, lon, anyNearest.distanceM, distance)
        val country = neighbourhood.country
        val local = neighbourhood.nearestInCountry?.let { cityAt(it, neighbourhood.nearestInCountryM) }
            ?: anyNearest
        // A row with no population figure keeps its name. Administrative seats are listed whatever
        // their size and many carry a zero, and a zero times any factor is a bar every neighbour
        // clears — the village someone stayed in would lose its name to the nearest larger town.
        if (local.population == 0) return local
        val reach = local.distanceM + NAME_WINDOW_M
        var best = local
        walkOutward(lat, lon, reach) { row ->
            // Population before geometry: it rules out nearly every row for the cost of one read,
            // where a distance call and a City are the expensive part of looking at one.
            if (populationAt(row) <= best.population) return@walkOutward
            if (populationAt(row) < local.population * NAME_DOMINANCE) return@walkOutward
            if (countryCodeAt(row) != country) return@walkOutward
            val d = distance.meters(lat, lon, latOf(row), lonOf(row))
            if (d <= reach) best = cityAt(row, d)
        }
        return best
    }

    /**
     * The country a coordinate is most likely in, and the nearest place in it — resolved in one walk,
     * since both questions read the same band.
     *
     * The country is the commonest among the [COUNTRY_VOTES] nearest places, ties going to the
     * nearest. A gazetteer knows nothing of borders, so near one the nearest row can easily sit on
     * the far side — a village names itself after a town in the next country, and a journey that
     * never left home counts two countries. Its neighbourhood is the evidence the row itself doesn't
     * carry. A real border city outvotes its neighbours by sitting on top of the query, so this never
     * argues a genuinely foreign place away.
     */
    private fun around(lat: Double, lon: Double, nearestM: Double, distance: DistanceFn): Neighbourhood {
        val farthest = DoubleArray(COUNTRY_VOTES) { Double.MAX_VALUE }
        val voter = IntArray(COUNTRY_VOTES) { NO_COUNTRY }
        val nearestOf = HashMap<Int, Int>(COUNTRY_VOTES)
        val nearestOfM = HashMap<Int, Double>(COUNTRY_VOTES)
        walkOutward(lat, lon, nearestM + COUNTRY_REACH_M) { row ->
            val d = distance.meters(lat, lon, latOf(row), lonOf(row))
            val code = countryCodeAt(row)
            if (d < (nearestOfM[code] ?: Double.MAX_VALUE)) {
                nearestOfM[code] = d
                nearestOf[code] = row
            }
            // Keep the nearest few, insertion-sorted — the whole band would be thousands of rows in
            // a dense country, and only the closest handful vote.
            var slot = COUNTRY_VOTES - 1
            if (d >= farthest[slot]) return@walkOutward
            while (slot > 0 && farthest[slot - 1] > d) {
                farthest[slot] = farthest[slot - 1]
                voter[slot] = voter[slot - 1]
                slot--
            }
            farthest[slot] = d
            voter[slot] = code
        }
        val votes = HashMap<Int, Int>(COUNTRY_VOTES)
        for (code in voter) if (code != NO_COUNTRY) votes[code] = (votes[code] ?: 0) + 1
        val leader = votes.maxByOrNull { it.value }?.value
        // Ties go to the nearest voter, which is the first slot holding a country with that count.
        val country = voter.firstOrNull { it != NO_COUNTRY && votes[it] == leader } ?: NO_COUNTRY
        return Neighbourhood(country, nearestOf[country], nearestOfM[country] ?: 0.0)
    }

    private class Neighbourhood(val country: Int, val nearestInCountry: Int?, val nearestInCountryM: Double)

    /** The two-letter country code packed into an int, for comparing without allocating a string. */
    private fun countryCodeAt(row: Int): Int = u16(rowsAt + row * ROW_BYTES + 10)

    private fun populationAt(row: Int): Int = u16(rowsAt + row * ROW_BYTES + 8) * 1_000

    /**
     * The nearest city to [lat]/[lon], or null only for an empty atlas. Nearest by [distance] on the
     * real geometry — the latitude ordering is a search index, never the answer.
     */
    fun nearest(lat: Double, lon: Double, distance: DistanceFn): City? {
        if (rowCount == 0) return null
        val targetE6 = (lat * 1e6).roundToInt()
        var best = -1
        var bestM = Double.MAX_VALUE
        var up = lowerBound(targetE6)
        var down = up - 1

        fun consider(row: Int) {
            val d = distance.meters(lat, lon, latOf(row), lonOf(row))
            if (d < bestM) {
                bestM = d
                best = row
            }
        }

        // Interleaved rather than one side then the other: a coordinate far from any city (at sea,
        // in a desert) would otherwise walk one direction to the bound before the first distance
        // exists to prune the other with.
        while (up < rowCount || down >= 0) {
            var moved = false
            if (up < rowCount && latBoundOf(up, targetE6) <= bestM) {
                consider(up++)
                moved = true
            } else {
                up = rowCount
            }
            if (down >= 0 && latBoundOf(down, targetE6) <= bestM) {
                consider(down--)
                moved = true
            } else {
                down = -1
            }
            if (!moved) break
        }
        return best.takeIf { it >= 0 }?.let { cityAt(it, bestM) }
    }

    /**
     * Every row that could be within [reachM] of [lat]/[lon], nearest latitude first. The shared walk
     * under both lookups: rows are ordered by latitude, so the search starts at the query's own and
     * steps outward until the latitude difference alone exceeds the reach.
     *
     * Longitude cannot bound the *walk* — the ordering says nothing about it — but it rules out most
     * of what the walk visits before anything is spent on them. A band of a quarter-degree circles
     * the earth, so at the reaches used here nearly every row in it is on another continent, and the
     * check is two reads and a compare against a distance call and the allocations behind it.
     */
    private inline fun walkOutward(lat: Double, lon: Double, reachM: Double, visit: (Int) -> Unit) {
        val targetE6 = (lat * 1e6).roundToInt()
        val lonE6 = (lon * 1e6).roundToInt()
        var up = lowerBound(targetE6)
        var down = up - 1
        while (up < rowCount && latBoundOf(up, targetE6) <= reachM) {
            if (lonBoundOf(up, lat, lonE6) <= reachM) visit(up)
            up++
        }
        while (down >= 0 && latBoundOf(down, targetE6) <= reachM) {
            if (lonBoundOf(down, lat, lonE6) <= reachM) visit(down)
            down--
        }
    }

    /**
     * The least distance a row this far east or west could be at. Meters per degree of longitude
     * shrink towards the poles, so the *higher* of the two latitudes gives the smaller figure — and
     * an understated bound is the only safe kind, since it can only fail to prune.
     */
    private fun lonBoundOf(row: Int, lat: Double, targetLonE6: Int): Double {
        val delta = abs(lonE6Of(row).toLong() - targetLonE6) / 1e6
        if (delta > HALF_TURN_DEGREES) return 0.0
        val poleward = max(abs(lat), abs(latE6Of(row) / 1e6))
        return delta * MIN_M_PER_DEGREE_LAT * cos(Math.toRadians(poleward))
    }

    private fun cityAt(row: Int, distanceM: Double): City {
        val at = rowsAt + row * ROW_BYTES
        val nameFrom = namesAt + nameStarts[row]
        return City(
            name = String(bytes, nameFrom, nameStarts[row + 1] - nameStarts[row], StandardCharsets.UTF_8),
            country = String(bytes, at + 10, 2, StandardCharsets.US_ASCII),
            zoneId = zones[u16(at + 12)],
            population = u16(at + 8) * 1_000,
            distanceM = distanceM,
        )
    }

    /**
     * The least distance a row this far north or south could possibly be at. Meridian meters per
     * degree is deliberately understated so the bound never prunes a row that could still win.
     */
    private fun latBoundOf(row: Int, targetE6: Int): Double =
        abs(latE6Of(row).toLong() - targetE6) / 1e6 * MIN_M_PER_DEGREE_LAT

    private fun lowerBound(targetE6: Int): Int {
        var lo = 0
        var hi = rowCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (latE6Of(mid) < targetE6) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun latE6Of(row: Int): Int = i32(rowsAt + row * ROW_BYTES)
    private fun lonE6Of(row: Int): Int = i32(rowsAt + row * ROW_BYTES + 4)
    private fun latOf(row: Int): Double = latE6Of(row) / 1e6
    private fun lonOf(row: Int): Double = i32(rowsAt + row * ROW_BYTES + 4) / 1e6

    private fun i32(at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or
            (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF shl 8) or (bytes[at + 1].toInt() and 0xFF)

    companion object {
        /** lat, lon, population, country, zone index, name length — see the packer. */
        private const val ROW_BYTES = 15

        /** No country voted for — no packed code can be zero, both bytes being ASCII letters. */
        private const val NO_COUNTRY = 0

        /** Beyond this a longitude difference has wrapped, and the bound is no longer a bound. */
        private const val HALF_TURN_DEGREES = 180.0

        /** Meters per degree of latitude, understated (the true minimum is ~110,574 at the equator). */
        private const val MIN_M_PER_DEGREE_LAT = 110_000.0

        /**
         * How much further than the nearest city a bigger one may sit and still take the name.
         * A district lies within a couple of kilometres of the city it belongs to; a town five
         * kilometres off is a different town, whatever its size.
         */
        private const val NAME_WINDOW_M = 5_000.0

        /**
         * How many times the nearest city's population a rival needs before it takes the name. Not
         * higher, however tempting: a city's largest district can hold a ninth of it and still be a
         * district, so a stricter bar leaves those districts naming journeys.
         */
        private const val NAME_DOMINANCE = 3

        /** How many of the nearest places vote on which country a coordinate is in. */
        private const val COUNTRY_VOTES = 5

        /** How far out those voters may be gathered from, and how far a same-country name may sit. */
        private const val COUNTRY_REACH_M = 25_000.0

        private val MAGIC = "BCTY1".toByteArray(StandardCharsets.US_ASCII)

        /**
         * Reads a packed atlas. Throws [IllegalArgumentException] on anything that isn't one, which
         * is a build error rather than a runtime condition — the file ships inside the APK.
         */
        fun parse(bytes: ByteArray): CityAtlas {
            require(bytes.size > MAGIC.size) { "city atlas is empty" }
            require(MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "not a city atlas" }
            val header = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
            val rowCount = header.int
            require(rowCount >= 0) { "city atlas declares $rowCount rows" }
            val zoneCount = header.short.toInt() and 0xFFFF
            val zones = ArrayList<String>(zoneCount)
            repeat(zoneCount) {
                val length = header.get().toInt() and 0xFF
                val id = ByteArray(length).also(header::get)
                zones += String(id, StandardCharsets.UTF_8)
            }
            val rowsAt = header.position()
            val namesAt = rowsAt + rowCount * ROW_BYTES
            require(namesAt <= bytes.size) { "city atlas is truncated" }
            val nameStarts = IntArray(rowCount + 1)
            for (row in 0 until rowCount) {
                val length = bytes[rowsAt + row * ROW_BYTES + 14].toInt() and 0xFF
                nameStarts[row + 1] = nameStarts[row] + length
            }
            require(namesAt + nameStarts[rowCount] <= bytes.size) { "city atlas names are truncated" }
            return CityAtlas(bytes, rowCount, rowsAt, namesAt, nameStarts, zones)
        }
    }
}
