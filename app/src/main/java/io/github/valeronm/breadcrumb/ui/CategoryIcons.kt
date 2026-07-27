package io.github.valeronm.breadcrumb.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.valeronm.breadcrumb.domain.PlaceCategory

/**
 * The glyph for each place category. Lives here rather than on [PlaceCategory] because an
 * `ImageVector` is Android, and the domain package stays platform-free.
 *
 * Chosen as a *set*, at the two sizes they appear in — a stay row's tonal disc and a chip — because
 * the failure mode is two categories sharing a silhouette rather than one weak glyph. Two rules
 * hold it together, and both matter when a glyph is swapped:
 *
 * - **No vehicle silhouette.** A timeline row already spends `DirectionsCar`/`DirectionsWalk`/
 *   `DirectionsBoat`/`LocalTaxi` on the *track's* activity, and `DirectionsRun` on a running one, so
 *   a plane for [PlaceCategory.TRAVEL] or a runner for [PlaceCategory.SPORTS] would read as a second
 *   activity. A suitcase and a dumbbell say the same thing without the collision. (A fuel pump is
 *   not a vehicle, so [PlaceCategory.GAS_STATION] takes the literal glyph.)
 * - **One *plain* building.** [PlaceCategory.HOME] is the house, so [PlaceCategory.WORK] is the
 *   briefcase rather than the office block it would otherwise be — a rectangle with windows is the
 *   house's silhouette at 20dp. [PlaceCategory.SIGHTSEEING]'s columned portico is admissible on the
 *   same test: a pediment on columns is nothing like a pitched roof on a square.
 *
 * [PlaceCategory.PARKING] is the one letterform in the set, on the one category that has to stay
 * legible among map pins.
 *
 * The closest pair left is [PlaceCategory.GROCERIES]'s basket against [PlaceCategory.SHOPPING]'s bag
 * — both carriers with a handle. They share a color too (one errands group), so the shapes are all
 * that separate them; a swap on either should be judged against the other rather than on its own.
 */
internal val PlaceCategory.icon: ImageVector
    get() = when (this) {
        PlaceCategory.HOME -> Icons.Filled.Home
        PlaceCategory.GROCERIES -> Icons.Filled.ShoppingBasket
        PlaceCategory.SHOPPING -> Icons.Filled.LocalMall
        PlaceCategory.KIDS_SCHOOL -> Icons.Filled.School
        PlaceCategory.SPORTS -> Icons.Filled.FitnessCenter
        PlaceCategory.OUTDOORS -> Icons.Filled.Terrain
        PlaceCategory.FRIENDS_FAMILY -> Icons.Filled.People
        PlaceCategory.SERVICES -> Icons.Filled.Handyman
        PlaceCategory.HEALTH -> Icons.Filled.MedicalServices
        PlaceCategory.TRAVEL -> Icons.Filled.Luggage
        PlaceCategory.FOOD -> Icons.Filled.Restaurant
        PlaceCategory.ENTERTAINMENT -> Icons.Filled.LocalActivity
        PlaceCategory.SIGHTSEEING -> Icons.Filled.AccountBalance
        PlaceCategory.GAS_STATION -> Icons.Filled.LocalGasStation
        PlaceCategory.PARKING -> Icons.Filled.LocalParking
        PlaceCategory.WORK -> Icons.Filled.Work
    }

/**
 * The glyph for a place's category, or the plain pin when it has none — the one place that decides
 * what *untagged* looks like, since every screen showing a place has to answer it the same way.
 */
internal val PlaceCategory?.discIcon: ImageVector get() = this?.icon ?: Icons.Filled.Place
