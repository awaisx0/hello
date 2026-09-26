package app.locjournal.geo

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun formatCoords(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.1f km", meters / 1000)

    /** First two comma-separated parts of a long address ("12 Main St, Springfield"). */
    fun shortAddress(address: String): String {
        val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.take(2).joinToString(", ").ifEmpty { address }
    }
}

data class LatLon(val lat: Double, val lon: Double)

/** Minimal view of a saved place, for matching without Android/Room types. */
data class PlaceArea(val id: Long, val lat: Double, val lon: Double, val radiusMeters: Float)

object PlaceMatcher {
    /** Extra slack for GPS drift, capped so a terrible fix can't match everything. */
    private const val MAX_ACCURACY_SLACK_M = 75f

    /**
     * Returns the saved place containing the point, allowing for GPS drift ("roughly" matching).
     * When several places overlap, the one whose center is relatively closest wins.
     */
    fun <T> match(places: List<T>, lat: Double, lon: Double, accuracy: Float?, area: (T) -> PlaceArea): T? {
        val slack = min(accuracy ?: 0f, MAX_ACCURACY_SLACK_M)
        return places
            .map { p ->
                val a = area(p)
                Triple(p, GeoUtils.distanceMeters(a.lat, a.lon, lat, lon), a.radiusMeters)
            }
            .filter { (_, d, r) -> d <= r + slack }
            .minByOrNull { (_, d, r) -> d / r.coerceAtLeast(1f) }
            ?.first
    }
}
