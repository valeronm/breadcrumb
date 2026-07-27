package io.github.valeronm.breadcrumb.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Matching a typed query against a place name.
 *
 * Two rules, both there because of how place names are actually typed. The match is a **substring**,
 * not a prefix: names carry their location or their chain ("Lidl Rebelva", "Prio Carcavelos"), so the
 * half a user remembers is as often the second word as the first. And it is **accent-insensitive** in
 * both directions, because a phone keyboard reaches "Obidos" long before "Óbidos" — a name the user
 * can see on the screen must not be unreachable from the keys next to hand.
 */
object PlaceSearch {

    /**
     * Whether [label] contains [query], ignoring case and diacritics. Blank queries match nothing.
     *
     * Folds both sides on every call, so a caller filtering a *list* per keystroke should fold once
     * itself instead — [fold] the query, pre-fold the labels when the list changes, and compare with
     * `contains`. This overload is for one-off checks and for saying what the rule is.
     */
    fun matches(label: String, query: String): Boolean {
        val needle = fold(query)
        return needle.isNotEmpty() && fold(label).contains(needle)
    }

    /**
     * Lowercased with diacritics dropped: decompose to NFD, so an accented letter becomes the plain
     * letter plus a combining mark, then drop the marks. Folding *both* sides makes the comparison
     * symmetric — an accented query finds an unaccented name just as the reverse does.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
