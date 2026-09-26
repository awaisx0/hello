package app.locjournal.geo

/** A named place near a location, e.g. a university campus, a cafe or a named building. */
data class PlaceCandidate(
    val name: String,
    /** OSM tag value, e.g. "university", "cafe", "residential". */
    val category: String?,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    /** The location lies inside this place's outline. */
    val enclosing: Boolean,
    /** Bounding box area for enclosing places; smaller means more specific. */
    val areaSqMeters: Double?,
    val kind: Kind,
) {
    enum class Kind { POI, BUILDING, AREA }
}

object PlaceNaming {
    /**
     * Picks a title the way map apps do: the most specific named place you are inside
     * ("Library, University of X"), else a named point of interest right next to you.
     */
    fun pickBestName(candidates: List<PlaceCandidate>): Pair<String, String?>? {
        val enclosing = candidates.filter { it.enclosing }.sortedBy { it.areaSqMeters ?: Double.MAX_VALUE }
        val poiArea = enclosing.firstOrNull { it.kind == PlaceCandidate.Kind.POI }
        val building = enclosing.firstOrNull { it.kind == PlaceCandidate.Kind.BUILDING }
        val nearPoi = candidates
            .filter { !it.enclosing && it.kind == PlaceCandidate.Kind.POI && it.distanceMeters <= 30 }
            .minByOrNull { it.distanceMeters }
        val area = enclosing.firstOrNull { it.kind == PlaceCandidate.Kind.AREA }

        return when {
            poiArea != null && building != null && building.name != poiArea.name &&
                (building.areaSqMeters ?: 0.0) < (poiArea.areaSqMeters ?: Double.MAX_VALUE) ->
                "${building.name}, ${poiArea.name}" to poiArea.category
            poiArea != null -> poiArea.name to poiArea.category
            nearPoi != null -> nearPoi.name to nearPoi.category
            building != null -> building.name to building.category
            area != null -> area.name to area.category
            else -> null
        }
    }
}
