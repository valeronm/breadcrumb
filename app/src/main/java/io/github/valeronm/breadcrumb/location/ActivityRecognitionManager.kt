package io.github.valeronm.breadcrumb.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.valeronm.breadcrumb.util.DebugLog
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.Settings
import kotlin.random.Random

/**
 * Registers/unregisters Activity Transition updates with Google Play Services. Transitions are
 * delivered to [ActivityTransitionReceiver] via a broadcast [PendingIntent].
 *
 * All Play Services calls go through [chain]. GMS processes them asynchronously with no ordering
 * guarantee, so a disarm's remove landing after a rearm's request — they are ~0.5s apart on a
 * toggle — unregisters the fresh registration, and reports success while doing it.
 */
class ActivityRecognitionManager(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)

    private fun broadcastPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).apply {
            this.action = action
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system mutates the intent to attach activity results.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * A first code for an installation that has none. Random rather than a fixed base because
     * settings are not in the backup set: a reinstall clears the stored code while GMS's entry from
     * the previous installation can outlive it (see [restart]), and starting at a constant would
     * retrace the old installation's codes and adopt a stranded entry.
     */
    private fun mintRequestCode() = Random.nextInt(REQUEST_TRANSITION_BASE, MAX_SEED)

    /** The code [start] should register on, minted and stored if the installation has none yet. */
    private fun currentRequestCode(): Int =
        Settings.arRequestCode(context)
            ?: mintRequestCode().also { Settings.setArRequestCode(context, it) }

    private fun transitionPendingIntent(requestCode: Int) =
        broadcastPendingIntent(ActivityTransitionReceiver.ACTION_TRANSITION, requestCode)

    private fun snapshotPendingIntent() =
        broadcastPendingIntent(ActivityTransitionReceiver.ACTION_SNAPSHOT, REQUEST_SNAPSHOT)

    private fun buildRequest(): ActivityTransitionRequest {
        val transitions = ArrayList<ActivityTransition>()
        for (activity in ActivityType.TRACKED_DETECTED_ACTIVITIES) {
            transitions.add(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
            )
            // Also watch EXIT of a moving activity — it's an early "stopped" signal, often slightly
            // ahead of ENTER STILL. STILL has no useful EXIT (it doesn't say what you started).
            if (activity != DetectedActivity.STILL) {
                transitions.add(
                    ActivityTransition.Builder()
                        .setActivityType(activity)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .build(),
                )
            }
        }
        return ActivityTransitionRequest(transitions)
    }

    /**
     * Caller must hold ACTIVITY_RECOGNITION; the service checks before invoking. Returns the
     * chained registration task so a broadcast-driven caller can hold its wakelock until the
     * request has reached GMS.
     */
    fun start(): Task<Void> = synchronized(opLock) { start(currentRequestCode()) }

    @SuppressLint("MissingPermission")
    private fun start(requestCode: Int): Task<Void> =
        chain {
            client.requestActivityTransitionUpdates(
                buildRequest(), transitionPendingIntent(requestCode),
            )
        }
            .addOnSuccessListener { DebugLog.i(TAG, "transition updates registered") }
            .addOnFailureListener { DebugLog.e(TAG, "transition updates registration FAILED: ${it.message}") }

    /** Disarm: drop the live registration, if this installation ever made one. */
    fun stop() {
        synchronized(opLock) { Settings.arRequestCode(context)?.let(::removeRegistration) }
    }

    @SuppressLint("MissingPermission")
    private fun removeRegistration(requestCode: Int) {
        DebugLog.i(TAG, "removing transition updates on $requestCode")
        val pi = transitionPendingIntent(requestCode)
        chain { client.removeActivityTransitionUpdates(pi) }
        // The documented deregistration recipe cancels the PendingIntent after removal; a cancel
        // makes the next getBroadcast(FLAG_UPDATE_CURRENT) mint a fresh token instead of reusing
        // the one GMS already holds. Chained (not a success listener) so it can't race a rearm's
        // getBroadcast and cancel the new token instead of the old one.
        chain {
            DebugLog.i(TAG, "cancelling transition PendingIntent")
            pi.cancel()
            Tasks.forResult(null as Void?)
        }
    }

    /**
     * Full re-registration on a request code no registration has used before: remove + cancel the
     * retiring one, settle, then register.
     *
     * A registration can stop delivering live transitions while still reporting success, answering
     * snapshots and replaying on re-registration, so there is no client-side tell — inferring it is
     * what the service's deafness oracle is for. Advancing the code keeps a re-registration from
     * resolving to whatever GMS still holds for an older one, since it keys its entry by (request
     * code, intent identity).
     *
     * Treat that as hygiene, not a cure. A fresh code is not known to revive a registration that
     * has stopped delivering — one has been seen registering successfully and receiving nothing
     * while a second install recovered on a code it had reused — and the state responsible appears
     * to sit in Play Services rather than in anything this app owns. Re-registration is not a
     * reliable recovery path, and nothing here should be built as though it were.
     *
     * The settle only spaces consecutive GMS calls; nothing depends on its duration.
     */
    fun restart(): Task<Void> = synchronized(opLock) {
        clearLegacyRegistration()
        // Codes only advance; a stranded entry can be neither repaired nor detected, so reusing one
        // risks adopting it. No stored code means nothing was ever registered — nothing to retire.
        val retiring = Settings.arRequestCode(context)
        val fresh = retiring?.plus(1) ?: mintRequestCode()
        DebugLog.i(TAG, "re-registering on request code $fresh (retiring $retiring)")
        retiring?.let(::removeRegistration)
        Settings.setArRequestCode(context, fresh)
        chain<Void> { delayed(REGISTER_SETTLE_MS) }
        start(fresh)
    }

    /**
     * One-time removal of the registration that builds predating per-installation codes left on
     * the fixed [REQUEST_TRANSITION_BASE]. Once an install has its own code nothing else names that
     * one, so it would keep delivering until the app is replaced.
     *
     * Runs from [restart] rather than the usual `App.onCreate` slot because only an armed install
     * can be holding such a registration — a disarm removes and cancels it — and a package
     * replacement re-arms on its own. Idempotent, so a crash before the flag write only costs a
     * repeat. Delete this, its flag, and [REQUEST_TRANSITION_BASE]'s legacy role once the installed
     * base has run it.
     */
    private fun clearLegacyRegistration() {
        if (Settings.isLegacyArCodeClearDone(context)) return
        DebugLog.i(TAG, "clearing legacy transition registration")
        removeRegistration(REQUEST_TRANSITION_BASE)
        Settings.setLegacyArCodeClearDone(context)
    }

    /** A Task that completes [delayMs] after being created; spaces chained GMS calls. */
    private fun delayed(delayMs: Long): Task<Void> {
        val source = TaskCompletionSource<Void>()
        Handler(Looper.getMainLooper()).postDelayed({ source.setResult(null) }, delayMs)
        return source.task
    }

    /**
     * Requests a one-shot snapshot of the current activity so recording can start immediately if
     * the user is already moving when they arm. The receiver removes it after the first result.
     */
    @SuppressLint("MissingPermission")
    fun requestSnapshot() {
        chain { client.requestActivityUpdates(0L, snapshotPendingIntent()) }
            .addOnFailureListener { DebugLog.e(TAG, "snapshot request FAILED: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun removeSnapshot() {
        chain { client.removeActivityUpdates(snapshotPendingIntent()) }
    }

    companion object {
        // Range a fresh installation's first code is drawn from (see [currentRequestCode]); the
        // ceiling leaves room for a lifetime of advances without overflowing. Doubles as the fixed
        // code every build predating per-installation codes registered on, which
        // [clearLegacyRegistration] removes once per install.
        private const val REQUEST_TRANSITION_BASE = 4711
        private const val MAX_SEED = Int.MAX_VALUE / 2

        // Shares the range above, harmlessly — a different intent action makes it a different token.
        private const val REQUEST_SNAPSHOT = 4712
        private const val REGISTER_SETTLE_MS = 2_000L
        private const val TAG = "Breadcrumb"

        // Guards each public operation's read-modify-write of the stored code together with the
        // chain enqueues acting on it. Arm calls [restart] on the main thread while the deafness
        // oracle calls it from an IO coroutine, and neither holds the service's mutex — interleaved,
        // both would read the same code and one of the registrations they create would be left with
        // nothing naming it. Deliberately not the [chain] monitor: this span includes a blocking
        // settings commit, which would stall every unrelated GMS enqueue behind it.
        private val opLock = Any()

        // The tail of the ordered GMS-call chain. Shared across instances (the receiver creates
        // its own manager for removeSnapshot) — ordering must hold per-package, not per-instance.
        private var lastOp: Task<*> = Tasks.forResult(null)

        // continueWithTask (not onSuccessTask) so a failed op never wedges the chain.
        @Synchronized
        private fun <T> chain(op: () -> Task<T>): Task<T> {
            val next = lastOp.continueWithTask { op() }
            lastOp = next
            return next
        }
    }
}
