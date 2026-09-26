package app.locjournal.data

import androidx.room.withTransaction
import app.locjournal.geo.PlaceArea
import app.locjournal.geo.PlaceMatcher
import kotlinx.coroutines.flow.Flow

/** All reads and writes of visits, labels and saved places go through here. */
class JournalRepository(private val db: AppDatabase) {
    private val visits = db.visitDao()
    private val tags = db.tagDao()
    private val places = db.savedPlaceDao()

    // ---- Visits ----

    fun observeRange(from: Long, to: Long): Flow<List<VisitWithDetails>> = visits.observeRange(from, to)
    suspend fun getRange(from: Long, to: Long): List<VisitWithDetails> = visits.getRange(from, to)
    fun observeVisit(id: Long): Flow<VisitWithDetails?> = visits.observeDetails(id)
    suspend fun getVisit(id: Long): Visit? = visits.getById(id)
    fun observeLatestTracked(): Flow<Visit?> = visits.observeLatestTracked()
    fun observeTopPurposes(limit: Int = 12): Flow<List<PurposeCount>> = visits.observeTopPurposes(limit)

    /** Inserts or updates [visit] and replaces its labels. Returns the visit id. */
    suspend fun saveVisit(visit: Visit, tagIds: Collection<Long>): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = if (visit.id == 0L) {
            visits.insert(visit.copy(createdAt = now, updatedAt = now))
        } else {
            visits.update(visit.copy(updatedAt = now))
            visit.id
        }
        tags.clearVisit(id)
        tagIds.forEach { tags.addToVisit(VisitTagCrossRef(id, it)) }
        id
    }

    suspend fun deleteVisit(id: Long) = visits.deleteById(id)

    // ---- Labels ----

    fun observeTags(): Flow<List<Tag>> = tags.observeAll()
    suspend fun getTags(): List<Tag> = tags.getAll()

    /** Returns the existing label with this name (case-insensitive) or creates it. */
    suspend fun getOrCreateTag(name: String, color: Int? = null): Tag {
        val trimmed = name.trim()
        tags.findByName(trimmed)?.let { return it }
        val tag = Tag(name = trimmed, color = color ?: TagColors.forName(trimmed))
        val id = tags.insert(tag)
        return if (id > 0) tag.copy(id = id) else tags.findByName(trimmed)!!
    }

    suspend fun updateTag(tag: Tag) = tags.update(tag)
    suspend fun deleteTag(tag: Tag) = tags.delete(tag)
    suspend fun tagUsage(tagId: Long) = tags.usageCount(tagId)

    // ---- Saved places ----

    fun observePlaces(): Flow<List<SavedPlace>> = places.observeAll()
    suspend fun getPlaces(): List<SavedPlace> = places.getAll()
    suspend fun getPlace(id: Long): SavedPlace? = places.getById(id)
    suspend fun visitCountForPlace(id: Long): Int = visits.countForPlace(id)

    suspend fun savePlace(place: SavedPlace): Long =
        if (place.id == 0L) places.insert(place) else {
            places.update(place); place.id
        }

    suspend fun deletePlace(place: SavedPlace) = places.delete(place)

    fun matchPlace(all: List<SavedPlace>, lat: Double, lon: Double, accuracy: Float?): SavedPlace? =
        PlaceMatcher.match(all, lat, lon, accuracy) { PlaceArea(it.id, it.latitude, it.longitude, it.radiusMeters) }

    /**
     * Re-runs saved-place matching over existing entries so a place you add later also labels your history.
     * Entries with a title you typed yourself keep it; the place's default labels and purpose are added
     * where missing. Returns how many entries were linked.
     */
    suspend fun applySavedPlacesToHistory(): Int = db.withTransaction {
        val all = places.getAll()
        if (all.isEmpty()) return@withTransaction 0
        var changed = 0
        for (v in visits.getAll()) {
            val match = matchPlace(all, v.latitude, v.longitude, v.accuracy) ?: continue
            if (v.savedPlaceId == match.id) continue
            visits.update(
                v.copy(
                    savedPlaceId = match.id,
                    purpose = v.purpose?.takeIf { it.isNotBlank() } ?: match.defaultPurpose,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            for (tagName in match.defaultTags) {
                tags.addToVisit(VisitTagCrossRef(v.id, getOrCreateTag(tagName).id))
            }
            changed++
        }
        changed
    }
}

object TagColors {
    val palette = listOf(
        0xFF1E88E5, 0xFF43A047, 0xFFE53935, 0xFFFB8C00, 0xFF8E24AA,
        0xFF00897B, 0xFF6D4C41, 0xFFD81B60, 0xFF3949AB, 0xFF7CB342,
    ).map { it.toInt() }

    fun forName(name: String): Int = palette[Math.floorMod(name.lowercase().hashCode(), palette.size)]
}
