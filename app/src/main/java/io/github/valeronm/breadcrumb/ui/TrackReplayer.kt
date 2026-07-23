package io.github.valeronm.breadcrumb.ui

import android.content.Context
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * DEBUG: replays a stored track through the live "current track" view at accelerated speed, so the
 * live UI (growing map line, directional pointer, ticking stats) can be exercised without actually
 * moving. Publishes the same shape the recorder feeds the UI — a [TrackingStatus.State] plus the
 * points recorded so far — but through its own flow, leaving the real recorder untouched.
 */
object TrackReplayer {

    class Replay(
        val trackLabel: String,
        val speedX: Int,
        val status: TrackingStatus.State,
        val points: List<TrackPoint>,
    )

    private val _state = MutableStateFlow<Replay?>(null)

    /** Non-null while a replay is running; the Record tab renders it instead of the live state. */
    val state: StateFlow<Replay?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Replays [trackId]'s good points, compressing recorded gaps by [speedX]. */
    fun start(context: Context, trackId: Long, speedX: Int = 30) {
        stop()
        val appContext = context.applicationContext
        job = scope.launch {
            val repository = TrackRepository(appContext)
            val track = repository.track(trackId) ?: return@launch
            val points = repository.pointsFor(trackId)
            if (points.size < 2) return@launch
            val activity = ActivityType.ofName(track.activityType) ?: ActivityType.UNKNOWN
            val label = ActivityType.labelFor(track.activityType)
            // The recorder's accumulator, replayed: same walk, so the replayed distance matches what
            // the card showed live and what the track row stores.
            val accumulator = TrackStats.Accumulator()
            for (i in points.indices) {
                val point = points[i]
                accumulator.add(point)
                val elapsedMs = point.timestamp - points.first().timestamp
                _state.value = Replay(
                    trackLabel = label,
                    speedX = speedX,
                    status = TrackingStatus.State(
                        tracking = true,
                        activity = activity,
                        recording = true,
                        distanceMeters = accumulator.distanceMeters,
                        points = accumulator.pointCount,
                        // Back-dated so the UI's wall-clock duration shows the track's own elapsed time.
                        startedAtMillis = System.currentTimeMillis() - elapsedMs,
                        speedMps = point.speed,
                        altitudeM = point.altitude,
                    ),
                    points = points.subList(0, i + 1),
                )
                if (i < points.lastIndex) {
                    // Cap huge recorded gaps (pauses) so a replay never stalls.
                    val gapMs = (points[i + 1].timestamp - point.timestamp).coerceIn(0, 60_000)
                    delay(gapMs / speedX)
                }
            }
            // Hold the finished state briefly, then dismiss.
            delay(3_000)
            _state.value = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = null
    }
}
