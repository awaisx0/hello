package app.locjournal.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val trackingEnabled: Boolean = false,
    /** How often a location is requested. 15 minutes = 4 times per hour. */
    val intervalMinutes: Int = 15,
    /** Moving more than this starts a new entry. */
    val distanceThresholdMeters: Int = 150,
    /** Fixes less accurate than this are ignored. */
    val maxAccuracyMeters: Int = 200,
    val highAccuracy: Boolean = true,
    /** Removes single-sample "jumps" that immediately bounce back (GPS glitches). */
    val filterGlitches: Boolean = true,
    /** Look up place names and addresses from OpenStreetMap. */
    val onlineLookup: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val trackingEnabled = booleanPreferencesKey("tracking_enabled")
        val intervalMinutes = intPreferencesKey("interval_minutes")
        val distanceThreshold = intPreferencesKey("distance_threshold")
        val maxAccuracy = intPreferencesKey("max_accuracy")
        val highAccuracy = booleanPreferencesKey("high_accuracy")
        val filterGlitches = booleanPreferencesKey("filter_glitches")
        val onlineLookup = booleanPreferencesKey("online_lookup")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            trackingEnabled = this[Keys.trackingEnabled] ?: d.trackingEnabled,
            intervalMinutes = this[Keys.intervalMinutes] ?: d.intervalMinutes,
            distanceThresholdMeters = this[Keys.distanceThreshold] ?: d.distanceThresholdMeters,
            maxAccuracyMeters = this[Keys.maxAccuracy] ?: d.maxAccuracyMeters,
            highAccuracy = this[Keys.highAccuracy] ?: d.highAccuracy,
            filterGlitches = this[Keys.filterGlitches] ?: d.filterGlitches,
            onlineLookup = this[Keys.onlineLookup] ?: d.onlineLookup,
        )
    }

    suspend fun setTrackingEnabled(value: Boolean) = context.dataStore.edit { it[Keys.trackingEnabled] = value }
    suspend fun setIntervalMinutes(value: Int) = context.dataStore.edit { it[Keys.intervalMinutes] = value }
    suspend fun setDistanceThreshold(value: Int) = context.dataStore.edit { it[Keys.distanceThreshold] = value }
    suspend fun setMaxAccuracy(value: Int) = context.dataStore.edit { it[Keys.maxAccuracy] = value }
    suspend fun setHighAccuracy(value: Boolean) = context.dataStore.edit { it[Keys.highAccuracy] = value }
    suspend fun setFilterGlitches(value: Boolean) = context.dataStore.edit { it[Keys.filterGlitches] = value }
    suspend fun setOnlineLookup(value: Boolean) = context.dataStore.edit { it[Keys.onlineLookup] = value }
}
