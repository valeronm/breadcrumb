package com.valeronm.activitytracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.valeronm.activitytracker.App
import com.valeronm.activitytracker.R
import com.valeronm.activitytracker.data.ActivityType
import com.valeronm.activitytracker.data.Settings
import com.valeronm.activitytracker.data.TrackRepository
import com.valeronm.activitytracker.data.db.TrackPoint
import com.valeronm.activitytracker.ui.MainActivity
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
    private var lastLocation: Location? = null
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
        startForegroundWithNotification(ActivityType.UNKNOWN.label, "Starting…")
        TrackingStatus.update { it.copy(tracking = true) }

        if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            activityManager.start()
        }
        // Begin recording immediately; transitions will refine the profile from here.
        scope.launch {
            mutex.withLock {
                // Close any track left open by a previous crash/kill before starting a new one.
                repository.finalizeDangling(exceptTrackId = null)
                currentActivity = ActivityType.STILL
                applyActivity(ActivityType.UNKNOWN)
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
        lastLocation = null
        val id = repository.startTrack(activity, now())
        currentTrackId = id
        activeTrackId = id
        withContext(Dispatchers.Main) { startLocationUpdates(activity) }
    }

    private suspend fun closeCurrentTrack() {
        withContext(Dispatchers.Main) { stopLocationUpdates() }
        val id = currentTrackId ?: return
        currentTrackId = null
        activeTrackId = null
        repository.finishTrack(id, now())
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(activity: ActivityType) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        stopLocationUpdates()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, activity.intervalMs)
            .setMinUpdateDistanceMeters(activity.minDistanceM)
            .setMinUpdateIntervalMillis(activity.intervalMs / 2)
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
            lastLocation?.let { distanceMeters += it.distanceTo(loc) }
            lastLocation = loc
            pointCount++
            val point = TrackPoint(
                trackId = trackId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                timestamp = if (loc.time > 0) loc.time else now(),
            )
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
            "%.2f km · %d pts".format(distanceMeters / 1000.0, pointCount)
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

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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

        const val ACTION_START = "com.valeronm.activitytracker.START"
        const val ACTION_STOP = "com.valeronm.activitytracker.STOP"
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
