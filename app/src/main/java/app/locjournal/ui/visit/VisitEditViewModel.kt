package app.locjournal.ui.visit

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locjournal.AppContainer
import app.locjournal.data.SavedPlace
import app.locjournal.data.Tag
import app.locjournal.data.Visit
import app.locjournal.geo.AddressResult
import app.locjournal.geo.LatLon
import app.locjournal.geo.PlaceCandidate
import app.locjournal.tracking.Permissions
import app.locjournal.util.TimeUtils
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ExtraField(val key: String, val value: String)

sealed interface VisitEditEvent {
    data object Closed : VisitEditEvent
    data class Message(val text: String) : VisitEditEvent
}

class VisitEditViewModel(
    private val c: AppContainer,
    private val visitId: Long,
    private val initialDate: String?,
) : ViewModel() {
    var loaded by mutableStateOf(false); private set
    var original by mutableStateOf<Visit?>(null); private set
    val isNew: Boolean get() = visitId <= 0

    var title by mutableStateOf("")
    var purpose by mutableStateOf("")
    var notes by mutableStateOf("")
    var startTime by mutableStateOf(0L); private set
    var endTime by mutableStateOf(0L); private set
    var lat by mutableStateOf(0.0); private set
    var lon by mutableStateOf(0.0); private set
    var hasLocation by mutableStateOf(false); private set
    var placeName by mutableStateOf<String?>(null); private set
    var placeCategory by mutableStateOf<String?>(null); private set
    var address by mutableStateOf<String?>(null); private set
    var savedPlaceId by mutableStateOf<Long?>(null)
    var selectedTags by mutableStateOf<Set<Long>>(emptySet()); private set
    val extras = mutableStateListOf<ExtraField>()

    private var locationChanged = false
    private var timesChanged = false
    private var lookupDone = false

    var candidates by mutableStateOf<List<PlaceCandidate>>(emptyList()); private set
    var busy by mutableStateOf<String?>(null); private set
    var addressResults by mutableStateOf<List<AddressResult>>(emptyList()); private set

    private val _events = MutableSharedFlow<VisitEditEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VisitEditEvent> = _events

    val tags: StateFlow<List<Tag>> = c.repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val places: StateFlow<List<SavedPlace>> = c.repo.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val purposeSuggestions: StateFlow<List<String>> = c.repo.observeTopPurposes()
        .map { list -> list.map { it.purpose } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (!isNew) {
            val d = c.repo.observeVisit(visitId).first()
            if (d == null) {
                _events.emit(VisitEditEvent.Message("Entry not found"))
                _events.emit(VisitEditEvent.Closed)
                return
            }
            val v = d.visit
            original = v
            title = v.title.orEmpty()
            purpose = v.purpose.orEmpty()
            notes = v.notes.orEmpty()
            startTime = v.startTime
            endTime = v.endTime
            lat = v.latitude
            lon = v.longitude
            hasLocation = true
            placeName = v.placeName
            placeCategory = v.placeCategory
            address = v.address
            savedPlaceId = v.savedPlaceId
            selectedTags = d.tags.map { it.id }.toSet()
            extras.addAll(v.extras.map { ExtraField(it.key, it.value) })
            lookupDone = v.lookupDone
        } else {
            val date = initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val start = if (date == LocalDate.now()) {
                LocalDateTime.now().minusHours(1).withMinute(0).withSecond(0).withNano(0)
            } else {
                LocalDateTime.of(date, LocalTime.NOON)
            }
            startTime = TimeUtils.toMillis(start)
            endTime = startTime + 60 * 60_000L
            c.db.visitDao().latestTracked()?.let { setLocationInternal(it.latitude, it.longitude, fromUser = false) }
        }
        loaded = true
    }

    // ---- Location ----

    fun setLocation(newLat: Double, newLon: Double, newAddress: String? = null) {
        setLocationInternal(newLat, newLon, fromUser = true)
        address = newAddress
        placeName = null
        placeCategory = null
        candidates = emptyList()
        lookupDone = false
        viewModelScope.launch {
            if (savedPlaceId == null) {
                c.repo.matchPlace(c.repo.getPlaces(), newLat, newLon, null)?.let { savedPlaceId = it.id }
            }
        }
    }

    private fun setLocationInternal(newLat: Double, newLon: Double, fromUser: Boolean) {
        lat = newLat
        lon = newLon
        hasLocation = true
        if (fromUser) locationChanged = true
    }

    @SuppressLint("MissingPermission")
    fun useCurrentLocation(context: android.content.Context) {
        if (!Permissions.hasLocation(context)) {
            message("Location permission is needed (Settings tab)")
            return
        }
        viewModelScope.launch {
            busy = "Getting your location…"
            try {
                val loc = LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (loc != null) setLocation(loc.latitude, loc.longitude) else message("Location unavailable right now")
            } catch (e: Exception) {
                message("Location failed: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    fun searchAddress(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            busy = "Searching…"
            addressResults = c.placeLookup.searchAddress(query, if (hasLocation) LatLon(lat, lon) else null)
            if (addressResults.isEmpty()) message("No results. Try a different spelling or add the city.")
            busy = null
        }
    }

    fun pickAddress(result: AddressResult) {
        setLocation(result.lat, result.lon, result.displayName)
        addressResults = emptyList()
    }

    // ---- Place names ----

    fun findNearbyNames() {
        if (!hasLocation) return
        viewModelScope.launch {
            busy = "Looking up nearby places…"
            try {
                candidates = c.placeLookup.nearbyCandidates(lat, lon, 150)
                if (candidates.isEmpty()) message("No named places found nearby on OpenStreetMap")
            } catch (e: Exception) {
                message("Lookup failed (offline?): ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    fun refreshPlaceInfo() {
        if (!hasLocation) return
        viewModelScope.launch {
            busy = "Looking up place name and address…"
            try {
                val r = c.placeLookup.lookup(lat, lon)
                placeName = r.placeName ?: placeName
                placeCategory = r.placeCategory ?: placeCategory
                address = r.address ?: address
                candidates = r.candidates
                lookupDone = r.placeName != null || r.address != null
                if (!lookupDone) message("Lookup failed (offline?)")
            } finally {
                busy = null
            }
        }
    }

    fun pickCandidate(candidate: PlaceCandidate) {
        title = candidate.name
        placeCategory = candidate.category ?: placeCategory
    }

    // ---- Times ----

    fun updateStart(millis: Long) {
        startTime = millis
        if (endTime < startTime) endTime = startTime
        timesChanged = true
    }

    fun updateEnd(millis: Long) {
        endTime = millis
        timesChanged = true
    }

    // ---- Labels & custom fields ----

    fun toggleTag(tag: Tag) {
        selectedTags = if (tag.id in selectedTags) selectedTags - tag.id else selectedTags + tag.id
    }

    fun createTag(name: String, color: Int) {
        viewModelScope.launch {
            val tag = c.repo.getOrCreateTag(name, color)
            selectedTags = selectedTags + tag.id
        }
    }

    fun addExtra(key: String = "") = extras.add(ExtraField(key, ""))
    fun updateExtra(index: Int, field: ExtraField) {
        if (index in extras.indices) extras[index] = field
    }
    fun removeExtra(index: Int) {
        if (index in extras.indices) extras.removeAt(index)
    }

    // ---- Save / delete ----

    fun save() {
        if (!hasLocation) return message("Pick a location first: tap the map, search an address or use your current location")
        if (endTime < startTime) return message("End time must be after start time")
        viewModelScope.launch {
            val base = original ?: Visit(
                latitude = lat, longitude = lon, startTime = startTime, endTime = endTime,
                isManual = true, userEdited = true,
            )
            val visit = base.copy(
                latitude = lat,
                longitude = lon,
                accuracy = if (locationChanged) null else base.accuracy,
                startTime = startTime,
                endTime = endTime,
                title = title.trim().ifEmpty { null },
                purpose = purpose.trim().ifEmpty { null },
                notes = notes.trim().ifEmpty { null },
                extras = extras.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value.trim() },
                savedPlaceId = savedPlaceId,
                placeName = placeName,
                placeCategory = placeCategory,
                address = address,
                lookupDone = lookupDone,
                userEdited = base.userEdited || locationChanged || timesChanged,
            )
            val id = c.repo.saveVisit(visit, selectedTags)
            if (!visit.lookupDone && c.settings.current().onlineLookup) {
                c.appScope.launch { c.recorder.refreshPlaceInfo(id) }
            }
            _events.emit(VisitEditEvent.Closed)
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (!isNew) c.repo.deleteVisit(visitId)
            _events.emit(VisitEditEvent.Message("Entry deleted"))
            _events.emit(VisitEditEvent.Closed)
        }
    }

    private fun message(text: String) {
        _events.tryEmit(VisitEditEvent.Message(text))
    }
}
