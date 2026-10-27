package io.github.valeronm.breadcrumb.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * The offline city atlas: every populated place of 1,000 or more and every administrative seat
 * whatever its size, with its country and IANA time zone, as one 4 MB asset. What lets a coordinate be *named* on a device that never asks anything
 * of the network — the app's one hard constraint, and the reason a hosted geocoder is not an option
 * however much better its answers would be.
 *
 * Packed by `tools/pack_cities.py`, whose docstring is the format's specification; this reads it and
 * nothing else writes it. Rows are fixed-width and sorted by latitude, the order [LatitudeIndex]
 * searches outward by.
 *
 * A place is named in a language through [withNames] wherever that language's table has a name for
 * it, and by its row's own name everywhere else. A coordinate's country comes from the outlines
 * [parse] reads beside the rows.
 *
 * Held as the raw bytes rather than decoded into objects: a row's fields are read by offset when
 * one is wanted, and 160,000 of them as instances would cost more heap than the file. Only the
 * winning row's name is ever turned into a String — with one exception: the first name search
 * folds every name once into its own index ([searchByName]).
 */
class CityAtlas private constructor(
    private val table: Table,
    val borders: CountryBorders,
    private val local: LocalNames = LocalNames.NONE,
) {

    /** The parsed rows, shared by every [withNames] copy of one atlas. */
    private class Table(
        val bytes: ByteArray,
        val rowCount: Int,
        val rowsAt: Int,
        val namesAt: Int,
        /** Start of each row's name within the names blob; one longer than [rowCount]. */
        val nameStarts: IntArray,
        val zones: List<String>,
    ) : LatitudeIndex.Rows {
        override val count get() = rowCount

        override fun latE6(row: Int) = i32(rowsAt + row * ROW_BYTES + LAT_AT)

        override fun lonE6(row: Int) = i32(rowsAt + row * ROW_BYTES + LON_AT)

        private fun i32(at: Int): Int =
            (bytes[at].toInt() and 0xFF shl 24) or
                (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or
                (bytes[at + 3].toInt() and 0xFF)

        val index = LatitudeIndex(this)
    }

    private val bytes = table.bytes
    private val rowCount = table.rowCount
    private val rowsAt = table.rowsAt
    private val namesAt = table.namesAt
    private val nameStarts = table.nameStarts
    private val zones = table.zones

    private val index = table.index

    /**
     * The names places have in one language where they differ from a row's own, packed as that
     * language's `res/raw-<language>/city_names.bin` — the packer's docstring is the format. Indexes the rows of
     * the atlas written in the same run, which [parse] checks by their count.
     */
    class LocalNames private constructor(
        private val bytes: ByteArray,
        private val rows: IntArray,
        private val namesAt: Int,
        /** Start of each entry's name within the names blob; one longer than [rows]. */
        private val nameStarts: IntArray,
    ) {
        /** The row's name in this language, or null where it has none of its own. */
        fun nameOf(row: Int): String? {
            val entry = rows.binarySearch(row)
            if (entry < 0) return null
            val from = namesAt + nameStarts[entry]
            return String(bytes, from, nameStarts[entry + 1] - nameStarts[entry], StandardCharsets.UTF_8)
        }

        companion object {
            private const val ENTRY_BYTES = 5

            /** No names of a language's own: every row reads its base name. */
            val NONE = LocalNames(ByteArray(0), IntArray(0), 0, IntArray(1))
            private val MAGIC = "BCTN1".toByteArray(StandardCharsets.US_ASCII)

            /** Throws [IllegalArgumentException] on anything that isn't a names table for an atlas
             *  of [rowCount] rows — a build error, both files shipping inside the APK. */
            fun parse(bytes: ByteArray, rowCount: Int): LocalNames {
                require(bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }) {
                    "not a city names table"
                }
                val header = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
                val forRows = header.int
                require(forRows == rowCount) { "city names index $forRows rows, the atlas has $rowCount" }
                val entryCount = header.int
                val entriesAt = header.position()
                val namesAt = entriesAt + entryCount * ENTRY_BYTES
                require(entryCount >= 0 && namesAt <= bytes.size) { "city names table is truncated" }
                val entries = ByteBuffer.wrap(bytes)
                val rows = IntArray(entryCount)
                val nameStarts = IntArray(entryCount + 1)
                for (entry in 0 until entryCount) {
                    rows[entry] = entries.getInt(entriesAt + entry * ENTRY_BYTES)
                    val length = bytes[entriesAt + entry * ENTRY_BYTES + 4].toInt() and 0xFF
                    nameStarts[entry + 1] = nameStarts[entry] + length
                }
                require(namesAt + nameStarts[entryCount] <= bytes.size) { "city names are truncated" }
                return LocalNames(bytes, rows, namesAt, nameStarts)
            }
        }
    }

    /** This atlas naming its places from [names] where a row has one there, and by the row's own
     *  name everywhere else. Shares the rows; the search index is built afresh for the new names. */
    fun withNames(names: LocalNames): CityAtlas = CityAtlas(table, borders, names)

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

    /** A name-search hit — where the named place sits, for a screen placing a pin by it. */
    class Hit(
        val name: String,
        /** ISO 3166-1 alpha-2. */
        val country: String,
        val lat: Double,
        val lon: Double,
    )

    /**
     * The folded names as one string plus per-row offsets into it — [searchByName]'s index. A row
     * with a name of its own in [local]'s language holds both, that one after [NAME_SEPARATOR].
     */
    private class FoldedNames(val text: String, val starts: IntArray)

    /** Built on the first search, kept for the process; a benign race — two builders agree. */
    @Volatile
    private var foldedNames: FoldedNames? = null

    /**
     * The rows whose names contain [query], by [PlaceSearch]'s rules (substring,
     * case- and accent-insensitive), a row's own name and its name in [local]'s language alike:
     * prefix matches before infix ones, each bucket most populous first, at most [limit]. The first
     * call folds every name into one searchable string — about twice the names blob in heap (UTF-16
     * over UTF-8), paid only if a search ever happens — after which a query is a string scan and a
     * handful of row reads.
     */
    fun searchByName(query: String, limit: Int): List<Hit> {
        val needle = PlaceSearch.fold(query)
        if (needle.length < MIN_QUERY_CHARS || limit <= 0) return emptyList()
        val names = foldedNames()
        val prefix = mutableListOf<Int>()
        val infix = mutableListOf<Int>()
        var at = names.text.indexOf(needle)
        while (at >= 0) {
            val row = rowAtOffset(names.starts, at)
            // indexOf finds the leftmost hit, so one running past this row's end is a false match
            // spanning two names — and no real one can sit earlier in the row to go back for.
            if (at + needle.length <= names.starts[row + 1]) {
                (if (names.startsWith(needle, row)) prefix else infix) += row
            }
            at = names.text.indexOf(needle, names.starts[row + 1])
        }
        return (mostPopulous(prefix, limit) + mostPopulous(infix, limit)).take(limit).map { row ->
            Hit(name = nameAt(row), country = countryAt(row), lat = latOf(row), lon = lonOf(row))
        }
    }

    /**
     * The [limit] most populous of [rows], most populous first — a bounded slate kept by insertion:
     * a two-letter query matches tens of thousands of rows, and a full sort re-decodes the population
     * field on every comparison for an order only the head of is read.
     */
    private fun mostPopulous(rows: List<Int>, limit: Int): List<Int> {
        val size = minOf(rows.size, limit)
        if (size == 0) return emptyList()
        val bestRows = IntArray(size)
        val bestPops = IntArray(size) { -1 }
        for (row in rows) {
            val pop = populationAt(row)
            var slot = size - 1
            if (pop <= bestPops[slot]) continue
            while (slot > 0 && bestPops[slot - 1] < pop) {
                bestPops[slot] = bestPops[slot - 1]
                bestRows[slot] = bestRows[slot - 1]
                slot--
            }
            bestPops[slot] = pop
            bestRows[slot] = row
        }
        return bestRows.toList()
    }

    /**
     * Folded per row rather than as one pass over the blob: folding can change a name's length, so
     * each row's offsets have to be measured on its own folded text.
     */
    private fun foldedNames(): FoldedNames {
        foldedNames?.let { return it }
        val out = StringBuilder(nameStarts[rowCount])
        val starts = IntArray(rowCount + 1)
        for (row in 0 until rowCount) {
            starts[row] = out.length
            out.append(PlaceSearch.fold(baseNameAt(row)))
            val localName = local.nameOf(row) ?: continue
            // A folded query holds no separator, so no match can run from one name into the other.
            out.append(NAME_SEPARATOR)
            out.append(PlaceSearch.fold(localName))
        }
        starts[rowCount] = out.length
        return FoldedNames(out.toString(), starts).also { foldedNames = it }
    }

    private fun FoldedNames.startsWith(needle: String, row: Int): Boolean {
        if (text.startsWith(needle, starts[row])) return true
        val separator = text.indexOf(NAME_SEPARATOR, starts[row])
        return separator in 0 until starts[row + 1] && text.startsWith(needle, separator + 1)
    }

    /** The row whose folded name holds [offset]: the last row starting at or before it. */
    private fun rowAtOffset(starts: IntArray, offset: Int): Int {
        var lo = 0
        var hi = starts.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * The city that should *name* this coordinate, which is not the same as the nearest one: the most
     * populous within [NAME_WINDOW_M] of the nearest in the coordinate's country and in the nearest's
     * region, the nearer of two equally populous.
     *
     * **The country comes from the outlines** ([parse]), since a place's own code carries the
     * place's country, not which side of a border the coordinate is on. Outside every outline it is
     * the country of whichever is nearer, the nearest outline or the nearest place. A coordinate in a
     * country with no place in the atlas is named by the nearest place of any.
     *
     * An atlas of settlements is also an atlas of their parts — an arrondissement, a borough,
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
     * over an atlas that does not mark which of its rows are parts of others — the codes are the
     * same for a capital's arrondissement and for a village — and the way out is better data, not
     * better numbers.
     *
     * **A bigger place takes the name only from a place in its own region**, the first-level division
     * the atlas codes each row with: where towns run into one another across a regional line, the
     * larger one in reach is a neighbor whatever its size.
     */
    fun naming(lat: Double, lon: Double, distance: DistanceFn): City? {
        val found = nearestInCountry(lat, lon, distance) ?: return null
        val local = cityAt(found.row, found.distanceM)
        val country = countryCodeAt(found.row)
        val region = regionAt(found.row)
        // A row with no population figure keeps its name. Administrative seats are listed whatever
        // their size and many carry a zero, and a zero times any factor is a bar every neighbour
        // clears — the village someone stayed in would lose its name to the nearest larger town.
        if (local.population == 0) return local
        val reach = local.distanceM + NAME_WINDOW_M
        var best = local
        index.forEachNear(lat, lon, reach, distance) { row ->
            // Population before geometry: it rules out nearly every row for the cost of one read,
            // where a distance call and a City are the expensive part of looking at one.
            val population = populationAt(row)
            if (population < best.population) return@forEachNear
            if (population < local.population * NAME_DOMINANCE) return@forEachNear
            if (countryCodeAt(row) != country) return@forEachNear
            if (!sameRegion(regionAt(row), region)) return@forEachNear
            val d = distance.meters(lat, lon, latOf(row), lonOf(row))
            if (d > reach) return@forEachNear
            if (population > best.population || d < best.distanceM) best = cityAt(row, d)
        }
        return best
    }

    private fun nearestInCountry(lat: Double, lon: Double, distance: DistanceFn): LatitudeIndex.Found? {
        fun nearestIn(country: Int) = index.nearest(lat, lon, distance) { countryCodeAt(it) == country }
        fun nearestOfAny() = index.nearest(lat, lon, distance) { true }

        borders.countryAt(lat, lon)?.let { return nearestIn(packedCode(it)) ?: nearestOfAny() }
        val anyNearest = nearestOfAny() ?: return null
        val outline = borders.nearestOutline(lat, lon, distance)
        if (outline == null || outline.distanceM >= anyNearest.distanceM) return anyNearest
        val country = packedCode(outline.country)
        if (country == countryCodeAt(anyNearest.row)) return anyNearest
        return nearestIn(country) ?: anyNearest
    }

    /** The two-letter country code packed into an int, for comparing without allocating a string. */
    private fun countryCodeAt(row: Int): Int = u16(rowsAt + row * ROW_BYTES + COUNTRY_AT)

    private fun packedCode(country: String): Int = (country[0].code shl 8) or country[1].code

    private fun regionAt(row: Int): Int = u16(rowsAt + row * ROW_BYTES + REGION_AT)

    // GeoNames leaves some cities without a region while coding their districts.
    private fun sameRegion(a: Int, b: Int): Boolean = a == b || a == NO_REGION || b == NO_REGION

    private fun populationAt(row: Int): Int = u16(rowsAt + row * ROW_BYTES + POPULATION_AT) * 1_000

    private fun cityAt(row: Int, distanceM: Double): City {
        val at = rowsAt + row * ROW_BYTES
        return City(
            name = nameAt(row),
            country = countryAt(row),
            zoneId = zones[u16(at + ZONE_AT)],
            population = populationAt(row),
            distanceM = distanceM,
        )
    }

    private fun nameAt(row: Int): String = local.nameOf(row) ?: baseNameAt(row)

    private fun baseNameAt(row: Int): String {
        val from = namesAt + nameStarts[row]
        return String(bytes, from, nameStarts[row + 1] - nameStarts[row], StandardCharsets.UTF_8)
    }

    private fun countryAt(row: Int): String =
        String(bytes, rowsAt + row * ROW_BYTES + COUNTRY_AT, 2, StandardCharsets.US_ASCII)

    private fun latOf(row: Int): Double = table.latE6(row) / 1e6
    private fun lonOf(row: Int): Double = table.lonE6(row) / 1e6

    private fun u16(at: Int): Int = (bytes[at].toInt() and 0xFF shl 8) or (bytes[at + 1].toInt() and 0xFF)

    companion object {
        private const val LAT_AT = 0
        private const val LON_AT = 4
        private const val POPULATION_AT = 8
        private const val COUNTRY_AT = 10
        private const val REGION_AT = 12
        private const val ZONE_AT = 14
        private const val NAME_LENGTH_AT = 16
        private const val ROW_BYTES = 17

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

        /** A folded needle shorter than this matches a meaningless fraction of the atlas — a
         *  one-char query hits tens of thousands of rows for a ranking no single letter asked. */
        private const val MIN_QUERY_CHARS = 2

        private const val NAME_SEPARATOR = '\u0000'

        private const val NO_REGION = 0

        private val MAGIC = "BCTY2".toByteArray(StandardCharsets.US_ASCII)

        /**
         * Reads a packed atlas and the [borders] file labeled against it. Throws
         * [IllegalArgumentException] on anything that isn't such a pair, which is a build error rather
         * than a runtime condition — both files ship inside the APK.
         */
        fun parse(bytes: ByteArray, borders: ByteArray): CityAtlas {
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
                val length = bytes[rowsAt + row * ROW_BYTES + NAME_LENGTH_AT].toInt() and 0xFF
                nameStarts[row + 1] = nameStarts[row] + length
            }
            require(namesAt + nameStarts[rowCount] <= bytes.size) { "city atlas names are truncated" }
            return CityAtlas(
                Table(bytes, rowCount, rowsAt, namesAt, nameStarts, zones),
                CountryBorders.parse(borders, rowCount),
            )
        }
    }
}
