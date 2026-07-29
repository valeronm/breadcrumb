package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is pinned here is the *shape* of the suggester's judgement — that it learns a user's own
 * naming, that it stays silent rather than guessing from the prior, and that ambiguity widens the
 * offer instead of suppressing it. Accuracy against a real history can't be pinned by a test and
 * isn't tried: every name below is invented, as fixtures here must be.
 */
class PlaceCategorySuggesterTest {

    private var nextId = 0L

    private fun place(label: String, category: PlaceCategory?) = Place(
        id = ++nextId,
        label = label,
        lat = 1.0,
        lon = -2.0,
        createdAt = 0L,
        radiusM = 150.0,
        category = category?.code,
    )

    /** A small invented history: two chains with several branches each, plus unrelated stops. */
    private fun trained() = PlaceCategorySuggester.train(
        listOf(
            place("Kestrel Market", PlaceCategory.GROCERIES),
            place("Kestrel Market North", PlaceCategory.GROCERIES),
            place("Kestrel Market Riverside", PlaceCategory.GROCERIES),
            place("Pomfret Grocers", PlaceCategory.GROCERIES),
            place("Halvard Fuel", PlaceCategory.GAS_STATION),
            place("Halvard Fuel Ringway", PlaceCategory.GAS_STATION),
            place("Halvard Fuel East Gate", PlaceCategory.GAS_STATION),
            place("Tarn Lake Trail", PlaceCategory.OUTDOORS),
            place("Tarn Lake Meadow", PlaceCategory.OUTDOORS),
        ),
    )

    @Test fun `an untrained model suggests nothing`() {
        assertEquals(emptyList<PlaceCategory>(), PlaceCategorySuggester.Untrained.suggest("Kestrel Market"))
    }

    /**
     * The cold start needs no threshold of its own: a model trained on nothing has an empty
     * vocabulary, recognizes none of a name's features, and is silenced by the evidence gate.
     */
    @Test fun `a model trained on no tagged places suggests nothing`() {
        val model = PlaceCategorySuggester.train(listOf(place("Kestrel Market", null)))
        assertEquals(emptyList<PlaceCategory>(), model.suggest("Kestrel Market North"))
    }

    @Test fun `untagged places are not evidence`() {
        val model = PlaceCategorySuggester.train(
            listOf(
                place("Kestrel Market", PlaceCategory.GROCERIES),
                place("Halvard Fuel", null),
            ),
        )
        assertEquals(listOf(PlaceCategory.GROCERIES), model.suggest("Kestrel Market North"))
    }

    @Test fun `a new branch of a tagged chain takes its category`() {
        assertEquals(PlaceCategory.GROCERIES, trained().suggest("Kestrel Market South").first())
        assertEquals(PlaceCategory.GAS_STATION, trained().suggest("Halvard Fuel Northway").first())
        assertEquals(PlaceCategory.OUTDOORS, trained().suggest("Tarn Lake Ridge").first())
    }

    /**
     * The character n-grams, not the word tokens, are what carry this: the branch word is new and
     * the chain's stem is the only thing the model has seen before.
     */
    @Test fun `a chain's stem carries a name whose words are all new`() {
        assertEquals(PlaceCategory.GAS_STATION, trained().suggest("Halvards").first())
    }

    /** A name built of nothing the model knows is refused outright rather than answered from the prior. */
    @Test fun `an unrecognizable name draws no suggestion`() {
        assertEquals(emptyList<PlaceCategory>(), trained().suggest("Wexley Quorn"))
    }

    @Test fun `a blank or punctuation-only name draws no suggestion`() {
        assertEquals(emptyList<PlaceCategory>(), trained().suggest(""))
        assertEquals(emptyList<PlaceCategory>(), trained().suggest("   "))
        assertEquals(emptyList<PlaceCategory>(), trained().suggest("--- ,,, ---"))
    }

    /**
     * The same word typed with and without its accent is one word, as it is to [PlaceSearch] — a
     * phone keyboard reaches the plain letter first, so a long history holds both spellings of the
     * same name, and a model that split them would learn each half.
     */
    @Test fun `an accent does not split a name from its plain spelling`() {
        val model = PlaceCategorySuggester.train(
            listOf(
                place("Café Ondine", PlaceCategory.FOOD),
                place("Café Ondine Riverside", PlaceCategory.FOOD),
                place("Tarn Lake Trail", PlaceCategory.OUTDOORS),
            ),
        )
        assertEquals(PlaceCategory.FOOD, model.suggest("Cafe Ondine North").first())
        assertEquals(model.suggest("Café Ondine North"), model.suggest("Cafe Ondine North"))
    }

    @Test fun `case and punctuation do not change the verdict`() {
        val expected = trained().suggest("Kestrel Market South")
        assertEquals(expected, trained().suggest("kestrel market south"))
        assertEquals(expected, trained().suggest("KESTREL  MARKET, SOUTH"))
        assertEquals(expected, trained().suggest("  Kestrel-Market  South  "))
    }

    /**
     * A name pulled between two tagged chains is offered both rather than silenced — a low margin
     * means the answer is probably among the top few, which is the case chips exist for.
     */
    @Test fun `a name torn between two chains offers both`() {
        val model = PlaceCategorySuggester.train(
            listOf(
                place("Kestrel Market", PlaceCategory.GROCERIES),
                place("Kestrel Market North", PlaceCategory.GROCERIES),
                place("Kestrel Cafe", PlaceCategory.FOOD),
                place("Kestrel Cafe Riverside", PlaceCategory.FOOD),
            ),
        )
        val suggestions = model.suggest("Kestrel")
        assertTrue("expected both chains, got $suggestions", suggestions.size > 1)
        assertTrue(suggestions.containsAll(listOf(PlaceCategory.GROCERIES, PlaceCategory.FOOD)))
    }

    /** An unambiguous name shouldn't spend chips on runners-up. */
    @Test fun `a name matching one chain outright offers it alone`() {
        assertEquals(listOf(PlaceCategory.GROCERIES), trained().suggest("Kestrel Market North"))
    }

    @Test fun `never more than three chips`() {
        val model = PlaceCategorySuggester.train(
            PlaceCategory.entries.map { place("Ashgrove Common ${it.code}", it) },
        )
        assertTrue(model.suggest("Ashgrove Common").size <= 3)
    }

    /** Same model, same name, same chips — a suggestion that reshuffled between openings would read as noise. */
    @Test fun `suggestions are stable across calls and rebuilds`() {
        assertEquals(trained().suggest("Kestrel Market"), trained().suggest("Kestrel Market"))
        val model = trained()
        assertEquals(model.suggest("Halvard Fuel Ringway"), model.suggest("Halvard Fuel Ringway"))
    }
}
