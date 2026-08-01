package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStarted
import io.github.valeronm.breadcrumb.util.APP_LOCK_AUTHENTICATORS
import io.github.valeronm.breadcrumb.util.canAuthenticate
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/**
 * The app lock's live state, and the two settings the activity shell acts on before `MainScreen`
 * exists to hold them.
 *
 * Process-scoped, for a different reason in each half. The unlocked flag has to outlive an
 * activity recreation — held in a `remember`, every rotation would re-prompt — while dying with
 * the process, which rules out `rememberSaveable`, since that is restored from the task snapshot
 * after a kill and would hand back an unlocked app. The two settings are mirrored here because the
 * screen that writes them is a sub-page reached through the overlay stack, while the code that
 * acts on them — the gate and the window flag — sits above `MainScreen`; threading a boolean
 * between the two would touch every signature in between. Settings no higher layer reads, the
 * grace period among them, stay in [AppSettings] and are read where they are used.
 *
 * None of this defends against an attacker with root or adb, and it isn't meant to: the database
 * is readable to anyone who can reach the app's private directory either way. What it defends is
 * an unlocked phone in someone else's hands.
 */
internal object Privacy {

    var lockEnabled by mutableStateOf(false)
        private set

    var blockScreenshots by mutableStateOf(false)
        private set

    /** Live state rather than a setting: whether the user has authenticated in this process. */
    var unlocked by mutableStateOf(false)
        private set

    /** Elapsed-realtime stamp of the moment the app was last backgrounded while unlocked. */
    private var leftAt: Long? = null

    /** Call before composing; writing this state from inside composition would be a write to
     *  snapshot state during a read of it. */
    fun load(context: Context) {
        lockEnabled = AppSettings.appLock(context)
        blockScreenshots = AppSettings.blockScreenshots(context)
    }

    /** The one answer to "is the app locked", so that everything the gate draws over asks the
     *  same question it does. */
    fun isLocked(context: Context): Boolean =
        lockEnabled && !unlocked && context.canAuthenticate()

    fun setLockEnabled(context: Context, enabled: Boolean) {
        AppSettings.setAppLock(context, enabled)
        lockEnabled = enabled
        // Whoever just flipped this switch is demonstrably present, so turning the lock on must
        // not throw up a prompt over the screen they turned it on from.
        if (enabled) unlocked = true
    }

    fun setBlockScreenshots(context: Context, enabled: Boolean) {
        AppSettings.setBlockScreenshots(context, enabled)
        blockScreenshots = enabled
    }

    fun markUnlocked() {
        unlocked = true
        leftAt = null
    }

    fun onStopped(elapsedRealtime: Long) {
        if (unlocked) leftAt = elapsedRealtime
    }

    fun onStarted(elapsedRealtime: Long, graceSec: Int) {
        val away = leftAt ?: return
        leftAt = null
        if (elapsedRealtime - away >= graceSec * 1000L) unlocked = false
    }
}

/**
 * Draws [content] and, while the app is locked, an opaque screen over it.
 *
 * Over rather than instead: replacing the content would tear down the whole composition on every
 * lock, so a call taken mid-journey would return the user to an empty Record tab with their open
 * track and scroll position gone. The cost is that the content keeps composing behind the lock,
 * which is why the pending-import effect in `MainScreen` asks [Privacy.isLocked] rather than
 * trusting the gate to hold it back.
 *
 * The recents thumbnail is deliberately *not* this composable's problem. Re-locking is evaluated
 * on the way back in, since the grace period is measured from the moment the app was left — so at
 * the moment the system snapshots the window the app is still unlocked, and hiding that is what
 * the separate screenshot setting is for.
 */
@Composable
internal fun PrivacyGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    Privacy.onStarted(
                        SystemClock.elapsedRealtime(),
                        AppSettings.appLockGraceSec(context),
                    )

                Lifecycle.Event.ON_STOP -> Privacy.onStopped(SystemClock.elapsedRealtime())
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A host without a FragmentActivity can raise no prompt, and a lock screen whose prompt can
    // never be raised is a trap with the history behind it. Nothing but MainActivity composes
    // this, so the null branch is unreachable by construction.
    val activity = LocalActivity.current as? FragmentActivity

    Box(Modifier.fillMaxSize()) {
        content()
        if (activity != null && Privacy.isLocked(context)) LockScreen(activity)
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var prompting by remember { mutableStateOf(true) }

    LaunchedEffect(prompting) {
        if (!prompting) return@LaunchedEffect
        // The prompt hosts itself in a fragment, and committing that transaction before the
        // activity is started throws. On a cold launch this composes from onCreate.
        lifecycleOwner.lifecycle.withStarted {}
        authenticate(
            activity,
            onSuccess = { Privacy.markUnlocked() },
            onDismissed = { prompting = false },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Breadcrumb is locked",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (!prompting) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = { prompting = true }) { Text("Unlock") }
            }
        }
    }
}

private fun authenticate(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onDismissed: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess()

            // Deliberately no onAuthenticationFailed override: an unrecognized finger is the
            // system prompt's own business and it stays up. Only an error or a cancel ends it.
            override fun onAuthenticationError(code: Int, message: CharSequence) = onDismissed()
        },
    )
    prompt.authenticate(promptInfo())
}

private fun promptInfo(): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Breadcrumb")
        .setSubtitle("Your location history is locked")
        // Face and iris are passive, so the prompt asks for a confirming tap by default — right
        // for authorizing a payment, friction for opening a viewer that is read-only until the
        // user acts. Fingerprint is unaffected: an active modality never had the extra tap.
        .setConfirmationRequired(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // No negative button may be set alongside DEVICE_CREDENTIAL — the credential fallback is
        // the negative button.
        builder.setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
    } else {
        // Below API 30 `setAllowedAuthenticators` has no support for device credentials, so this
        // deprecated call is the only way to offer the PIN — which has to be offered, or a user
        // with no enrolled fingerprint could never get in.
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    return builder.build()
}
