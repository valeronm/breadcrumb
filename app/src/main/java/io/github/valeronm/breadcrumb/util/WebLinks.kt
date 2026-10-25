package io.github.valeronm.breadcrumb.util

import android.content.Context
import androidx.core.net.toUri
import io.github.valeronm.breadcrumb.BuildConfig

internal object WebLinks {
    const val SITE = "https://breadcrumb.place/"

    // Play's listing points at this page as the privacy policy, so its URL is fixed.
    const val PRIVACY_POLICY = "https://breadcrumb.place/privacy/"
    const val VIEWER = "https://breadcrumb.place/viewer/"
    const val SOURCE = "https://github.com/valeronm/breadcrumb"
    const val PLAY = "https://play.google.com/store/apps/details?id=${BuildConfig.PLAY_PACKAGE}"

    // The basemap's link, as its style's own attribution carries it.
    const val PROTOMAPS = "https://github.com/protomaps/basemaps"

    // The OpenStreetMap data credit's links, in the form FOSSGIS's terms ask for.
    const val OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright"
    const val ODBL = "https://opendatacommons.org/licenses/odbl/"
    const val OSM_FIX_THE_MAP = "https://www.openstreetmap.org/fixthemap"

    // The place-name atlas's credit links.
    const val GEONAMES = "https://www.geonames.org/"
    const val CC_BY_4 = "https://creativecommons.org/licenses/by/4.0/"

    const val FOSSGIS_ROUTING = "https://routing.openstreetmap.de/"
    const val PHOTON = "https://photon.komoot.io/"
}

internal fun Context.openInBrowser(url: String, onNoApp: () -> Unit) = viewUri(url.toUri(), onNoApp)
