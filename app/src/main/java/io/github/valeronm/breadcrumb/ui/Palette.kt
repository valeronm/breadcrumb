package io.github.valeronm.breadcrumb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup

// A qualitative (categorical) palette for activity type. M3 has no categorical roles, so this is a
// derived set: one fixed saturation + lightness, only the hue rotates, so every activity carries
// equal visual weight. It's a calmer sibling of the map's speed ramp (lower saturation) so a screen
// stays quiet. Green is nudged toward teal to avoid colliding with the app's green theme accent.
// STILL/UNKNOWN fall back to the neutral scheme color.
private const val ACTIVITY_SAT = 0.5f

private const val ACTIVITY_LUM = 0.62f

/**
 * A hue per activity, wherever movement is drawn — the Record tab's totals, the Timeline's track
 * rows and day totals, the Insights stats. It shares screens with the place palette, and the two
 * stay apart by weight rather than by surface: an activity is a tonal wash under a hued glyph,
 * while a categorized place's disc is its map pin's solid fill — so a kind of travel can't be
 * mistaken for a kind of stop even at neighboring hues.
 */
@Composable
internal fun activityColor(activity: ActivityType?): Color =
    activityColorOr(activity, MaterialTheme.colorScheme.onSurfaceVariant)

/** [activityColor] for a caller outside composition, handed the theme fallback it read there. */
internal fun activityColorOr(activity: ActivityType?, fallback: Color): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.TAXI -> Color.hsl(48f, ACTIVITY_SAT, ACTIVITY_LUM)     // taxi yellow
    ActivityType.FERRY -> Color.hsl(330f, ACTIVITY_SAT, ACTIVITY_LUM)   // magenta
    ActivityType.FLIGHT -> Color.hsl(195f, ACTIVITY_SAT, ACTIVITY_LUM)  // sky cyan
    ActivityType.TRANSIT -> Color.hsl(242f, ACTIVITY_SAT, ACTIVITY_LUM) // indigo
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> fallback
}

// The places' categorical palette: what a place was for, by category group rather than by category —
// fifteen colors would be a legend to memorize, five are a pattern picked up by scrolling. Built like
// the activity set above (fixed saturation and lightness, hue rotates, so no group outweighs another)
// and kept a step quieter than it, so where the two share a screen — the Timeline, a day's totals —
// a group still can't be mistaken for an activity at a neighboring hue.
private const val CATEGORY_SAT = 0.34f

private const val CATEGORY_LUM = 0.60f

private const val CATEGORY_TRANSIENT_SAT = 0.16f

/**
 * A map pin is read at a glance against a basemap, alone, with nothing to compete with — where a
 * disc behind a list row's text is read *with* the text and has to stay quiet enough for it. Same
 * hues, so the two surfaces still name the same groups; only how loudly they say it differs.
 */
private const val PIN_SAT = 0.85f

/**
 * The *lightest* a pin fill may be, not the lightness it gets: [forWhiteGlyph] darkens each hue
 * from here only as far as white ink needs, so every fill comes out as bright as its own hue allows
 * rather than as bright as the dullest one does.
 */
private const val PIN_MAX_LUM = 0.66f

private const val PIN_TRANSIENT_SAT = 0.38f

/** The share of its saturation a muted pin keeps — see [categoryMutedPinColor]. */
private const val PIN_MUTED_SAT = 0.28f

/**
 * The hue a group reads as — the only thing that separates the five, and the one thing every
 * surface coloring a place shares. Whatever else moves, this table is the vocabulary.
 *
 * The four carrying full chroma sit ~95° apart, as far as five slots allow, so no two are near
 * neighbours at pin size. Where a hue *means* something it follows that meaning — blue for the
 * places you belong to, green for being out, orange for the errand — and violet takes the routine
 * because the wheel's remaining gap is there. The transient pair is the odd one: kept close to
 * blue but drained of chroma, and deliberately **not** warm, which would read as a faded errand at
 * exactly the moment that matters (the car park on the way to the shop).
 */
private val PlaceCategoryGroup.hue: Float
    get() = when (this) {
        PlaceCategoryGroup.HOME_PEOPLE -> 217f // blue
        PlaceCategoryGroup.ERRANDS -> 22f      // orange
        PlaceCategoryGroup.ROUTINE -> 285f     // violet
        PlaceCategoryGroup.AWAY -> 116f        // green
        PlaceCategoryGroup.TRANSIENT -> 200f   // blue-gray
    }

/**
 * [sat], except for the transient pair, which stays deliberately the faintest of the five — the
 * stops that are passed through rather than spent. Still a hue, though, never a true neutral: that
 * is what an *untagged* place wears, and it can't be a group's color as well.
 */
private fun PlaceCategoryGroup.saturation(sat: Float, transientSat: Float) =
    if (this == PlaceCategoryGroup.TRANSIENT) transientSat else sat

/**
 * The color a categorized place reads as: its **group's**, never its own — a colour per category
 * would be a legend to memorize. Deliberately theme-free (a fixed hue, saturation and lightness, as
 * [activityColor] is), which is also what lets the map bake it into a pin image outside composition.
 */
internal fun categoryColor(category: PlaceCategory): Color =
    Color.hsl(category.group.hue, category.group.saturation(CATEGORY_SAT, CATEGORY_TRANSIENT_SAT), CATEGORY_LUM)

/** [categoryColor] as a map pin wears it — see [PIN_SAT] for why the map gets its own weight. */
internal fun categoryPinColor(category: PlaceCategory): Color = pinFill(category, satScale = 1f)

/**
 * The one recipe a pin fill is built by, so a neighbour's differs from a subject's in exactly the
 * one term that is meant to. [satScale] must apply *inside* it rather than to the colour it returns:
 * [forWhiteGlyph] darkens by measured contrast, so draining chroma afterwards would leave a fill
 * lightened for a saturation it no longer has.
 */
private fun pinFill(category: PlaceCategory, satScale: Float): Color = forWhiteGlyph(
    Color.hsl(
        category.group.hue,
        category.group.saturation(PIN_SAT, PIN_TRANSIENT_SAT) * satScale,
        PIN_MAX_LUM,
    ),
)

/**
 * What a *neighbouring* place's pin keeps of its chroma: the same hue and the same lightness, most of
 * the saturation gone. Colour is the only thing a pin spends to be noticed — hue is what the coding
 * says and lightness is what the glyph needs — so draining it is what makes a pin recede without
 * making it faint, which is the state a pin can't afford: the glyph still has to be recognisable, or
 * a neighbour is drawn for nothing.
 *
 * A *fraction* of the group's saturation rather than a flat value, so the transient groups — already
 * the quietest, deliberately — stay quietest here too instead of all five flattening to one wash.
 */
internal fun categoryMutedPinColor(category: PlaceCategory): Color =
    pinFill(category, satScale = PIN_MUTED_SAT)

/**
 * The pin an untagged place wears: chroma-free, so it can never be mistaken for a group's colour —
 * which is the same thing the lists say with [placeDiscStyle]'s neutral, said in the map's own terms.
 *
 * Fixed rather than the theme's neutral, as the five hues are. The map bakes its colours into
 * bitmaps outside composition, and one colour reaching back into the theme would be the only reason
 * a pin image depends on anything but its category. It is a *quieter* rule than it looks: the theme
 * neutral was never worn as-is either, since white ink has to read on it.
 *
 * Lazy because [forWhiteGlyph] reaches `android.graphics.Color`: computed eagerly it would run in
 * this file's static initializer, and the whole file — every pure function in it, [categoryColor]
 * and [activityColorOr] included — would then fail to load on a plain JVM, not just this value.
 */
internal val untaggedPinColor: Color by lazy { forWhiteGlyph(Color.hsl(0f, 0f, PIN_MAX_LUM)) }

/**
 * What white ink needs against the fill under it. 3:1 is the bar for a *graphical object* (WCAG
 * 1.4.11) — a glyph is a shape to recognize, not text to read, and holding it to the 4.5:1 text bar
 * costs about a fifth of the palette's brightness for legibility a Material silhouette never needed.
 */
private const val PIN_GLYPH_CONTRAST = 3.0

/** No hue is dragged below this trying to reach it — a fill that dark stops reading as its hue. */
private const val PIN_MIN_LUM = 0.18f

private const val LUM_STEP = 0.01f

/**
 * [color] darkened, hue and saturation held, until white ink on it reads. Every glyph on the map is
 * white — one ink, so a category is never told apart by the color of its *glyph* — and that is only
 * true if no fill can be too light to carry it.
 *
 * This is what the map spends instead of the lists' fixed lightness: equal *contrast* rather than
 * equal HSL, which is the same principle (no group outweighing another) measured where it shows.
 * HSL lightness is not perceived lightness — at a shared 50% a green sits nearly three times as
 * luminous as a blue — so holding the number would be holding the wrong thing.
 */
internal fun forWhiteGlyph(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    while (hsl[2] > PIN_MIN_LUM &&
        ColorUtils.calculateContrast(Color.White.toArgb(), ColorUtils.HSLToColor(hsl)) < PIN_GLYPH_CONTRAST
    ) {
        hsl[2] -= LUM_STEP
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Title color for anything place-like (Places list rows, stay cards, gap sides): named reads
 * at full onSurface, unnamed at the variant. Explicit because the inherited card color dims
 * to onSurfaceVariant under dynamic color (contentColorFor matches surfaceVariant first).
 */
@Composable
internal fun placeTitleColor(named: Boolean) =
    if (named) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Category tint where the glyph sits beside its own words — the suggestion chips and the picker's
 * rows: a categorized place takes its **group's** color (kinds of stop read as a pattern down a
 * list); an untagged one — and an unnamed cluster, which can have no category at all — stays
 * neutral, so neutral is never a group's color (see [categoryColor], where the transient pair is
 * faint but still hued for exactly that reason). The list *discs* wear [placeDiscStyle] instead.
 */
@Composable
internal fun categoryGlyphTint(category: PlaceCategory?) =
    if (category != null) {
        categoryColor(category)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * The tonal disc an activity wears wherever it is a token — the Record tab's totals, the Timeline's
 * track rows, the stats page: a wash of its own hue under a glyph in the same hue. Deliberately a
 * step quieter than [placeDiscStyle]'s solid fill; the weight difference is what keeps a kind of
 * travel from being mistaken for a kind of stop.
 */
@Composable
internal fun activityDiscStyle(activity: ActivityType?): DiscStyle =
    DiscStyle.tonal(activityColor(activity))

/**
 * The disc anything place-like wears in a list row (Timeline stays, the Places list): a categorized
 * place takes its map pin's own fill — solid, white glyph, the same token the map draws, so a stop
 * reads as one thing on both surfaces — while an untagged one stays a faint neutral tonal disc.
 * That asymmetry is deliberate: neutral recedes so the categorized pattern is what a scroll picks
 * up, and neutral is never a group's color. Deliberately a second channel beside [placeTitleColor]:
 * the title says whether you *named* the place, the disc whether you said what it's *for* — one row
 * answers both at a glance, and the pin → category glyph swap alone isn't left to carry a
 * distinction that would read as a shape change rather than a state.
 */
@Composable
internal fun placeDiscStyle(category: PlaceCategory?): DiscStyle =
    if (category != null) {
        categorizedDiscStyles.getValue(category)
    } else {
        val neutral = MaterialTheme.colorScheme.onSurfaceVariant
        DiscStyle(fill = neutral, fillAlpha = 0.12f, glyph = neutral)
    }

/**
 * The categorized styles are theme-free and a pure function of the category, so they are built
 * once rather than re-running [forWhiteGlyph]'s contrast walk on every composition of every list
 * row. Lazy for the same reason as [untaggedPinColor].
 */
private val categorizedDiscStyles: Map<PlaceCategory, DiscStyle> by lazy {
    PlaceCategory.entries.associateWith {
        DiscStyle(fill = categoryPinColor(it), fillAlpha = 1f, glyph = Color.White)
    }
}
