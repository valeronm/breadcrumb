package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup

/**
 * The name each place category goes by, here rather than on [PlaceCategory] for the same reason its
 * glyph is (see `CategoryIcons`): a name is language, and the domain package holds no resources.
 *
 * The enum's [PlaceCategory.code] is what persists — into the `places.category` column and into the
 * backup file the web viewer reads — so a code and a name can never be confused for one another
 * once the name lives over here and can be translated.
 */
internal val PlaceCategory.labelRes: Int
    @StringRes get() = when (this) {
        PlaceCategory.FRIENDS_FAMILY -> R.string.place_category_friends_family
        PlaceCategory.HOME -> R.string.place_category_home
        PlaceCategory.GROCERIES -> R.string.place_category_groceries
        PlaceCategory.FOOD -> R.string.place_category_food
        PlaceCategory.SHOPPING -> R.string.place_category_shopping
        PlaceCategory.SERVICES -> R.string.place_category_services
        PlaceCategory.HEALTH -> R.string.place_category_health
        PlaceCategory.KIDS_SCHOOL -> R.string.place_category_kids_school
        PlaceCategory.SPORTS -> R.string.place_category_sports
        PlaceCategory.WORK -> R.string.place_category_work
        PlaceCategory.OUTDOORS -> R.string.place_category_outdoors
        PlaceCategory.SIGHTSEEING -> R.string.place_category_sightseeing
        PlaceCategory.TRAVEL -> R.string.place_category_travel
        PlaceCategory.ENTERTAINMENT -> R.string.place_category_entertainment
        PlaceCategory.PARKING -> R.string.place_category_parking
        PlaceCategory.TRANSIT -> R.string.place_category_transit
        PlaceCategory.GAS_STATION -> R.string.place_category_gas_station
        PlaceCategory.SERVICE_AREA -> R.string.place_category_service_area
    }

/** Heads each run of the category picker — see [labelRes] for why the text lives here. */
internal val PlaceCategoryGroup.labelRes: Int
    @StringRes get() = when (this) {
        PlaceCategoryGroup.HOME_PEOPLE -> R.string.place_group_home_people
        PlaceCategoryGroup.ERRANDS -> R.string.place_group_errands
        PlaceCategoryGroup.ROUTINE -> R.string.place_group_routine
        PlaceCategoryGroup.AWAY -> R.string.place_group_away
        PlaceCategoryGroup.TRANSIENT -> R.string.place_group_transient
    }
