package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.valeronm.breadcrumb.App
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.ui.MainActivity
import io.github.valeronm.breadcrumb.util.formatKm
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service that records GPS while the app is in the background. It listens for activity
 * changes (delivered via [ActivityTransitionReceiver]) and adjusts the GPS sampling profile, or
 * pauses recording entirely while the user is stationary.
 */
class LocationRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private lateinit var repository: TrackRepository
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var activityManager: ActivityRecognitionManager

    @Volatile private var currentActivity = ActivityType.STILL
    @Volatile private var currentTrackId: Long? = null
    @Volatile private var distanceMeters = 0.0
    @Volatile private var pointCount = 0
    // Last point accepted as a good fix — the baseline for distance and the bad-fix jump check.
    private var lastGoodPoint: TrackPoint? = null
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = TrackRepository(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        activityManager = ActivityRecognitionManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            // Null intent = the system restarted us (START_STICKY) after process death.
            // Resume only if auto-recording is still armed; otherwise shut down cleanly.
            else -> if (Settings.isAutoRecord(this)) handleStart() else stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart() {
        startForegroundWithNotification(ActivityType.STILL.label, "Waiting for movement…")
        TrackingStatus.update { it.copy(tracking = true) }

        // Start armed but paused — recording begins when a moving activity transition arrives.
        // (Don't optimistically open a track: while stationary it would just be created and
        // immediately discarded, flashing the UI.)
        scope.launch {
            mutex.withLock {
                // Close any track left open by a previous crash/kill, but never the one we're
                // actively recording (a snapshot may have already opened it).
                repository.finalizeDangling(exceptTrackId = activeTrackId)
                currentActivity = ActivityType.STILL
                publishStatus()
            }
            // Arm activity recognition only after the paused state is established. Doing it before
            // lets the one-shot snapshot's applyActivity() race this block on the mutex; if the
            // snapshot won, it would open a track that finalizeDangling then deleted and
            // currentActivity = STILL then reset — wedging the recorder while GPS kept running.
            withContext(Dispatchers.Main) {
                if (isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    activityManager.start()
                    // One-shot: if we're already moving right now, start recording without waiting
                    // for the next transition.
                    activityManager.requestSnapshot()
                }
            }
        }
    }

    private fun handleStop() {
        activityManager.stop()
        scope.launch {
            mutex.withLock {
                closeCurrentTrack()
                currentActivity = ActivityType.STILL
            }
            withContext(Dispatchers.Main) {
                TrackingStatus.reset()
                ServiceCompat.stopForeground(this@LocationRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Called by [ActivityTransitionReceiver] when Play Services reports a new activity. */
    fun onActivityChanged(activity: ActivityType) {
        scope.launch { mutex.withLock { applyActivity(activity) } }
    }

    private suspend fun applyActivity(activity: ActivityType) {
        if (activity == currentActivity) return
        closeCurrentTrack()
        currentActivity = activity
        if (activity.recording) {
            openTrack(activity)
        }
        publishStatus()
    }

    private suspend fun openTrack(activity: ActivityType) {
        distanceMeters = 0.0
        pointCount = 0
        lastGoodPoint = null
        val id = repository.startTrack(activity, now())
        currentTrackId = id
        activeTrackId = id
        withContext(Dispatchers.Main) { startLocationUpdates() }
    }

    private suspend fun closeCurrentTrack() {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        val id = currentTrackId ?: return
        currentTrackId = null
        activeTrackId = null
        repository.finishTrack(id, now())
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val intervalMs = Settings.minIntervalSec(this) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(Settings.minDistanceM(this).toFloat())
            // Don't let fixes arrive faster than the user's chosen minimum interval.
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = handleLocations(result.locations)
        }
        locationCallback = callback
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fused.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun handleLocations(locations: List<Location>) {
        val trackId = currentTrackId ?: return
        for (loc in locations) {
            val candidate = TrackPoint(
                trackId = trackId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                timestamp = if (loc.time > 0) loc.time else now(),
            )
            // Bad fixes are still stored, just excluded from distance and the good-point baseline.
            val bad = TrackQuality.isBadFix(lastGoodPoint, candidate, currentActivity)
            val point = candidate.copy(ignored = bad)
            if (!bad) {
                lastGoodPoint?.let { distanceMeters += TrackQuality.distanceMeters(it, point) }
                lastGoodPoint = point
                pointCount++
            }
            val distanceSnapshot = distanceMeters
            scope.launch {
                repository.addPoint(point)
                repository.updateDistance(trackId, distanceSnapshot)
            }
        }
        publishStatus()
    }

    private fun publishStatus() {
        val activity = currentActivity
        TrackingStatus.update {
            it.copy(
                tracking = true,
                activityLabel = activity.label,
                recording = activity.recording,
                distanceMeters = if (activity.recording) distanceMeters else 0.0,
                points = if (activity.recording) pointCount else 0,
            )
        }
        val detail = if (activity.recording) {
            "${formatKm(distanceMeters)} · $pointCount pts"
        } else {
            "Paused — waiting for movement"
        }
        updateNotification(activity.label, detail)
    }

    // --- Notifications -------------------------------------------------------

    private fun startForegroundWithNotification(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title, text), type)
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    // The notification's PendingIntents never change, so build them once and reuse across the
    // per-fix notification rebuilds (each getActivity/getService is a round-trip to the system).
    private val openIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, LocationRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("Tracking: $title")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }


    private fun now() = System.currentTimeMillis()

    override fun onDestroy() {
        stopLocationUpdates()
        instance = null
        activeTrackId = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var instance: LocationRecordingService? = null
            private set

        /** True while the service is alive in this process. */
        val isRunning: Boolean get() = instance != null

        /** Id of the track currently being recorded, or null. Used to skip it during cleanup. */
        @Volatile
        var activeTrackId: Long? = null
            private set

        const val ACTION_START = "io.github.valeronm.breadcrumb.START"
        const val ACTION_STOP = "io.github.valeronm.breadcrumb.STOP"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            Settings.setAutoRecord(context, true)
            val intent = Intent(context, LocationRecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            Settings.setAutoRecord(context, false)
            val intent = Intent(context, LocationRecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
