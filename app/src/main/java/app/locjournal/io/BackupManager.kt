package app.locjournal.io

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.locjournal.data.AppDatabase
import app.locjournal.data.JournalRepository
import app.locjournal.data.SavedPlace
import app.locjournal.data.Visit
import app.locjournal.data.VisitTagCrossRef
import app.locjournal.data.VisitWithDetails
import app.locjournal.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

@Serializable
data class BackupFile(
    val format: String = "location-journal",
    val version: Int = 1,
    val exportedAt: Long,
    val tags: List<TagDto>,
    val savedPlaces: List<SavedPlaceDto>,
    val visits: List<VisitDto>,
)

@Serializable
data class TagDto(val name: String, val color: Int)

@Serializable
data class SavedPlaceDto(
    val uuid: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val address: String? = null,
    val defaultPurpose: String? = null,
    val defaultTags: List<String> = emptyList(),
    val notes: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class VisitDto(
    val uuid: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val startTime: Long,
    val endTime: Long,
    val sampleCount: Int = 1,
    val title: String? = null,
    val placeName: String? = null,
    val placeCategory: String? = null,
    val address: String? = null,
    val purpose: String? = null,
    val notes: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val savedPlaceUuid: String? = null,
    val tags: List<String> = emptyList(),
    val isManual: Boolean = false,
    val userEdited: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class ImportSummary(val visits: Int, val places: Int, val tags: Int)

/**
 * JSON is a full backup (everything, merged back by id on import).
 * CSV holds visits only, for spreadsheets; importing CSV adds/updates visits.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: JournalRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val compactJson = Json { ignoreUnknownKeys = true }

    suspend fun exportJson(uri: Uri): Int = withContext(Dispatchers.IO) {
        val visits = db.visitDao().getAllDetails()
        val places = repo.getPlaces()
        val placeUuids = places.associate { it.id to it.uuid }
        val backup = BackupFile(
            exportedAt = System.currentTimeMillis(),
            tags = repo.getTags().map { TagDto(it.name, it.color) },
            savedPlaces = places.map {
                SavedPlaceDto(
                    it.uuid, it.name, it.latitude, it.longitude, it.radiusMeters, it.address,
                    it.defaultPurpose, it.defaultTags, it.notes, it.createdAt,
                )
            },
            visits = visits.map { d ->
                val v = d.visit
                VisitDto(
                    uuid = v.uuid, latitude = v.latitude, longitude = v.longitude, accuracy = v.accuracy,
                    startTime = v.startTime, endTime = v.endTime, sampleCount = v.sampleCount, title = v.title,
                    placeName = v.placeName, placeCategory = v.placeCategory, address = v.address, purpose = v.purpose,
                    notes = v.notes, extras = v.extras, savedPlaceUuid = v.savedPlaceId?.let { placeUuids[it] },
                    tags = d.tags.map { it.name }, isManual = v.isManual, userEdited = v.userEdited,
                    createdAt = v.createdAt, updatedAt = v.updatedAt,
                )
            },
        )
        write(uri, json.encodeToString(BackupFile.serializer(), backup))
        visits.size
    }

    suspend fun importJson(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val backup = json.decodeFromString(BackupFile.serializer(), read(uri))
        require(backup.format == "location-journal") { "Not a Location Journal backup" }
        db.withTransaction {
            backup.tags.forEach { repo.getOrCreateTag(it.name, it.color) }

            val placeIds = HashMap<String, Long>()
            for (p in backup.savedPlaces) {
                val existing = db.savedPlaceDao().getByUuid(p.uuid)
                val entity = SavedPlace(
                    id = existing?.id ?: 0, uuid = p.uuid, name = p.name, latitude = p.latitude,
                    longitude = p.longitude, radiusMeters = p.radiusMeters, address = p.address,
                    defaultPurpose = p.defaultPurpose, defaultTags = p.defaultTags, notes = p.notes,
                    createdAt = p.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                placeIds[p.uuid] = repo.savePlace(entity)
            }

            for (v in backup.visits) {
                val existing = db.visitDao().getByUuid(v.uuid)
                val visit = Visit(
                    id = existing?.id ?: 0, uuid = v.uuid, latitude = v.latitude, longitude = v.longitude,
                    accuracy = v.accuracy, startTime = v.startTime, endTime = v.endTime, sampleCount = v.sampleCount,
                    title = v.title, placeName = v.placeName, placeCategory = v.placeCategory, address = v.address,
                    purpose = v.purpose, notes = v.notes, extras = v.extras,
                    savedPlaceId = v.savedPlaceUuid?.let { placeIds[it] ?: db.savedPlaceDao().getByUuid(it)?.id },
                    isManual = v.isManual, lookupDone = true, userEdited = v.userEdited,
                    createdAt = v.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                )
                val tagIds = v.tags.map { repo.getOrCreateTag(it).id }
                repo.saveVisit(visit, tagIds)
            }
        }
        ImportSummary(backup.visits.size, backup.savedPlaces.size, backup.tags.size)
    }

    // ---- CSV ----

    private val csvColumns = listOf(
        "uuid", "date", "start", "end", "duration_min", "title", "saved_place", "place_name", "category",
        "address", "latitude", "longitude", "accuracy_m", "purpose", "labels", "notes", "custom_fields", "manual",
    )

    /** Exports visits overlapping [from, to), or all visits when null. */
    suspend fun exportCsv(uri: Uri, from: Long? = null, to: Long? = null): Int = withContext(Dispatchers.IO) {
        val visits: List<VisitWithDetails> =
            if (from != null && to != null) repo.getRange(from, to) else db.visitDao().getAllDetails()
        val mapSerializer = MapSerializer(String.serializer(), String.serializer())
        val rows = visits.map { d ->
            val v = d.visit
            listOf(
                v.uuid,
                TimeUtils.toLocalDate(v.startTime).toString(),
                TimeUtils.toLocalDateTime(v.startTime).format(TimeUtils.isoDateTimeFmt),
                TimeUtils.toLocalDateTime(v.endTime).format(TimeUtils.isoDateTimeFmt),
                ((v.endTime - v.startTime) / 60_000).toString(),
                v.title.orEmpty(),
                d.savedPlace?.name.orEmpty(),
                v.placeName.orEmpty(),
                v.placeCategory.orEmpty(),
                v.address.orEmpty(),
                v.latitude.toString(),
                v.longitude.toString(),
                v.accuracy?.toInt()?.toString().orEmpty(),
                v.purpose.orEmpty(),
                d.tags.joinToString("; ") { it.name },
                v.notes.orEmpty(),
                if (v.extras.isEmpty()) "" else compactJson.encodeToString(mapSerializer, v.extras),
                v.isManual.toString(),
            )
        }
        write(uri, Csv.write(listOf(csvColumns) + rows))
        rows.size
    }

    /**
     * Imports visits from CSV. Needs at least start, end, latitude and longitude columns
     * (the same headers the export uses). Rows with a known uuid update that entry.
     */
    suspend fun importCsv(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val table = Csv.parse(read(uri)).filter { row -> row.any { it.isNotBlank() } }
        require(table.isNotEmpty()) { "The file is empty" }
        val header = table.first().map { it.trim().lowercase() }
        fun idx(name: String) = header.indexOf(name)
        val iStart = idx("start"); val iEnd = idx("end"); val iLat = idx("latitude"); val iLon = idx("longitude")
        require(iStart >= 0 && iEnd >= 0 && iLat >= 0 && iLon >= 0) {
            "CSV needs start, end, latitude and longitude columns"
        }
        val places = repo.getPlaces().associateBy { it.name.lowercase() }
        val mapSerializer = MapSerializer(String.serializer(), String.serializer())
        var count = 0
        db.withTransaction {
            for (row in table.drop(1)) {
                fun col(name: String): String? = idx(name).takeIf { it >= 0 && it < row.size }?.let { row[it].trim() }?.ifEmpty { null }
                val start = parseDateTime(row.getOrNull(iStart)) ?: continue
                val end = parseDateTime(row.getOrNull(iEnd)) ?: start
                val lat = row.getOrNull(iLat)?.trim()?.toDoubleOrNull() ?: continue
                val lon = row.getOrNull(iLon)?.trim()?.toDoubleOrNull() ?: continue
                val uuid = col("uuid")
                val existing = uuid?.let { db.visitDao().getByUuid(it) }
                val extras = col("custom_fields")?.let {
                    runCatching { compactJson.decodeFromString(mapSerializer, it) }.getOrNull()
                } ?: emptyMap()
                val visit = Visit(
                    id = existing?.id ?: 0,
                    uuid = uuid ?: java.util.UUID.randomUUID().toString(),
                    latitude = lat, longitude = lon,
                    accuracy = col("accuracy_m")?.toFloatOrNull(),
                    startTime = start, endTime = maxOf(start, end),
                    title = col("title"), placeName = col("place_name"), placeCategory = col("category"),
                    address = col("address"), purpose = col("purpose"), notes = col("notes"),
                    extras = extras,
                    savedPlaceId = col("saved_place")?.let { places[it.lowercase()]?.id },
                    isManual = col("manual")?.toBooleanStrictOrNull() ?: true,
                    lookupDone = true, userEdited = true,
                )
                val tagIds = col("labels")?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.map { repo.getOrCreateTag(it).id } ?: emptyList()
                repo.saveVisit(visit, tagIds)
                count++
            }
        }
        ImportSummary(count, 0, 0)
    }

    private fun parseDateTime(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return it } // epoch millis
        val normalized = v.replace('T', ' ')
        return runCatching { TimeUtils.toMillis(LocalDateTime.parse(normalized, TimeUtils.isoDateTimeFmt)) }.getOrNull()
            ?: runCatching { TimeUtils.toMillis(LocalDateTime.parse(v)) }.getOrNull()
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: error("Could not open file for writing")
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not open file")
}
