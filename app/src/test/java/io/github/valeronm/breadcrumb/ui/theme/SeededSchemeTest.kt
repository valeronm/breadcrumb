package io.github.valeronm.breadcrumb.ui.theme

import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The generated scheme owes a value for every role [ColorScheme] has: one left out keeps the
 * library's baseline default instead, a lone purple among the greens, on exactly the devices nobody
 * here can look at. The generator states that as a comment over its role list; this holds the list
 * against the class, so a Compose version that adds a role fails a test rather than shipping.
 *
 * Read off the source text rather than the built schemes because a missing role is indistinguishable
 * from a supplied one by value — several roles are the same colour in every M3 scheme.
 */
class SeededSchemeTest {

    private val source = File("src/main/java/io/github/valeronm/breadcrumb/ui/theme/SeededScheme.kt")

    /**
     * Every role, taken from the class. The getters return a `Color`, which is a value class over
     * `ULong`, so on the JVM they are `long`-returning and name-mangled — `getPrimary-0d7_KjU`.
     */
    private val roles: Set<String> = ColorScheme::class.java.methods
        .filter { it.returnType == java.lang.Long.TYPE && it.name.startsWith("get") && '$' !in it.name }
        .map { it.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    @Test
    fun `the generated file is where this test thinks it is`() {
        assertTrue("expected ${source.absolutePath}", source.isFile)
        assertTrue("expected roles to be readable off ColorScheme", roles.size > 40)
    }

    @Test
    fun `both schemes state every role`() {
        val text = source.readText()
        for (scheme in listOf("SeededLightScheme", "SeededDarkScheme")) {
            val body = text.substringAfter("internal val $scheme = ").substringBefore("\n)")
            val stated = Regex("""(\w+) = Color\(""").findAll(body).map { it.groupValues[1] }.toSet()
            assertEquals("$scheme leaves roles on the library default", emptySet<String>(), roles - stated)
        }
    }
}
