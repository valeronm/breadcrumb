package io.github.valeronm.breadcrumb.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Formats a distance in metres as a "%.2f km" string — the app's one rendering of track length. */
fun formatKm(meters: Double): String = "%.2f km".format(meters / 1000.0)

/** Whether [permission] is currently granted to this context. */
fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
