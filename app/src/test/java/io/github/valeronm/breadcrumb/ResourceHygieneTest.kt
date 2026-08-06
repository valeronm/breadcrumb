package io.github.valeronm.breadcrumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Rules over the string resources themselves, checked by reading the files rather than by rendering
 * anything: each one is total over every string that exists and every string anyone adds later,
 * which is the property a per-string assertion could never have.
 *
 * Read with a real XML parser rather than a regex, because totality is the whole claim: a pattern
 * anchored on `<string name="x">` skips the moment a resource grows a second attribute
 * (`translatable`, `formatted`), and skips silently — the rules below would still pass green while
 * covering less than they say. The parser also costs nothing here; these files are kilobytes.
 *
 * Plain JVM on purpose — no resource table, no Robolectric, no emulation. What is being checked is
 * how the files are *written*, and that question is answerable from the text.
 *
 * **This reads `src/main/res` off the disk, which Gradle cannot infer.** `app/build.gradle.kts`
 * declares those files as an input to the test task; without that the task stays up-to-date through
 * an XML-only edit and every rule here reports green without running.
 */
class ResourceHygieneTest {

    private val resDir = File("src/main/res")

    private val valuesDirs: List<File>
        get() = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun xmlIn(dir: File): List<File> =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    /**
     * The `<string>` values a folder declares, as name-to-text. Deliberately over *all* its XML
     * rather than `strings*.xml`: the hazards below belong to the resource format, not to a
     * filename, and a future `arrays.xml` would inherit them without inheriting the check.
     */
    private fun stringsIn(dir: File): List<Pair<String, String>> =
        xmlIn(dir).flatMap { file ->
            parse(file).elementsNamed("string").map { it.getAttribute("name") to it.textContent }
        }

    /** The `<plurals>` and `<string-array>` items, one entry per quantity or index. */
    private fun countedIn(dir: File): List<Pair<String, String>> =
        xmlIn(dir).flatMap { file ->
            (parse(file).elementsNamed("plurals") + parse(file).elementsNamed("string-array"))
                .flatMap { parent ->
                    val name = parent.getAttribute("name")
                    parent.elementsNamed("item").mapIndexed { index, item ->
                        val which = item.getAttribute("quantity").ifEmpty { index.toString() }
                        "$name[$which]" to item.textContent
                    }
                }
        }

    /**
     * Every key a folder declares, with a plural's quantities collapsed back to the one name they
     * belong to — which is the only shape the coverage rule below can use: how many quantities a
     * plural takes is the *language's* answer, and a rule demanding English's two would fail a
     * language that needs one or four.
     */
    private fun keysIn(dir: File): Set<String> =
        (stringsIn(dir).map { it.first } + countedIn(dir).map { it.first.substringBefore('[') })
            .toSet()

    /** Keys the base folder marks as not for translation — a product name, or bare punctuation. */
    private fun untranslatable(dir: File): Set<String> =
        xmlIn(dir).flatMap { file ->
            parse(file).elementsNamed("string")
                .filter { it.getAttribute("translatable") == "false" }
                .map { it.getAttribute("name") }
        }.toSet()

    @Test
    fun `the resource folders are where this test thinks they are`() {
        // Guards the rest of the class: a moved source root, or a parser that quietly matched
        // nothing, would otherwise make every rule below pass by finding nothing to check.
        assertTrue("no values* dirs under $resDir", valuesDirs.isNotEmpty())
        assertTrue("no locale folders under $resDir", localeOf(valuesDirs).isNotEmpty())
        valuesDirs.forEach {
            assertTrue("no strings in ${it.name}", (stringsIn(it) + countedIn(it)).isNotEmpty())
        }
    }

    /**
     * **A resource that needs a space at its edge is a sentence fragment**, and a fragment freezes
     * word order: whatever it brackets can only ever sit where the seam between the pieces puts it,
     * so every language inherits the one the fragments were written in. A line built around a value
     * the format string can't carry — a drawn clock time, say — goes through
     * `annotatedStringResource` and stays one whole sentence with a placeholder in it.
     *
     * Checked as whitespace because that is the readable symptom, and because the same rule catches
     * the plainer bug underneath it: Android strips edge whitespace from an unquoted resource, so a
     * fragment that *was* deliberate loses its space silently and renders welded to its neighbour.
     * Quoting it would preserve the space and leave the word order frozen, which is why quoting is
     * no longer the fix.
     */
    @Test
    fun `no resource is a sentence fragment`() {
        val offenders = valuesDirs.flatMap { dir ->
            (stringsIn(dir) + countedIn(dir))
                // Android's quoting is stripped first, or the rule reads past exactly what it is
                // looking for: in `"Until "` the outermost characters are the quotes, so the raw
                // text trims to itself and the fragment the quotes exist to preserve goes unseen.
                .map { (name, text) -> name to text.unquoted() }
                .filter { (_, text) -> text != text.trim() }
                .map { (name, text) -> "${dir.name}/$name = \"$text\"" }
        }
        assertEquals("resources with edge whitespace", emptyList<String>(), offenders)
    }

    /**
     * A translation that invents or renumbers a placeholder crashes at format time rather than
     * reading oddly, and only on the device set to that language.
     *
     * A plural is held to the same rule minus one allowance: its count is always passed, so a
     * translation may *add* a count placeholder where the English had none — and in some languages it
     * must, since Portuguese counts 0 as singular and so needs the number in a form where English can
     * write "the one track". Adding anything else, or renumbering, still fails; skipping plurals
     * wholesale would exempt exactly the resources a translator is most likely to fumble, English
     * offering two forms where their language wants four.
     *
     * Which quantities a plural takes is the language's own answer, so an item in a quantity English
     * does not author — Russian's `few` and `many` — is checked against the English `other`, the one
     * bucket every language must fill.
     */
    @Test
    fun `every translation uses the same placeholders as the English`() {
        val baseStrings = stringsIn(File(resDir, "values")).toMap()
        val baseCounted = countedIn(File(resDir, "values")).toMap()
        val mismatches = valuesDirs.filter { it.name != "values" }.flatMap { dir ->
            fun check(entries: List<Pair<String, String>>, base: Map<String, String>, counted: Boolean) =
                entries.mapNotNull { (name, text) ->
                    val english = base[name]
                        ?: base["${name.substringBefore('[')}[other]"].takeIf { counted }
                        ?: return@mapNotNull "${dir.name}/$name has no English original"
                    val expected = PLACEHOLDER.findAll(english).map { it.value }.toSet()
                    val actual = PLACEHOLDER.findAll(text).map { it.value }.toSet()
                    val added = actual - expected
                    val ok = expected - actual == emptySet<String>() &&
                        (added.isEmpty() || (counted && added.all { it in COUNT_PLACEHOLDERS }))
                    "${dir.name}/$name expects $expected but has $actual".takeUnless { ok }
                }
            check(stringsIn(dir), baseStrings, counted = false) +
                check(countedIn(dir), baseCounted, counted = true)
        }
        assertEquals("placeholder mismatches", emptyList<String>(), mismatches)
    }

    /**
     * A string with no translation falls back to English silently — the app keeps working and one
     * line of it is simply in the wrong language, which is invisible to everyone who does not read
     * that screen in that language. It is also the failure a *rendering* test cannot catch by
     * itself: composed under a locale whose key is missing, a row renders the English fallback and
     * the assertion passes.
     *
     * Total over the whole table, which is why it lives here rather than in a suite that composes
     * rows: it costs a file read per language and holds for every string anyone adds later.
     */
    @Test
    fun `every translatable string reaches every language that ships`() {
        val base = File(resDir, "values")
        val owed = keysIn(base) - untranslatable(base)
        val missing = valuesDirs.filter { it.name != "values" }.flatMap { dir ->
            (owed - keysIn(dir)).sorted().map { "${dir.name}/$it" }
        }
        assertEquals("strings with no translation", emptyList<String>(), missing)
    }

    /**
     * Android 13+'s per-app language picker reads `locales_config.xml` and nothing else, so a
     * translation that ships as a folder without being declared there is invisible to every user who
     * would want it — and the app looks monolingual on the one screen that offers the choice.
     * Checked both ways: a declared language with no folder is a picker entry that changes nothing.
     *
     * Folders are matched as *locale qualifiers*, not by name: `values-night` and `values-v31` are
     * configurations rather than languages, and reporting them as undeclared would send the reader to
     * add them to a file Android would then ignore. A regional variant is normalised on the way past,
     * because a folder spells it `values-pt-rBR` and the config spells the same language `pt-BR`.
     */
    @Test
    fun `every translation folder is declared in the locale config`() {
        val config = parse(File(resDir, "xml/locales_config.xml"))
        val declared = config.elementsNamed("locale").map { it.getAttribute("android:name") }.toSet()
        // `values` itself is the fallback the others are translations of, so it answers for English.
        val shipped = localeOf(valuesDirs) + DEFAULT_LOCALE
        assertEquals("languages shipped vs declared", shipped, declared)
    }

    /** The BCP-47 tags the `values-*` folders stand for, ignoring non-locale qualifiers. */
    private fun localeOf(dirs: List<File>): Set<String> =
        dirs.mapNotNull { LOCALE_QUALIFIER.matchEntire(it.name.removePrefix("values-")) }
            .map { "${it.groupValues[1]}${it.groupValues[2].replace("-r", "-")}" }
            .toSet()

    private fun String.unquoted() =
        if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

    private companion object {
        const val DEFAULT_LOCALE = "en"
        val PLACEHOLDER = Regex("""%\d+\$[sd]|%[sd]""")
        val COUNT_PLACEHOLDERS = setOf("%d", "%1\$d")

        /** `pt`, `pt-rBR` — a language, optionally with a region. Anything else is a configuration. */
        val LOCALE_QUALIFIER = Regex("""([a-z]{2,3})(-r[A-Z]{2})?""")

        /** Descendants by tag name, as [Element]s — the DOM API hands back raw nodes. */
        fun Element.elementsNamed(tag: String): List<Element> =
            getElementsByTagName(tag).let { nodes ->
                (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            }
    }
}
