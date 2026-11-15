package io.github.valeronm.breadcrumb.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A device with no app for [uri] gets [onNoApp] rather than the exception `startActivity` throws. */
internal fun Context.viewUri(uri: Uri, onNoApp: () -> Unit) {
    if (!startQuietly(Intent(Intent.ACTION_VIEW, uri))) onNoApp()
}

/**
 * Starts a screen offered as a way forward, where a missing activity is a crash and nothing
 * happening is the better answer. Returns whether it started.
 */
internal fun Context.startQuietly(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
