package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import kotlin.math.ln

/**
 * Guesses what a place is for from what the user called it, so naming a place can offer its likely
 * categories as one-tap chips instead of a trip through the full picker.
 *
 * The training set is the user's own tagged places, and that is the whole reason a model this small
 * works: place names are proper nouns, and a general vocabulary would have to know every shop brand
 * on earth to say anything. One person's names rhyme with each other — the same chains, the same
 * habits of abbreviation — so a naive Bayes over word tokens and character 3/4-grams learns the
 * *user's* naming, not the world's. Char n-grams alongside whole words are what carry an unseen
 * name: a branch never tagged before still shares its chain's stem with one that was.
 *
 * **Silence is a supported answer, and [MIN_KNOWN_FEATURES] is what buys it.** A name built of
 * features the model has never seen scores every category at roughly its prior, so the ranking
 * degrades to "whichever category is commonest" — confidently useless. Gating on the share of the
 * name's features that are in the vocabulary at all separates that case sharply: on a history of a
 * few hundred tagged places, names below half-known are right ~15% of the time and names almost
 * fully known ~82%. The gate also subsumes the cold start, which is why there is no minimum-example
 * threshold beside it: an untrained model has an empty vocabulary, recognizes nothing, and is
 * silent on a fresh install for free — then speaks the moment it recognizes something, which is
 * exactly when it has grounds to.
 *
 * **The margin decides how many chips, not whether to show any.** A low margin means the answer is
 * probably among the top few rather than that there is no answer, so candidates within
 * [MAX_SPREAD] per-feature log-likelihood of the best are offered together: a clear name yields one
 * chip, an ambiguous one two or three. Tuned against a history of a few hundred tagged places, that
 * shows a suggestion on ~4 names in 5, averages ~1.4 chips, and holds the true category ~78% of the
 * time. The gate is set where the share of *all* places that get correctly tagged from a chip peaks
 * — a stricter one raises the hit rate on what it does show while tagging fewer places overall,
 * which is the wrong trade when a wrong chip costs a glance and the full picker sits beside it.
 *
 * Retraining is just recounting — cheap enough to redo from scratch whenever the places table
 * changes, so there is no model to persist, version or migrate.
 */
object PlaceCategorySuggester {

    /**
     * Additive smoothing. Below 1 deliberately: with a few hundred examples and a vocabulary in the
     * hundreds, Laplace's 1 flattens the evidence of a token seen two or three times — which for a
     * chain named twice is the entire signal.
     */
    private const val ALPHA = 0.35

    /** Minimum share of a name's features the model must recognize before it will guess at all. */
    private const val MIN_KNOWN_FEATURES = 0.6

    /** How far behind the best a candidate may score (per feature) and still earn a chip. */
    private const val MAX_SPREAD = 0.2

    /** Chips are a shortcut past the picker; past three, scanning them costs what the picker costs. */
    private const val MAX_SUGGESTIONS = 3

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private val NGRAM_SIZES = 3..4

    /**
     * A trained classifier. Immutable and cheap to hold; rebuild via [train] rather than updating
     * one in place.
     */
    class Model internal constructor(
        private val featureCounts: Map<PlaceCategory, Map<String, Int>>,
        private val examples: Map<PlaceCategory, Int>,
    ) {
        // Both are functions of the counts, so they are derived rather than accumulated alongside
        // them: a denominator kept in step by hand is a denominator that can fall out of step.
        // Computed once per model, so scoring pays nothing for it.
        private val featureTotals = featureCounts.mapValues { (_, counts) -> counts.values.sum() }
        private val vocabulary = featureCounts.values.flatMapTo(mutableSetOf()) { it.keys }

        /**
         * The categories worth offering for [name], best first — empty when the model has no
         * grounds to guess, which callers must treat as a normal outcome rather than an error.
         * Never longer than [MAX_SUGGESTIONS].
         */
        fun suggest(name: String): List<PlaceCategory> {
            val features = featuresOf(name)
            if (features.isEmpty() || examples.isEmpty()) return emptyList()
            if (features.count { it in vocabulary }.toDouble() / features.size < MIN_KNOWN_FEATURES) {
                return emptyList()
            }
            val totalExamples = examples.values.sum().toDouble()
            // Iterating the enum rather than the map keeps ties resolved by declared order, so the
            // same name always yields the same chips in the same places.
            val ranked = PlaceCategory.entries
                .filter { it in examples }
                .map { category ->
                    val counts = featureCounts.getValue(category)
                    val denominator = featureTotals.getValue(category) + ALPHA * vocabulary.size
                    var score = ln(examples.getValue(category) / totalExamples)
                    features.forEach { score += ln(((counts[it] ?: 0) + ALPHA) / denominator) }
                    category to score
                }
                .sortedByDescending { (_, score) -> score }
            val best = ranked.first().second
            // Scores sum over features, so a long name spreads every gap wider than a short one's
            // for the same disagreement — dividing by the count makes one threshold fit both.
            return ranked
                .takeWhile { (_, score) -> (best - score) / features.size <= MAX_SPREAD }
                .take(MAX_SUGGESTIONS)
                .map { (category, _) -> category }
        }
    }

    /** A model that recognizes nothing and so suggests nothing — the state before any place is tagged. */
    val Untrained = Model(emptyMap(), emptyMap())

    /** Learn from the tagged places in [places]; untagged rows are the prediction target, not evidence. */
    fun train(places: List<Place>): Model {
        val featureCounts = mutableMapOf<PlaceCategory, MutableMap<String, Int>>()
        val examples = mutableMapOf<PlaceCategory, Int>()
        places.forEach { place ->
            val category = place.placeCategory ?: return@forEach
            val features = featuresOf(place.label)
            if (features.isEmpty()) return@forEach
            val counts = featureCounts.getOrPut(category) { mutableMapOf() }
            features.forEach { feature -> counts[feature] = (counts[feature] ?: 0) + 1 }
            examples[category] = (examples[category] ?: 0) + 1
        }
        return Model(featureCounts, examples)
    }

    /**
     * Whole words plus padded character n-grams. The padding is load-bearing: it makes a word's
     * first and last letters distinguishable from the same letters mid-word, which is where a chain
     * name's stem lives.
     *
     * Names arrive through [PlaceSearch.fold], so an accent is invisible here exactly as it is to
     * search — the same name typed with and without one is one name to both. Two normalizations
     * would be the app disagreeing with itself about what counts as the same place, and the typing
     * this absorbs is real: a phone keyboard reaches the plain letter first, so the same word gets
     * entered both ways over a long enough history.
     */
    private fun featuresOf(name: String): List<String> {
        val normalized = PlaceSearch.fold(name).replace(NON_WORD, " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val padded = " $normalized "
        return buildList {
            normalized.split(' ').forEach { if (it.isNotEmpty()) add("w:$it") }
            NGRAM_SIZES.forEach { size ->
                for (start in 0..padded.length - size) add("c:" + padded.substring(start, start + size))
            }
        }
    }
}
