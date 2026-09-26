package app.locjournal.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.locjournal.geo.GeoUtils
import java.util.UUID

/**
 * One stay at one location. A new Visit is created only when the tracker detects that you moved
 * further than the configured distance threshold; otherwise the current visit's [endTime] is extended.
 */
@Entity(
    tableName = "visits",
    indices = [Index("startTime"), Index("endTime"), Index("savedPlaceId"), Index(value = ["uuid"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = SavedPlace::class,
            parentColumns = ["id"],
            childColumns = ["savedPlaceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class Visit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    /** Best (smallest) GPS accuracy radius seen for this visit, in meters. */
    val accuracy: Float? = null,
    val startTime: Long,
    /** Time of the last location sample at this place (or user-set end time). */
    val endTime: Long,
    val sampleCount: Int = 1,
    /** User-set title; overrides the saved place name and the auto-detected place name. */
    val title: String? = null,
    /** Auto-detected place name from OpenStreetMap (e.g. "University of X"). */
    val placeName: String? = null,
    /** Auto-detected place category (e.g. "university", "cafe"). */
    val placeCategory: String? = null,
    val address: String? = null,
    /** What you were doing there. */
    val purpose: String? = null,
    val notes: String? = null,
    /** User-defined custom fields (key -> value). */
    val extras: Map<String, String> = emptyMap(),
    val savedPlaceId: Long? = null,
    val isManual: Boolean = false,
    /** True once online place lookup completed (or is not needed). */
    val lookupDone: Boolean = false,
    /** True once the user changed the location or times by hand; the tracker then stops refining it. */
    val userEdited: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB color. */
    val color: Int,
)

@Entity(
    tableName = "visit_tags",
    primaryKeys = ["visitId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = Visit::class, parentColumns = ["id"], childColumns = ["visitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class VisitTagCrossRef(
    val visitId: Long,
    val tagId: Long,
)

/**
 * A place you named yourself ("Home", "Office", "Gym"). Any location sample within [radiusMeters]
 * (plus a GPS accuracy allowance) is attributed to this place.
 */
@Entity(tableName = "saved_places", indices = [Index(value = ["uuid"], unique = true)])
data class SavedPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f,
    val address: String? = null,
    val defaultPurpose: String? = null,
    /** Label (tag) names applied automatically to visits at this place. */
    val defaultTags: List<String> = emptyList(),
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class VisitWithDetails(
    @Embedded val visit: Visit,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(VisitTagCrossRef::class, parentColumn = "visitId", entityColumn = "tagId"),
    )
    val tags: List<Tag>,
    @Relation(parentColumn = "savedPlaceId", entityColumn = "id")
    val savedPlace: SavedPlace?,
) {
    val displayTitle: String
        get() = visit.title?.takeIf { it.isNotBlank() }
            ?: savedPlace?.name
            ?: visit.placeName?.takeIf { it.isNotBlank() }
            ?: visit.address?.let { GeoUtils.shortAddress(it) }
            ?: GeoUtils.formatCoords(visit.latitude, visit.longitude)

    /** Secondary line: the auto place/address when the title comes from somewhere else. */
    val subtitle: String?
        get() {
            val title = displayTitle
            val parts = listOfNotNull(
                visit.placeName?.takeIf { it.isNotBlank() && it != title },
                visit.address?.let { GeoUtils.shortAddress(it) }?.takeIf { it != title },
            )
            return parts.firstOrNull()
        }
}

data class PurposeCount(
    val purpose: String,
    @ColumnInfo(name = "cnt") val count: Int,
)
