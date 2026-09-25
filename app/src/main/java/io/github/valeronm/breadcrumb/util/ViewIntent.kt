package io.github.valeronm.breadcrumb.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Hands [uri] to whichever app views it; a device with none shows [noApp] rather than swallowing
 * the tap.
 */
internal fun Context.viewUri(uri: Uri, @StringRes noApp: Int) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(this, noApp, Toast.LENGTH_SHORT).show() }
}
