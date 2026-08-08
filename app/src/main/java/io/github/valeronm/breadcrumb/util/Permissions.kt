package io.github.valeronm.breadcrumb.util

import android.Manifest
import android.app.Activity
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
import androidx.core.app.ActivityCompat

/**
 * What recording asks Android for, one entry per thing the user is actually deciding — each is a
 * separate reason, so each is a separate ask and a separate row on the setup card. The pair below is
 * one decision: the platform shows precise and approximate as a single dialog. A list rather than
 * the array a launcher takes, because it is read far more often than it is requested.
 */
internal val LOCATION_PERMISSIONS = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

internal fun Context.locationGranted(): Boolean = LOCATION_PERMISSIONS.all { isGranted(it) }

/**
 * The queries below answer true where the platform has no such permission to grant, so a caller
 * never has to ask the version question twice — and none of them has to. What a *screen* shows
 * follows from that: a step the platform predates reads as met and drops out of the unmet list on
 * its own, which is why `SetupState` states no API levels of its own.
 */
internal fun Context.activityRecognitionGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        isGranted(Manifest.permission.ACTIVITY_RECOGNITION)

internal fun Context.notificationsGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        isGranted(Manifest.permission.POST_NOTIFICATIONS)

internal fun Context.backgroundGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

/**
 * Whether the recording service may start. More than a location grant: location is a while-in-use
 * permission, so from Android 14 starting a location-type foreground service while the app is in
 * the background throws SecurityException unless location is granted *all the time* — a
 * while-in-use grant passes a plain permission check and still crashes the start. Most of this
 * service's starts are background ones (boot, the watchdog's self-heal, a sticky restart after
 * the process died), so the all-the-time grant is required everywhere rather than per caller.
 * Below Android 10 the background half answers true for good, like its neighbours above.
 *
 * `any`, where [locationGranted] wants both: the platform's start check is satisfied by either
 * grade of location, and this predicate states what the platform requires, not what setup asks for.
 */
internal fun Context.canStartLocationService(): Boolean =
    LOCATION_PERMISSIONS.any { isGranted(it) } && backgroundGranted()

/**
 * Whether Android will no longer put a dialog up for [permission] — it stops after the second
 * refusal, and from then on only the app's settings page can turn it on. A button still offering to
 * request it is a button that does nothing at all, with nothing on screen to say why.
 *
 * [asked] is the set of permissions this install has actually requested (`Settings.askedPermissions`)
 * and it is not optional bookkeeping: `shouldShowRequestPermissionRationale` answers false both here
 * and for a permission never asked for, so without it the two are indistinguishable.
 */
internal fun Activity.permanentlyDenied(permission: String, asked: Set<String>): Boolean =
    permission in asked &&
        !isGranted(permission) &&
        !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

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

/**
 * The app's own page in system settings, and **as deep as an app can link into the permission UI**.
 * This is where a reader is sent for a permission refused twice, which is the one case the platform
 * will put nothing up for — the all-the-time half is *requested* like any other and Android opens
 * its own location page in reply, so it does not come through here.
 *
 * Do not try to deep-link past it. The permission controller does expose entry points for one app's
 * permission (`MANAGE_APP_PERMISSION`) and its permission list (`MANAGE_APP_PERMISSIONS`), and both
 * *resolve* from a normal app — but starting either is refused with "requires
 * android.permission.GRANT_RUNTIME_PERMISSIONS", a signature permission no ordinary app can hold.
 * They resolve and then deny, so a resolve check reads as success and the failure only shows up as
 * a `SecurityException` at launch.
 *
 * Swallows a missing activity like its neighbour above: this is offered as a way forward, and a
 * crash is a worse answer than nothing happening.
 */
internal fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
