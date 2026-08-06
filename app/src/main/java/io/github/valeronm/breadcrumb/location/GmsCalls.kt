package io.github.valeronm.breadcrumb.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/**
 * The two things every Play Services registration in this package needs, kept in one place because
 * both encode a hazard rather than a convenience — and a second copy of either would be a second
 * chance to get it wrong.
 */
object GmsCalls {

    // The tail of the ordered call chain. Shared across every caller and every instance: ordering
    // must hold per-package, not per-object — the transition registration and the departure fence
    // are toggled by the same passes, and GMS gives no guarantee between them either.
    private var lastOp: Task<*> = Tasks.forResult(null)

    /**
     * Run [op] after every Play Services call already queued. GMS processes requests asynchronously
     * with no ordering guarantee, so a disarm's remove landing after a rearm's request (~0.5 s apart
     * on a toggle) unregisters the fresh registration and reports success — silently, which is what
     * makes it worth a mechanism rather than care at each call site.
     *
     * `continueWithTask`, not `onSuccessTask`, so a failed op never wedges the chain.
     */
    @Synchronized
    fun <T> chain(op: () -> Task<T>): Task<T> {
        val next = lastOp.continueWithTask { op() }
        lastOp = next
        return next
    }

    /**
     * A broadcast [PendingIntent] Play Services may fill in. **[PendingIntent.FLAG_MUTABLE] is the
     * point**: the system mutates the intent to attach the result — the transition batch, the
     * geofence event — and an immutable one is delivered empty. Only supported from S, hence the
     * gate; an alarm that carries nothing back wants the immutable variant instead and builds its own.
     */
    inline fun <reified T> broadcastPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, T::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
