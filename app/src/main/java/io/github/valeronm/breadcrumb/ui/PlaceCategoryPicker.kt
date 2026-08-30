package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.placeCategory

/**
 * One category as a chip, denser than the stock [SuggestionChip]. **Both states of the category line
 * are built from this** — the tagged place's current category and the untagged place's suggestions —
 * so the line keeps one height and one visual language whichever it is showing, and there is no
 * selector for the suggestions to look bolted onto. The glyph carries its group's colour: the same
 * set the timeline and Places rows use, already learned, and scanned faster than the label.
 * [showsMore] marks a chip that opens the full picker rather than setting a category.
 *
 * [icon] is optional because one chip has no category to stand for: "More" would only be able to
 * show a glyph *about* opening a picker, which the caret already says and the word says twice.
 *
 * [selected] switches it to a filled `InputChip`. The filled/outlined split is what carries the
 * meaning — an answer already given should not look like one more thing on offer, and a caret alone
 * said only *this opens something*, which is an affordance rather than a statement of what the chip
 * holds. The component pair follows Material's rule that a chip is chosen by who authored its
 * content, the product's guess against the person's own answer; the two are never on screen
 * together, so the differing chip metrics cost nothing.
 */
@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    showsMore: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (showsMore) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val leading: @Composable (() -> Unit)? = icon?.let {
        { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint) }
    }
    val height = Modifier.height(CATEGORY_CHIP_HEIGHT)
    if (selected) {
        InputChip(
            selected = true,
            onClick = onClick,
            label = content,
            leadingIcon = leading,
            shape = CircleShape,
            modifier = height,
        )
    } else {
        SuggestionChip(
            onClick = onClick,
            label = content,
            icon = leading,
            shape = CircleShape,
            modifier = height,
        )
    }
}

/** Shorter than the stock 32dp chip; the touch target stays 48dp, which the chip's halo supplies. */
private val CATEGORY_CHIP_HEIGHT = 30.dp

/**
 * A category the user has just tapped, held until the stored row catches up. A wrapper rather than a
 * bare [PlaceCategory]? because untagging is itself a choice: "picked Not set" and "picked nothing
 * yet" are different states and null cannot carry both.
 */
private data class PickedCategory(val value: PlaceCategory?)

/**
 * What a place is for, as **one chip-high line**: tagged, the category it carries;
 * untagged, the categories its *name* suggests ([PlaceCategorySuggester]) plus a chip onto the full
 * picker. Deliberately not a card and not a full-width row — a place spends most of its life with a
 * one-word answer here, and a card's surface and padding cost three times the line they wrap, on a
 * screen whose subject is the visits below it.
 *
 * A suggestion is a shortcut past the picker, never a replacement for it: the picker chip is always
 * present, the suggester offers at most three and often none, and nothing is written until something
 * is tapped — which is what lets a wrong guess cost a glance. Suggestions show only while untagged,
 * because a tagged place has an answer already and re-suggesting against it would invite tapping the
 * model's opinion over the user's own.
 */
@Composable
internal fun PlaceCategorySection(
    place: Place,
    suggester: PlaceCategorySuggester.Model,
    onPick: (PlaceCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingCategory by remember(place.id) { mutableStateOf(false) }
    // Keyed on the stored category as well as the place: the hold releases itself the moment the row
    // comes back carrying anything, which is what an effect comparing the two would do a frame later.
    var picked by remember(place.id, place.placeCategory) { mutableStateOf<PickedCategory?>(null) }
    // What was tapped outranks what the row currently reads, until the two agree. The write is
    // asynchronous and the row comes back through the whole stay derivation, so for the length of
    // that walk the screen would otherwise hold a place that is still untagged — and the *model*,
    // which reloads off the places table directly, is already retrained on the tag just written.
    // Untagged place plus a model that has memorized this exact name is a chip for the category the
    // user has just chosen, offered beside a row still reading "Not set".
    val pending = picked
    val category = if (pending != null) pending.value else place.placeCategory
    // Keyed on the label as well as the model: a rename is new evidence about what this place is,
    // and it retrains the model that reads it.
    val suggestions = remember(suggester, place.label, category) {
        if (category == null) suggester.suggest(place.label) else emptyList()
    }
    // Scrolls rather than wraps, so the line's height never depends on how long three category
    // labels happen to be. The picker chip is last and always present — it wears the caret, which is
    // what marks a chip that opens something rather than setting a category outright — and it is the
    // tagged place's chip too, since a tagged place has no suggestions to precede it.
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { suggestion ->
            CategoryChip(stringResource(suggestion.labelRes), suggestion.icon, categoryGlyphTint(suggestion)) {
                picked = PickedCategory(suggestion)
                onPick(suggestion)
            }
        }
        CategoryChip(
            label = category?.let { stringResource(it.labelRes) } ?: stringResource(
                if (suggestions.isEmpty()) R.string.place_category_set else R.string.place_category_more,
            ),
            // Standing alone it wears the place glyph — untagged included, which `discIcon` is the
            // one place that decides. Beside suggestions it wears none: "More" has no category to
            // stand for, and a glyph about opening a picker says what the caret already says.
            icon = if (suggestions.isEmpty()) category.discIcon else null,
            tint = categoryGlyphTint(category),
            showsMore = true,
            // Filled once a category is set, outlined until then. Material picks a chip by who
            // authored it: a suggestion is the model's guess, the category is the user's answer, and
            // an answer already given should not look like one more thing being offered.
            selected = category != null,
        ) { choosingCategory = true }
    }
    if (choosingCategory) {
        CategorySheet(
            current = category,
            onPick = {
                picked = PickedCategory(it)
                onPick(it)
                choosingCategory = false
            },
            onDismiss = { choosingCategory = false },
        )
    }
}

/**
 * The full category list: one full-width row each, so a long label sits on its own line instead of
 * wrapping mid-list. "Not set" leads — untagging is a choice worth seeing, not a gesture (re-tapping
 * the chosen one) left to discover. Grouped by colour under headings, where the coding is learned —
 * scattering one group's colour across four runs would teach nothing — and unsorted: [PlaceCategory]
 * is declared grouped, then by how often a category is chosen, so this walks the entries once and
 * heads each run as it starts.
 *
 * A **sheet** rather than a dialog: seventeen rows carrying glyphs and group headings are what a
 * modal bottom sheet is for, where a dialog would have to scroll inside its own bounded text slot.
 * It also leaves the visit list showing behind it — deciding what a place *is* is a question the
 * screen underneath helps answer. No Cancel button: dismissing a sheet is the drag or
 * the scrim, and a picker writes on the row that is tapped rather than on a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    current: PlaceCategory?,
    onPick: (PlaceCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                stringResource(R.string.places_category_question),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            // Untagged leads, above the groups: it belongs to none of them.
            CategoryRow(null, selected = current == null) { onPick(null) }
            // One pass, heading each time the group changes — the categories are *declared* in
            // this order, so reading it off them can't disagree with a second list of groups.
            var heading: PlaceCategoryGroup? = null
            PlaceCategory.entries.forEach { option ->
                if (option.group != heading) {
                    heading = option.group
                    Text(
                        stringResource(option.group.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                CategoryRow(option, selected = option == current) { onPick(option) }
            }
        }
    }
}

/**
 * One category as a row — glyph, label, and the chosen tick. Untagged (a
 * null [category]) wears the plain pin, which is what its stays show on the timeline.
 */
@Composable
private fun CategoryRow(
    category: PlaceCategory?,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    OptionRow(
        icon = category.discIcon,
        label = category?.let { stringResource(it.labelRes) } ?: stringResource(R.string.place_category_unset),
        // The picker is where the color coding is learned, so a row wears its group's color.
        tint = categoryGlyphTint(category),
        labelColor = placeTitleColor(named = category != null),
        selected = selected,
        onClick = onClick,
    )
}
