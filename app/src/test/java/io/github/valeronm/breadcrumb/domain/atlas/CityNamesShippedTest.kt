package io.github.valeronm.breadcrumb.domain.atlas

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Each names and region table records the row count of the atlas it was packed for, so a table
 * regenerated apart from the atlas fails to parse, and a language the app offers without its tables
 * reads every name in the base language; neither shows until a device reads the shipped files.
 *
 * Reads `src/main/assets` and `src/main/res` directly, which Gradle cannot infer —
 * `app/build.gradle.kts` declares them as inputs to the test task.
 */
class CityNamesShippedTest {

    private val atlas = CityAtlas.parse(
        File("src/main/assets/cities.bin").readBytes(),
        File("src/main/assets/borders.bin").readBytes(),
    )

    private val tables: Map<String, File> = tablesNamed("city_names.bin")

    private val regionTables: Map<String, File> = tablesNamed("region_names.bin")

    private fun tablesNamed(name: String): Map<String, File> =
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("raw") }.orEmpty()
            .map { it.name.removePrefix("raw").removePrefix("-") to File(it, name) }
            .filter { (_, file) -> file.exists() }
            .toMap()

    @Test fun `the shipped outlines were labeled against the shipped atlas`() {
        CountryBorders.parse(File("src/main/assets/borders.bin").readBytes(), atlas.size)
    }

    @Test fun `every names table indexes the shipped atlas`() {
        for (file in tables.values) CityAtlas.LocalNames.parse(file.readBytes(), atlas.size)
    }

    @Test fun `every region table names the shipped atlas's regions`() {
        for (file in regionTables.values) CityAtlas.RegionNames.parse(file.readBytes(), atlas.size)
    }

    @Test fun `every language with a names table has a region table, and so does the default`() {
        assertEquals(tables.keys, regionTables.keys)
    }

    @Test fun `every language the app offers besides English has its own names table`() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml")).documentElement
        val locales = config.getElementsByTagName("locale")
        val offered = (0 until locales.length)
            .map { locales.item(it).attributes.getNamedItem("android:name").nodeValue.substringBefore('-') }
            .filterNot { it == "en" }
            .toSet()
        assertEquals("languages offered vs names tables", offered, tables.keys - "")
    }
}
