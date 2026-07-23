package io.github.valeronm.breadcrumb.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.util.DebugLog

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

    private fun transitionPendingIntent() =
        broadcastPendingIntent(ActivityTransitionReceiver.ACTION_TRANSITION, REQUEST_TRANSITION)

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
    @SuppressLint("MissingPermission")
    fun start(): Task<Void> =
        chain { client.requestActivityTransitionUpdates(buildRequest(), transitionPendingIntent()) }
            .addOnSuccessListener {
                lastRegisteredAtMs = System.currentTimeMillis()
                DebugLog.i(TAG, "transition updates registered")
            }
            .addOnFailureListener { DebugLog.e(TAG, "transition updates registration FAILED: ${it.message}") }

    @SuppressLint("MissingPermission")
    fun stop() {
        DebugLog.i(TAG, "removing transition updates")
        val pi = transitionPendingIntent()
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
     * Full re-registration on a fresh PendingIntent token: remove + cancel ([stop]), settle, then
     * register.
     *
     * A registration can stop delivering live transitions while still reporting success, answering
     * snapshots and replaying on re-registration, so there is no client-side tell — inferring it is
     * what the service's deafness oracle is for. Cancelling before re-requesting at least mints a
     * fresh token rather than reusing the one GMS already holds.
     *
     * Treat that as hygiene, not a cure. Re-registration is not a reliable recovery path: a
     * registration on a request code GMS had never seen has been observed coming up dead while a
     * second install recovered on a code it had reused, and the state responsible sits in Play
     * Services rather than in anything this app owns — on the device it cleared only on a reboot.
     * Nothing here should be built as though restarting fixes deafness.
     *
     * The settle only spaces consecutive GMS calls; nothing depends on its duration.
     */
    fun restart(): Task<Void> {
        stop()
        chain<Void> { delayed(REGISTER_SETTLE_MS) }
        return start()
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
        private const val REQUEST_TRANSITION = 4711
        private const val REQUEST_SNAPSHOT = 4712
        private const val REGISTER_SETTLE_MS = 2_000L
        private const val TAG = "Breadcrumb"

        /**
         * When a registration last reached GMS. Stamped on success rather than at the call, since
         * [restart] enqueues a remove and a settle first — consumers time a replay window against
         * this, and an early stamp would let a replay pass as a live delivery.
         */
        @Volatile
        var lastRegisteredAtMs = 0L
            private set

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
