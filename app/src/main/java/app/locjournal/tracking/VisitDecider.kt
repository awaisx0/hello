package app.locjournal.tracking

import app.locjournal.geo.GeoUtils
import kotlin.math.max

/** The parts of a visit that matter when deciding what to do with a new location sample. */
data class VisitSnapshot(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val startTime: Long,
    val endTime: Long,
    val sampleCount: Int,
    val savedPlaceId: Long?,
    val userEdited: Boolean,
)

data class Sample(
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val time: Long,
)

data class DeciderConfig(
    val distanceThresholdMeters: Int,
    val maxAccuracyMeters: Int,
    val filterGlitches: Boolean,
)

sealed interface Decision {
    /** Fix too inaccurate or older than what we already have. */
    data class Ignore(val reason: String) : Decision

    /** Still at the same place: extend the visit, optionally replacing its center with a better fix. */
    data class Extend(val visitId: Long, val refineCenter: Boolean) : Decision

    /** Moved: start a new visit. */
    data object StartNew : Decision

    /**
     * The current visit was a one-off jump (GPS glitch) and we are back at the previous place:
     * delete [glitchVisitId] and extend [previousVisitId].
     */
    data class RevertGlitch(val glitchVisitId: Long, val previousVisitId: Long) : Decision
}

/**
 * Pure decision logic for the tracker, kept free of Android so it can be unit tested.
 */
object VisitDecider {
    /** A stay this long without samples is treated as a new visit even at the same spot. */
    const val MAX_GAP_MS = 12L * 60 * 60 * 1000

    /** Glitch reverts only apply when the jump was this recent. */
    private const val GLITCH_WINDOW_MS = 60L * 60 * 1000

    fun decide(
        sample: Sample,
        current: VisitSnapshot?,
        previous: VisitSnapshot?,
        matchedPlaceId: Long?,
        config: DeciderConfig,
    ): Decision {
        val acc = sample.accuracy
        if (acc != null && acc > config.maxAccuracyMeters) {
            return Decision.Ignore("accuracy ${acc.toInt()} m > ${config.maxAccuracyMeters} m")
        }
        if (current == null) return Decision.StartNew
        if (sample.time < current.endTime) return Decision.Ignore("older than latest sample")
        if (sample.time - current.endTime > MAX_GAP_MS) return Decision.StartNew

        if (isSamePlace(sample, current, matchedPlaceId, config)) {
            val refine = !current.userEdited && current.savedPlaceId == null &&
                acc != null && (current.accuracy == null || acc < current.accuracy)
            return Decision.Extend(current.id, refine)
        }

        if (config.filterGlitches && previous != null &&
            current.sampleCount == 1 && !current.userEdited && current.savedPlaceId == null &&
            sample.time - current.startTime <= GLITCH_WINDOW_MS &&
            isSamePlace(sample, previous, matchedPlaceId, config)
        ) {
            return Decision.RevertGlitch(current.id, previous.id)
        }
        return Decision.StartNew
    }

    fun isSamePlace(sample: Sample, visit: VisitSnapshot, matchedPlaceId: Long?, config: DeciderConfig): Boolean {
        if (matchedPlaceId != null && visit.savedPlaceId != null) {
            return matchedPlaceId == visit.savedPlaceId
        }
        val distance = GeoUtils.distanceMeters(visit.lat, visit.lon, sample.lat, sample.lon)
        // A poor fix can be off by its accuracy radius, so require the move to exceed that too.
        val limit = max(config.distanceThresholdMeters.toDouble(), (sample.accuracy ?: 0f).toDouble())
        return distance <= limit
    }
}
