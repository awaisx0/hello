package app.locjournal.geo

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class LookupResult(
    val placeName: String?,
    val placeCategory: String?,
    val address: String?,
    val candidates: List<PlaceCandidate>,
)

data class AddressResult(val displayName: String, val lat: Double, val lon: Double)

/**
 * Resolves coordinates to human-readable place names and addresses, and addresses to coordinates.
 *
 * Uses OpenStreetMap services (no API key): Overpass for named places around/containing the point
 * (universities, malls, parks, named buildings), Nominatim for the address. Falls back to Android's
 * built-in Geocoder when those are unreachable. Requests are serialized and spaced to respect the
 * public servers' usage policies.
 */
class PlaceLookup(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nominatimLock = Mutex()
    private var lastNominatimCall = 0L
    private val userAgent = "LocationJournal/1.0 (Android; personal location diary)"

    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    private val poiKeys = listOf(
        "amenity", "shop", "tourism", "leisure", "office", "healthcare", "historic",
        "craft", "aeroway", "railway", "public_transport", "sport", "club", "man_made",
    )

    suspend fun lookup(lat: Double, lon: Double): LookupResult = withContext(Dispatchers.IO) {
        val candidates = runCatching { nearbyCandidates(lat, lon, 80) }
            .onFailure { Log.w(TAG, "Overpass lookup failed", it) }
            .getOrDefault(emptyList())
        val nominatim = runCatching { nominatimReverse(lat, lon) }
            .onFailure { Log.w(TAG, "Nominatim reverse failed", it) }
            .getOrNull()
        val geocoderAddress = if (nominatim?.address == null) runCatching { geocoderReverse(lat, lon) }.getOrNull() else null

        val best = PlaceNaming.pickBestName(candidates)
        LookupResult(
            placeName = best?.first ?: nominatim?.name ?: geocoderAddress?.featureName?.takeIf { isMeaningfulName(it) },
            placeCategory = best?.second ?: nominatim?.category,
            address = nominatim?.address ?: geocoderAddress?.let { formatAddress(it) },
            candidates = candidates,
        )
    }

    /** Named places containing the point plus named points of interest within [radius] meters. */
    suspend fun nearbyCandidates(lat: Double, lon: Double, radius: Int = 150): List<PlaceCandidate> =
        withContext(Dispatchers.IO) {
            val poiRegex = poiKeys.joinToString("|")
            val query = """
                [out:json][timeout:20];
                is_in($lat,$lon)->.a;
                (
                  way(pivot.a)[name][!boundary][!place][!highway][!natural][!route][!waterway];
                  relation(pivot.a)[name][!boundary][!place][!highway][!natural][!route][!waterway];
                );
                out tags bb;
                node(around:$radius,$lat,$lon)[name][~"^($poiRegex)$"~"."];
                out 60;
                way(around:$radius,$lat,$lon)[name][~"^($poiRegex|building)$"~"."];
                out tags center 30;
            """.trimIndent()
            var lastError: Exception? = null
            for (endpoint in overpassEndpoints) {
                try {
                    val body = httpPost(endpoint, "data=" + URLEncoder.encode(query, "UTF-8"))
                    return@withContext parseOverpass(body, lat, lon)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No Overpass endpoint")
        }

    private fun parseOverpass(body: String, lat: Double, lon: Double): List<PlaceCandidate> {
        val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
        val seen = HashMap<String, PlaceCandidate>()
        for (el in elements) {
            val obj = el.jsonObject
            val tags = obj["tags"]?.jsonObject ?: continue
            val name = tags.str("name")?.takeIf { it.isNotBlank() } ?: continue
            val key = "${obj.str("type")}/${obj.str("id")}"
            val bounds = obj["bounds"]?.jsonObject
            val center = obj["center"]?.jsonObject
            val kind = when {
                poiKeys.any { tags.str(it) != null } -> PlaceCandidate.Kind.POI
                tags.str("building") != null -> PlaceCandidate.Kind.BUILDING
                else -> PlaceCandidate.Kind.AREA
            }
            val category = poiKeys.firstNotNullOfOrNull { tags.str(it) }
                ?: tags.str("building")?.let { if (it == "yes") "building" else it }
                ?: tags.str("landuse")

            val candidate = if (bounds != null) {
                val minLat = bounds.dbl("minlat") ?: continue
                val minLon = bounds.dbl("minlon") ?: continue
                val maxLat = bounds.dbl("maxlat") ?: continue
                val maxLon = bounds.dbl("maxlon") ?: continue
                val cLat = (minLat + maxLat) / 2
                val cLon = (minLon + maxLon) / 2
                val h = GeoUtils.distanceMeters(minLat, cLon, maxLat, cLon)
                val w = GeoUtils.distanceMeters(cLat, minLon, cLat, maxLon)
                PlaceCandidate(name, category, cLat, cLon, GeoUtils.distanceMeters(lat, lon, cLat, cLon), true, h * w, kind)
            } else {
                val pLat = center?.dbl("lat") ?: obj.dbl("lat") ?: continue
                val pLon = center?.dbl("lon") ?: obj.dbl("lon") ?: continue
                PlaceCandidate(name, category, pLat, pLon, GeoUtils.distanceMeters(lat, lon, pLat, pLon), false, null, kind)
            }
            val existing = seen[key]
            if (existing == null || (!existing.enclosing && candidate.enclosing)) seen[key] = candidate
        }
        return seen.values.sortedWith(
            compareByDescending<PlaceCandidate> { it.enclosing }
                .thenBy { if (it.enclosing) it.areaSqMeters ?: Double.MAX_VALUE else it.distanceMeters },
        )
    }

    private data class NominatimReverse(val name: String?, val category: String?, val address: String?)

    private suspend fun nominatimReverse(lat: Double, lon: Double): NominatimReverse {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1" +
            "&lat=$lat&lon=$lon&accept-language=${Locale.getDefault().toLanguageTag()}"
        val obj = json.parseToJsonElement(nominatimGet(url)).jsonObject
        // Street names and admin areas are not places you "are at"; they belong in the address.
        val category = obj.str("category")
        val name = obj.str("name")
            ?.takeIf { it.isNotBlank() && isMeaningfulName(it) && category !in setOf("highway", "place", "boundary") }
        return NominatimReverse(
            name = name,
            category = obj.str("type"),
            address = obj.str("display_name"),
        )
    }

    /** Address or place search, biased towards [near] when given. */
    suspend fun searchAddress(query: String, near: LatLon?): List<AddressResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AddressResult>()
        runCatching {
            val viewbox = near?.let {
                val d = 0.5
                "&viewbox=${it.lon - d},${it.lat + d},${it.lon + d},${it.lat - d}"
            } ?: ""
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8$viewbox" +
                "&accept-language=${Locale.getDefault().toLanguageTag()}&q=" + URLEncoder.encode(query, "UTF-8")
            val arr: JsonArray = json.parseToJsonElement(nominatimGet(url)).jsonArray
            for (el in arr) {
                val o = el.jsonObject
                val la = o.str("lat")?.toDoubleOrNull() ?: continue
                val lo = o.str("lon")?.toDoubleOrNull() ?: continue
                results += AddressResult(o.str("display_name") ?: query, la, lo)
            }
        }.onFailure { Log.w(TAG, "Nominatim search failed", it) }

        if (results.isEmpty()) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context).getFromLocationName(query, 5)?.forEach { a ->
                    results += AddressResult(formatAddress(a), a.latitude, a.longitude)
                }
            }.onFailure { Log.w(TAG, "Geocoder search failed", it) }
        }
        results
    }

    /** Address only (no POI search), used when naming saved places. */
    suspend fun reverseAddress(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching { nominatimReverse(lat, lon).address }.getOrNull()
            ?: runCatching { geocoderReverse(lat, lon)?.let { formatAddress(it) } }.getOrNull()
    }

    private fun geocoderReverse(lat: Double, lon: Double): Address? {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        return Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()
    }

    private fun formatAddress(a: Address): String =
        (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
            .ifBlank { listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.countryName).joinToString(", ") }

    /** Filters out "names" that are really just house numbers or plus codes. */
    private fun isMeaningfulName(name: String): Boolean =
        name.any { it.isLetter() } && !Regex("^[0-9A-Z]{4}\\+[0-9A-Z]{2,3}").containsMatchIn(name)

    private suspend fun nominatimGet(url: String): String = nominatimLock.withLock {
        // Nominatim usage policy: at most one request per second.
        val wait = 1100 - (System.currentTimeMillis() - lastNominatimCall)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try {
            httpGet(url)
        } finally {
            lastNominatimCall = System.currentTimeMillis()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return conn.run {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            try {
                if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode for $url")
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    private fun httpPost(url: String, form: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return conn.run {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            try {
                outputStream.use { it.write(form.toByteArray()) }
                if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode for $url")
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    companion object {
        private const val TAG = "PlaceLookup"
    }
}
