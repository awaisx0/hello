package app.locjournal.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun mapToString(map: Map<String, String>): String = json.encodeToString(mapSerializer, map)

    @TypeConverter
    fun stringToMap(value: String?): Map<String, String> =
        if (value.isNullOrBlank()) emptyMap() else runCatching { json.decodeFromString(mapSerializer, value) }.getOrDefault(emptyMap())

    @TypeConverter
    fun listToString(list: List<String>): String = json.encodeToString(listSerializer, list)

    @TypeConverter
    fun stringToList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, value) }.getOrDefault(emptyList())
}
