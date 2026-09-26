package app.locjournal.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room.withTransaction
import app.locjournal.data.AppDatabase
import app.locjournal.data.JournalRepository
import app.locjournal.data.SettingsRepository
import app.locjournal.data.Visit
import app.locjournal.data.VisitTagCrossRef
import app.locjournal.geo.PlaceLookup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns raw location samples into visits: extends the current visit while you stay put and starts
 * a new one when you move beyond the distance threshold.
 */
class VisitRecorder(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: JournalRepository,
    private val settings: SettingsRepository,
    private val lookup: PlaceLookup,
) {
    private val mutex = Mutex()
    private val visitDao = db.visitDao()
    private val tagDao = db.tagDao()

    /** Records a sample. Returns the visit the sample was attributed to, or null if ignored. */
    suspend fun onLocation(lat: Double, lon: Double, accuracy: Float?, time: Long): Visit? {
        val s = settings.current()
        val result = mutex.withLock {
            db.withTransaction {
                val current = visitDao.latestTracked()
                val previous = current?.let { visitDao.latestTrackedExcept(it.id) }
                val places = repo.getPlaces()
                val matched = repo.matchPlace(places, lat, lon, accuracy)
                val sample = Sample(lat, lon, accuracy, time)
                val decision = VisitDecider.decide(
                    sample = sample,
                    current = current?.snapshot(),
                    previous = previous?.snapshot(),
                    matchedPlaceId = matched?.id,
                    config = DeciderConfig(s.distanceThresholdMeters, s.maxAccuracyMeters, s.filterGlitches),
                )
                Log.d(TAG, "Sample $lat,$lon ±$accuracy -> $decision")
                when (decision) {
                    is Decision.Ignore -> null
                    is Decision.Extend -> {
                        val v = current!!
                        val updated = v.copy(
                            endTime = time,
                            sampleCount = v.sampleCount + 1,
                            latitude = if (decision.refineCenter) lat else v.latitude,
                            longitude = if (decision.refineCenter) lon else v.longitude,
                            accuracy = if (decision.refineCenter) accuracy else v.accuracy,
                            savedPlaceId = v.savedPlaceId ?: matched?.id,
                        )
                        visitDao.update(updated)
                        updated
                    }
                    is Decision.RevertGlitch -> {
                        visitDao.deleteById(decision.glitchVisitId)
                        val prev = previous!!
                        val updated = prev.copy(endTime = time, sampleCount = prev.sampleCount + 1)
                        visitDao.update(updated)
                        updated
                    }
                    Decision.StartNew -> {
                        val now = System.currentTimeMillis()
                        val visit = Visit(
                            latitude = lat,
                            longitude = lon,
                            accuracy = accuracy,
                            startTime = time,
                            endTime = time,
                            savedPlaceId = matched?.id,
                            purpose = matched?.defaultPurpose,
                            lookupDone = !s.onlineLookup,
                            createdAt = now,
                            updatedAt = now,
                        )
                        val id = visitDao.insert(visit)
                        matched?.defaultTags?.forEach { name ->
                            tagDao.addToVisit(VisitTagCrossRef(id, repo.getOrCreateTag(name).id))
                        }
                        visit.copy(id = id)
                    }
                }
            }
        }
        if (s.onlineLookup) processPendingLookups()
        return result
    }

    /** Fills in place names/addresses for visits that don't have them yet (retries when back online). */
    suspend fun processPendingLookups(limit: Int = 3) {
        if (!isOnline()) return
        for (v in visitDao.pendingLookups(limit)) {
            refreshPlaceInfo(v.id)
        }
    }

    /** Looks up the place name and address for one visit. Returns false if the lookup failed. */
    suspend fun refreshPlaceInfo(visitId: Long): Boolean {
        val v = visitDao.getById(visitId) ?: return false
        return try {
            val r = lookup.lookup(v.latitude, v.longitude)
            if (r.placeName == null && r.address == null) return false
            mutex.withLock {
                // Re-read in case the tracker or the user changed it in the meantime.
                val fresh = visitDao.getById(visitId) ?: return@withLock
                visitDao.update(
                    fresh.copy(
                        placeName = r.placeName ?: fresh.placeName,
                        placeCategory = r.placeCategory ?: fresh.placeCategory,
                        address = r.address ?: fresh.address,
                        lookupDone = true,
                    ),
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed for visit $visitId", e)
            false
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun Visit.snapshot() = VisitSnapshot(
        id = id,
        lat = latitude,
        lon = longitude,
        accuracy = accuracy,
        startTime = startTime,
        endTime = endTime,
        sampleCount = sampleCount,
        savedPlaceId = savedPlaceId,
        userEdited = userEdited,
    )

    companion object {
        private const val TAG = "VisitRecorder"
    }
}
