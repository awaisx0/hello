package app.locjournal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.locjournal.data.AppDatabase
import app.locjournal.data.JournalRepository
import app.locjournal.data.SettingsRepository
import app.locjournal.geo.PlaceLookup
import app.locjournal.io.BackupManager
import app.locjournal.tracking.VisitRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import java.io.File

/** Simple manual dependency container. */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val repo = JournalRepository(db)
    val placeLookup = PlaceLookup(context)
    val recorder = VisitRecorder(context, db, repo, settings, placeLookup)
    val backup = BackupManager(context, db, repo)

    /** For work that must outlive a screen or the service (e.g. saving a setting while stopping). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class LocationJournalApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        Configuration.getInstance().apply {
            load(this@LocationJournalApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Location tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your location journal is recording"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Tracking problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when tracking stopped and needs your attention"
            },
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val Context.container: AppContainer get() = (applicationContext as LocationJournalApp).container
