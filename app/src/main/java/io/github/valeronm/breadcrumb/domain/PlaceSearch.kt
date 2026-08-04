package io.github.valeronm.breadcrumb.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Matching a typed query against a place name. Two rules, both from how names are actually typed:
 * a **substring** match, not a prefix (names carry their location or chain — the remembered half
 * is as often the second word), and **accent-insensitive** both ways (a phone keyboard reaches the
 * plain letter long before the accented one — a name visible on screen must not be unreachable
 * from the keys next to hand).
 */
object PlaceSearch {

    /**
     * Whether [label] contains [query], ignoring case and diacritics; blank queries match nothing.
     * Folds both sides on every call, so a caller filtering a *list* per keystroke should instead
     * [fold] the query once, pre-fold the labels when the list changes, and compare with
     * `contains` — this overload is for one-off checks and for saying what the rule is.
     */
    fun matches(label: String, query: String): Boolean {
        val needle = fold(query)
        return needle.isNotEmpty() && fold(label).contains(needle)
    }

    /**
     * Lowercased with diacritics dropped: decompose to NFD, so an accented letter becomes the plain
     * letter plus a combining mark, then drop the marks. Folding *both* sides makes the comparison
     * symmetric — an accented query finds an unaccented name just as the reverse does.
     *
     * [Locale.ROOT], not the device's: this is a comparison key, not text anyone reads, and the
     * two sides must fold identically. A Turkish locale lowercases `I` to a dotless `ı`, so a
     * device-locale fold makes a name and a query for it stop matching.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
