package app.locjournal.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.locjournal.LocationJournalApp
import app.locjournal.R
import app.locjournal.container
import app.locjournal.data.AppSettings
import app.locjournal.ui.MainActivity
import app.locjournal.util.TimeUtils
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Always-on foreground service that asks the fused location provider for a fix every
 * [AppSettings.intervalMinutes] minutes and feeds it to [VisitRecorder].
 *
 * A watchdog alarm ([WatchdogReceiver]) additionally requests a one-off fix on the same interval, so
 * a sample still arrives when the provider batches or delays updates while the phone is idle.
 */
class LocationTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fused: FusedLocationProviderClient
    private var settingsJob: Job? = null
    private var activeConfig: Pair<Int, Boolean>? = null
    @Volatile private var lastSampleAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handleLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.appScope.launch { container.settings.setTrackingEnabled(false) }
            WatchdogReceiver.cancel(this)
            stopTracking()
            return START_NOT_STICKY
        }

        if (!Permissions.hasLocation(this)) {
            Log.w(TAG, "No location permission; stopping")
            notifyProblem(this, "Location permission is missing. Open the app to grant it.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Recording your location"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground", e)
            notifyProblem(this, "Tracking could not start in the background. Open the app to resume.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (settingsJob == null) {
            settingsJob = scope.launch {
                container.settings.settings
                    .map { it.intervalMinutes to it.highAccuracy }
                    .distinctUntilChanged()
                    .collect { (interval, high) -> startUpdates(interval, high) }
            }
            scope.launch { updateNotificationFromLatest() }
        }

        if (intent?.action == ACTION_TICK) scope.launch { requestSingleFix() }
        scope.launch { WatchdogReceiver.schedule(this@LocationTrackingService, container.settings.current().intervalMinutes) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(intervalMinutes: Int, highAccuracy: Boolean) {
        if (activeConfig == intervalMinutes to highAccuracy) return
        activeConfig = intervalMinutes to highAccuracy
        val intervalMs = intervalMinutes * 60_000L
        val request = LocationRequest.Builder(priority(highAccuracy), intervalMs)
            // Also accept fixes other apps request, but no more than every few minutes.
            .setMinUpdateIntervalMillis((intervalMs / 3).coerceAtLeast(60_000L))
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.i(TAG, "Location updates every $intervalMinutes min, highAccuracy=$highAccuracy")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission revoked", e)
            notifyProblem(this, "Location permission was revoked. Open the app to grant it.")
            stopTracking()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix() {
        val s = container.settings.current()
        // Skip when a regular update arrived recently.
        if (System.currentTimeMillis() - lastSampleAt < s.intervalMinutes * 60_000L * 2 / 3) return
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(priority(s.highAccuracy))
                .setMaxUpdateAgeMillis(2 * 60_000L)
                .setDurationMillis(60_000L)
                .build()
            val location = fused.getCurrentLocation(request, null).await()
            if (location != null) handleLocation(location)
        } catch (e: Exception) {
            Log.w(TAG, "Single fix failed", e)
        }
    }

    private fun handleLocation(location: Location) {
        lastSampleAt = System.currentTimeMillis()
        scope.launch {
            try {
                container.recorder.onLocation(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null,
                    time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                updateNotificationFromLatest()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record location", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateNotificationFromLatest() {
        val latest = container.db.visitDao().latestTracked() ?: return
        val place = latest.savedPlaceId?.let { container.repo.getPlace(it)?.name }
        val name = latest.title ?: place ?: latest.placeName ?: latest.address?.substringBefore(",") ?: "current location"
        val text = "At $name since ${TimeUtils.formatTime(latest.startTime)}"
        if (Permissions.canPostNotifications(this)) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LocationJournalApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Location Journal is recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop tracking", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        activeConfig = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun priority(high: Boolean) =
        if (high) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    companion object {
        private const val TAG = "TrackingService"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.locjournal.START"
        const val ACTION_STOP = "app.locjournal.STOP"
        const val ACTION_TICK = "app.locjournal.TICK"

        /** Starts the service. Only call while the app is in the foreground or from an exempt context (boot). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP))
        }

        @SuppressLint("MissingPermission")
        fun notifyProblem(context: Context, message: String) {
            if (!Permissions.canPostNotifications(context)) return
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, LocationJournalApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Location tracking paused")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(2, n)
        }
    }
}
