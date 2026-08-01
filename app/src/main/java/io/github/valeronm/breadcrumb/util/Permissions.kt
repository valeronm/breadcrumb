package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Everything the arming flow requests up front; background location is its own later step. */
internal fun foregroundPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

internal fun Context.foregroundGranted(): Boolean = foregroundPermissions().all { isGranted(it) }

internal fun Context.backgroundGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

/**
 * What the app lock accepts. The capability query below and the prompt that later asks for it must
 * name the same set: a check answering for authenticators the prompt doesn't request is exactly the
 * "lockout with no recovery" [canAuthenticate] exists to prevent.
 */
internal const val APP_LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * Whether this device can satisfy an app-lock prompt at all. A lock the device cannot open is a
 * lockout with no recovery — the history would sit behind a prompt that always errors — so both
 * the settings switch and the gate itself consult this, and the gate stands aside when it says no.
 *
 * The two branches are one question asked the only way each API level answers it: below API 30
 * `canAuthenticate` has no answer for device credentials, which is also why the prompt falls back
 * to the deprecated builder call there.
 */
internal fun Context.canAuthenticate(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.from(this).canAuthenticate(APP_LOCK_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } else {
        getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

internal fun Context.isBatteryOptimizationIgnored(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

@Suppress("BatteryLife")
internal fun Context.requestIgnoreBatteryOptimization() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }.onFailure {
        // Some OEMs don't expose the direct dialog; fall back to the settings list.
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
