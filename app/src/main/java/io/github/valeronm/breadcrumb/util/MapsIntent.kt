package io.github.valeronm.breadcrumb.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import java.util.Locale

/**
 * Shows [lat]/[lon] as a dropped pin in whatever maps app the device has, titled [label] when the
 * point has a name.
 *
 * A `geo:` view is the one map action every maps app answers, and it needs the point twice: the
 * scheme's own coordinate aims the camera, while the `q=` copy is what actually drops a marker
 * there — without it the area opens with nothing pinned. Coordinates are formatted in [Locale.US]
 * because the URI grammar wants a decimal point whatever the phone's locale would print.
 */
internal fun Context.openInMaps(lat: Double, lon: Double, label: String? = null) {
    val point = "%.6f,%.6f".format(Locale.US, lat, lon)
    val query = if (label.isNullOrBlank()) point else "$point(${Uri.encode(label)})"
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, "geo:$point?q=$query".toUri())) }
        .onFailure {
            // A device with no maps app at all (bare emulator images have none) would otherwise
            // just swallow the tap.
            Toast.makeText(this, "No maps app to open this in", Toast.LENGTH_SHORT).show()
        }
}
