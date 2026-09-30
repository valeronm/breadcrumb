package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The names tables index the atlas's rows, so one regenerated without the other fails to parse, and
 * a language the app offers without a table names every place by its base name; neither shows until
 * a device reads the shipped files.
 *
 * Reads `src/main/assets` and `src/main/res` directly, which Gradle cannot infer —
 * `app/build.gradle.kts` declares them as inputs to the test task.
 */
class CityNamesShippedTest {

    private val atlas = CityAtlas.parse(File("src/main/assets/cities.bin").readBytes())

    private val tables: Map<String, File> =
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("raw") }.orEmpty()
            .map { it.name.removePrefix("raw").removePrefix("-") to File(it, "city_names.bin") }
            .filter { (_, file) -> file.exists() }
            .toMap()

    @Test fun `every names table indexes the shipped atlas`() {
        for (file in tables.values) CityAtlas.LocalNames.parse(file.readBytes(), atlas.size)
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
