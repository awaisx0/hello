package app.locjournal.tracking

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.locjournal.container
import kotlinx.coroutines.launch

object Permissions {
    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasFineLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Restarts tracking after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                if (context.container.settings.current().trackingEnabled && Permissions.hasLocation(context)) {
                    try {
                        LocationTrackingService.start(context)
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Could not restart tracking", e)
                        LocationTrackingService.notifyProblem(context, "Tracking could not restart. Open the app to resume.")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Fires every tracking interval (also while the phone is idle) and asks the service for a fresh fix.
 * If the service was killed, it tries to restart it and tells you when Android doesn't allow that.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                val settings = context.container.settings.current()
                if (!settings.trackingEnabled) return@launch
                schedule(context, settings.intervalMinutes)
                val tick = Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_TICK)
                try {
                    // Works while the service is alive (the app then counts as foreground).
                    context.startService(tick)
                } catch (e: Exception) {
                    try {
                        ContextCompat.startForegroundService(context, tick)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Could not restart tracking service", e2)
                        LocationTrackingService.notifyProblem(
                            context,
                            "Android stopped location tracking. Tap to open the app and resume it.",
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Watchdog"

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 10, Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun schedule(context: Context, intervalMinutes: Int) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMinutes * 60_000L,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
