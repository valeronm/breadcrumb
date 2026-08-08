package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.LOCATION_PERMISSIONS
import io.github.valeronm.breadcrumb.util.activityRecognitionGranted
import io.github.valeronm.breadcrumb.util.backgroundGranted
import io.github.valeronm.breadcrumb.util.isBatteryOptimizationIgnored
import io.github.valeronm.breadcrumb.util.locationGranted
import io.github.valeronm.breadcrumb.util.notificationsGranted
import io.github.valeronm.breadcrumb.util.permanentlyDenied
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/**
 * One thing recording needs from Android before it works: what it is, and why the recorder wants it.
 * A step per *reason*, not per dialog — location and activity recognition arrive in one breath from
 * the platform's point of view and are two different bargains from the reader's, and a row that
 * argues for two things at once is a row that can only be refused as one.
 *
 * The glyph is the step's own subject rather than a checklist tick: only unmet steps are ever shown,
 * so a mark saying so would be the same mark on every row.
 */
internal enum class SetupStep(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    /**
     * Location, **including all the time** — one requirement, because in system settings it is one
     * switch: "Allow all the time" and "Allow only while using the app" are two positions of the
     * same control, and a reader shown two rows for it is shown the same thing twice. Android just
     * refuses to take the second question until the first is answered, so this is asked in two
     * goes — see [bodyRes] and [SetupState.progressOf].
     */
    LOCATION(R.string.setup_location_title, Icons.Filled.MyLocation),
    ACTIVITY(R.string.setup_activity_title, Icons.AutoMirrored.Filled.DirectionsWalk),
    NOTIFICATIONS(R.string.setup_notifications_title, Icons.Filled.Notifications),
    BATTERY(R.string.setup_battery_title, Icons.Filled.BatteryFull),
}

/**
 * What the row says, which for location depends on how far the one switch has got: the whole
 * bargain up front, so nobody is told halfway through that more is wanted than they agreed to, and
 * then the upgrade alone once it is the only part outstanding.
 */
@StringRes
internal fun SetupStep.bodyRes(state: SetupState): Int = when (this) {
    SetupStep.LOCATION ->
        if (state.locationOk) R.string.setup_location_body_all_time else R.string.setup_location_body

    SetupStep.ACTIVITY -> R.string.setup_activity_body
    SetupStep.NOTIFICATIONS -> R.string.setup_notifications_body
    SetupStep.BATTERY -> R.string.setup_battery_body
}

/** The runtime permissions the *next* ask covers; the battery exemption is not one, and is empty. */
internal fun SetupStep.permissions(state: SetupState): List<String> = when (this) {
    SetupStep.LOCATION ->
        if (state.locationOk) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else LOCATION_PERMISSIONS

    SetupStep.ACTIVITY -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
    SetupStep.NOTIFICATIONS -> listOf(Manifest.permission.POST_NOTIFICATIONS)
    SetupStep.BATTERY -> emptyList()
}

/**
 * Which of [SetupLadder]'s two signals the *next* ask will answer on: the resume after it, rather
 * than a permission dialog's result. Named for the answer and not for leaving the app, because the
 * two are not the same — from Android 11 the all-the-time request does leave, and still reports back
 * through the launcher.
 *
 * The ladder must not confuse them: a resume fires for reasons that have nothing to do with a run,
 * while a dialog's result fires exactly once, for exactly the thing that was asked — which is also
 * why the ladder settles on what was true when it asked rather than when the answer lands.
 */
private fun SetupStep.answersOnResume(state: SetupState): Boolean =
    !asksThroughDialog || this in state.blocked

/**
 * Whether this step is asked for through the permission system at all — which reports back through
 * the launcher whatever it chose to put on screen: a dialog for plain location, and from Android 11
 * its own per-app location page for the all-the-time half. The battery exemption is a plain activity
 * of someone else's, with nothing to answer through.
 *
 * Separate from [answersOnResume] and deliberately blind to [SetupState.blocked], which is derived
 * from it: a step Android has stopped taking questions about is asked for by opening settings, so it
 * answers on a resume *because* it is blocked, and reading blocked-ness here would be a circle.
 */
private val SetupStep.asksThroughDialog: Boolean
    get() = when (this) {
        SetupStep.BATTERY -> false
        SetupStep.LOCATION, SetupStep.ACTIVITY, SetupStep.NOTIFICATIONS -> true
    }

/**
 * Which requirements this device has, which are met, and which can no longer be asked for.
 *
 * Derived from the platform and never stored, so it is right whenever it is read: a permission
 * revoked long after setup is simply unmet again, with nothing to invalidate.
 *
 * `@Immutable` because it is: every property is a `val` computed at construction. Without it the
 * `List` and `Set` properties make Compose infer the whole class unstable, and every composable
 * taking one stops being skippable.
 */
@Immutable
internal data class SetupState(
    val locationOk: Boolean,
    val backgroundOk: Boolean,
    val activityOk: Boolean,
    val notificationsOk: Boolean,
    val batteryOk: Boolean,
    /** Steps Android will put no more dialogs up for — only its settings page can turn these on. */
    val blocked: Set<SetupStep> = emptySet(),
) {
    fun isMet(step: SetupStep): Boolean = when (step) {
        // Both positions of the one switch. Below Android 10 the second answers true for good, so
        // this reduces to the first without a version check of its own.
        SetupStep.LOCATION -> locationOk && backgroundOk
        SetupStep.ACTIVITY -> activityOk
        SetupStep.NOTIFICATIONS -> notificationsOk
        SetupStep.BATTERY -> batteryOk
    }

    /**
     * How far a step has got. [SetupLadder] ends a run on an ask that moved *nothing*, which is not
     * the same as one that left the step unmet: location granted for the foreground is real
     * progress towards a step that still isn't met, and the run has to carry on to the second half.
     */
    fun progressOf(step: SetupStep): Int = when (step) {
        SetupStep.LOCATION -> (if (locationOk) 1 else 0) + (if (backgroundOk) 1 else 0)
        else -> if (isMet(step)) 1 else 0
    }

    /**
     * Every step over the whole enum, with no version gate of its own: where the platform predates
     * a permission its query in `util/Permissions.kt` answers true for good, so the step reads as
     * met and drops out here. Repeating those API levels would be the same threshold stated in two
     * files with nothing relating them.
     */
    val unmet: List<SetupStep> = SetupStep.entries.filterNot(::isMet)

    /**
     * Nothing left to ask for, which is also the whole condition for arming the recorder — there is
     * no narrower "enough to record" beside it.
     *
     * Two of these could technically be refused and still leave a recording running: the foreground
     * service starts without `POST_NOTIFICATIONS` (Android only hides the notification), and the
     * platform does not check the battery exemption up front. Both are required anyway, because the
     * alternative is worse to live with than to explain — a recorder that arms and then dies in the
     * background hours later, or one running with nothing on screen to say so, is a failure the
     * reader meets long after the moment they could have understood it. One list, one meaning of
     * ready, and the toggle either works or names what is missing.
     */
    val complete: Boolean = unmet.isEmpty()
}

/** Reads every requirement's current state off the platform. */
internal fun setupState(context: Context): SetupState {
    val state = SetupState(
        locationOk = context.locationGranted(),
        backgroundOk = context.backgroundGranted(),
        activityOk = context.activityRecognitionGranted(),
        notificationsOk = context.notificationsGranted(),
        batteryOk = context.isBatteryOptimizationIgnored(),
    )
    // The rationale query needs the activity, and a step that answers on a resume has no dialog to
    // be refused twice in the first place. Narrowed to the unmet steps first, so a settled install
    // makes no rationale calls at all.
    //
    // Logged rather than shrugged off: without an activity nothing is ever blocked, every such step
    // is asked for with a dialog that will not appear, and the toggle silently does nothing — a
    // failure indistinguishable from "no permission was ever refused twice" unless it says so here.
    val activity = context as? Activity
    if (activity == null) {
        DebugLog.w("Breadcrumb", "setup state read off a non-activity context; blocked steps unknown")
        return state
    }
    val askable = state.unmet.filter { it.asksThroughDialog }
    if (askable.isEmpty()) return state
    val asked = AppSettings.askedPermissions(context)
    return state.copy(
        blocked = askable
            .filter { step -> step.permissions(state).any { activity.permanentlyDenied(it, asked) } }
            .toSet(),
    )
}

/**
 * Something the app itself has to say before it hands the reader to Android, and the fact that it is
 * on screen. One type for both cases rather than a flag each: a resume arriving while any of these
 * is up is not a run's answer — the reader has not left yet — and a rule that has to name every
 * prompt separately is one a third prompt can be added without.
 */
internal sealed interface SetupPrompt {
    /** The prominent disclosure, carrying the very permissions its confirm will ask for. */
    data class AllTimeLocation(val permissions: List<String>) : SetupPrompt

    /** Why a step offers only settings, for a run where no card is on screen to have said it. */
    data class Blocked(val step: SetupStep) : SetupPrompt
}

/**
 * The run of asks behind a single "start recording": every unmet requirement in turn, each at the
 * moment the one before it was granted.
 *
 * This is what makes the requests *in context* — the reader asked to record, and each dialog is
 * part of answering that, rather than a toll collected at first launch before they have said what
 * they want. **The run ends on an ask that moved nothing** — a refusal is an answer, and asking the
 * next thing on top of it is how a permission flow turns into nagging. Moved nothing rather than
 * left the step unmet, because location is granted in two goes and is still unmet after the first:
 * that ask moved something, so the run carries on and puts the second half up. What is left over is
 * shown by [SetupCard], whose own buttons ask for a single step and start no run.
 */
internal class SetupLadder {
    /** The step waiting on the reader, or null when no run is in progress. */
    var current by mutableStateOf<SetupStep?>(null)
        private set

    // Both captured when the ask goes out, never re-derived from the answer: granting the first
    // half of location changes which signal the *next* ask would answer on, and a run settling
    // against that would discard the answer it was waiting for.
    private var awaitingResume = false
    private var askedProgress = 0

    /** Starts a run at the first unmet requirement; a complete setup starts nothing. */
    fun start(state: SetupState, ask: (SetupStep) -> Unit) = advance(state, ask)

    /** A permission dialog has answered. Ignored for a step whose answer comes back another way. */
    fun onDialogAnswered(state: SetupState, ask: (SetupStep) -> Unit) {
        val asked = current ?: return
        if (!awaitingResume) settle(asked, state, ask)
    }

    /** The app is in front again after a step that left it (system settings, the battery dialog). */
    fun onResumed(state: SetupState, ask: (SetupStep) -> Unit) {
        val asked = current ?: return
        if (awaitingResume) settle(asked, state, ask)
    }

    /** Ends the run without asking for anything more. */
    fun cancel() {
        current = null
    }

    private fun settle(asked: SetupStep, state: SetupState, ask: (SetupStep) -> Unit) {
        if (state.progressOf(asked) > askedProgress) advance(state, ask) else current = null
    }

    private fun advance(state: SetupState, ask: (SetupStep) -> Unit) {
        val next = state.unmet.firstOrNull()
        current = next
        if (next == null) return
        awaitingResume = next.answersOnResume(state)
        askedProgress = state.progressOf(next)
        ask(next)
    }
}

/**
 * Said before any all-the-time location request, from every path that makes one — the ladder, where
 * no row is on screen to carry it, and the card's own button alike, so the disclosure cannot go
 * missing by the route taken. It names the app, what is collected, that it happens with the app
 * closed, and what becomes of it, which is what a prominent disclosure is required to say.
 */
@Composable
internal fun BackgroundLocationDisclosure(onContinue: () -> Unit, onDismiss: () -> Unit) {
    // The last thing on screen before the platform's own asking takes over, and the only place the
    // reader is told what to pick there — the card behind it is covered, and in a ladder run there
    // is no card at all.
    ConfirmDialog(
        // The location item's own glyph, because this is that item asking: the row it stands in
        // front of carries the same one, and a second symbol here would read as a second subject.
        icon = SetupStep.LOCATION.icon,
        title = stringResource(R.string.setup_background_title),
        text = stringResource(R.string.setup_background_disclosure, allTimeOptionLabel()),
        confirmLabel = stringResource(R.string.setup_allow),
        onConfirm = onContinue,
        onDismiss = onDismiss,
    )
}

/**
 * Android's own name for the all-the-time option, which is the one the reader is about to look for.
 *
 * Asked of the platform rather than translated here: the label is the system's to word, it is
 * already in the device's language, and it has changed between versions — a copy in this app's
 * strings is a quotation that can go stale or, worse, be translated to something that appears
 * nowhere on the screen it describes. The app's own wording stands in only below Android 11, where
 * there is nothing to ask, and where a device returns nothing.
 */
@Composable
private fun allTimeOptionLabel(): String {
    val fallback = stringResource(R.string.setup_all_time_option)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
    val context = LocalContext.current
    val label = remember(context) {
        context.packageManager.backgroundPermissionOptionLabel.toString()
    }
    return label.ifBlank { fallback }
}

/**
 * Shown when a ladder run reaches a step Android has stopped taking questions about — the row's own
 * words, because during a run no row is on screen, over the one action left. Being dropped into
 * system settings with nothing said is a jump the reader cannot act on: they arrive not knowing
 * which switch was wanted or why the ordinary button was not offered.
 *
 * Not shown from the card's button, where both texts are already visible above it — there the tap
 * goes straight through. This dialog exists to *supply* that context, not to repeat it.
 */
@Composable
internal fun BlockedStepDialog(
    step: SetupStep,
    @StringRes bodyRes: Int,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        icon = step.icon,
        title = stringResource(step.titleRes),
        // The two the card stacks, in its order: why the requirement exists, then why the ordinary
        // button is gone. Neither is a fragment of the other — each is a whole text that translates
        // as a unit, and only the break between them is this surface's doing.
        text = stringResource(bodyRes) + "\n\n" + stringResource(R.string.setup_step_blocked),
        confirmLabel = stringResource(R.string.setup_open_settings),
        onConfirm = onOpenSettings,
        onDismiss = onDismiss,
    )
}

/**
 * Everything still owed, on the Record tab and nowhere else: one card in the slot the live map and
 * the totals fill, a row per unmet requirement carrying its reason and its own button.
 *
 * Deliberately the *only* setup surface there is. The detail listed here is what a page behind a
 * tap would have carried, and showing a summary that opens a screen showing the same list is one
 * presentation too many — so the card holds the detail and there is no screen. It also
 * lists only what is unmet: a satisfied requirement is not news, and a row that reports one is a
 * row the reader has to read past to find the one they can act on.
 *
 * **Sized by what it holds.** With one requirement left, a card stretched down the tab is mostly
 * empty and its line of text floats in the middle of it, which reads as a rendering fault rather
 * than as one small thing outstanding. Taking no weight, it wraps; the scroll inside it caps that at
 * the space the caller has left, so a full list still fits without pushing the row below it
 * off the screen. The slack under it belongs to the caller.
 */
@Composable
internal fun SetupCard(
    state: SetupState,
    onGrant: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            for (step in state.unmet) {
                SetupStepRow(
                    step = step,
                    bodyRes = step.bodyRes(state),
                    blocked = step in state.blocked,
                    onGrant = { onGrant(step) },
                )
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    step: SetupStep,
    @StringRes bodyRes: Int,
    blocked: Boolean,
    onGrant: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        IconDisc(
            icon = step.icon,
            style = DiscStyle.tonal(MaterialTheme.colorScheme.onSurfaceVariant),
            contentDescription = stringResource(R.string.setup_step_missing),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(step.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Location's own "here is what to pick" belongs to the disclosure, which stands between
            // this button and the request — saying it here as well would put it on screen twice for
            // one grant.
            if (blocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.setup_step_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            // One action either way — asking for a blocked step *is* opening settings, and that
            // holds whether this button did the asking or the recorder's toggle did. Only the label
            // differs, because only the label is about what happens next.
            Button(onClick = onGrant) {
                Text(
                    stringResource(
                        if (blocked) R.string.setup_open_settings else R.string.setup_allow,
                    ),
                )
            }
        }
    }
}
