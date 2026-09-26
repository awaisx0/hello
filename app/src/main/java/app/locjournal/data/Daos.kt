package app.locjournal.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Insert
    suspend fun insert(visit: Visit): Long

    @Update
    suspend fun update(visit: Visit)

    @Delete
    suspend fun delete(visit: Visit)

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun getById(id: Long): Visit?

    @Query("SELECT * FROM visits WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): Visit?

    @Transaction
    @Query("SELECT * FROM visits WHERE id = :id")
    fun observeDetails(id: Long): Flow<VisitWithDetails?>

    /** Visits that overlap [from, to). */
    @Transaction
    @Query("SELECT * FROM visits WHERE startTime < :to AND endTime >= :from ORDER BY startTime")
    fun observeRange(from: Long, to: Long): Flow<List<VisitWithDetails>>

    @Transaction
    @Query("SELECT * FROM visits WHERE startTime < :to AND endTime >= :from ORDER BY startTime")
    suspend fun getRange(from: Long, to: Long): List<VisitWithDetails>

    @Transaction
    @Query("SELECT * FROM visits ORDER BY startTime")
    suspend fun getAllDetails(): List<VisitWithDetails>

    @Query("SELECT * FROM visits ORDER BY startTime")
    suspend fun getAll(): List<Visit>

    /** The visit the tracker is currently extending. Manual entries are ignored. */
    @Query("SELECT * FROM visits WHERE isManual = 0 ORDER BY startTime DESC, id DESC LIMIT 1")
    suspend fun latestTracked(): Visit?

    @Query("SELECT * FROM visits WHERE isManual = 0 ORDER BY startTime DESC, id DESC LIMIT 1")
    fun observeLatestTracked(): Flow<Visit?>

    @Query("SELECT * FROM visits WHERE isManual = 0 AND id != :excludeId ORDER BY startTime DESC, id DESC LIMIT 1")
    suspend fun latestTrackedExcept(excludeId: Long): Visit?

    @Query("SELECT * FROM visits WHERE lookupDone = 0 ORDER BY startTime DESC LIMIT :limit")
    suspend fun pendingLookups(limit: Int): List<Visit>

    @Query(
        "SELECT purpose, COUNT(*) AS cnt FROM visits WHERE purpose IS NOT NULL AND purpose != '' " +
            "GROUP BY purpose ORDER BY cnt DESC LIMIT :limit",
    )
    fun observeTopPurposes(limit: Int): Flow<List<PurposeCount>>

    @Query("SELECT COUNT(*) FROM visits WHERE savedPlaceId = :placeId")
    suspend fun countForPlace(placeId: Long): Int
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Tag>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToVisit(ref: VisitTagCrossRef)

    @Query("DELETE FROM visit_tags WHERE visitId = :visitId")
    suspend fun clearVisit(visitId: Long)

    @Query("SELECT COUNT(*) FROM visit_tags WHERE tagId = :tagId")
    suspend fun usageCount(tagId: Long): Int
}

@Dao
interface SavedPlaceDao {
    @Insert
    suspend fun insert(place: SavedPlace): Long

    @Update
    suspend fun update(place: SavedPlace)

    @Delete
    suspend fun delete(place: SavedPlace)

    @Query("SELECT * FROM saved_places ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SavedPlace>>

    @Query("SELECT * FROM saved_places ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SavedPlace>

    @Query("SELECT * FROM saved_places WHERE id = :id")
    suspend fun getById(id: Long): SavedPlace?

    @Query("SELECT * FROM saved_places WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): SavedPlace?
}
