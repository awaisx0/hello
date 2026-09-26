package app.locjournal.ui.places

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locjournal.AppContainer
import app.locjournal.data.SavedPlace
import app.locjournal.data.Tag
import app.locjournal.geo.AddressResult
import app.locjournal.geo.LatLon
import app.locjournal.tracking.Permissions
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class PlaceRow(val place: SavedPlace, val visitCount: Int)

class PlacesViewModel(private val c: AppContainer) : ViewModel() {
    val places: StateFlow<List<PlaceRow>?> = c.repo.observePlaces()
        .map { list -> list.map { PlaceRow(it, c.repo.visitCountForPlace(it.id)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages

    fun applyToHistory() {
        viewModelScope.launch {
            val n = c.repo.applySavedPlacesToHistory()
            _messages.emit(if (n == 0) "No new entries matched your places" else "Linked $n entries to your places")
        }
    }
}

sealed interface PlaceEditEvent {
    data object Closed : PlaceEditEvent
    data class Message(val text: String) : PlaceEditEvent
}

class PlaceEditViewModel(
    private val c: AppContainer,
    private val placeId: Long,
    initialLat: Double?,
    initialLon: Double?,
    initialName: String?,
) : ViewModel() {
    var loaded by mutableStateOf(false); private set
    var original by mutableStateOf<SavedPlace?>(null); private set
    val isNew get() = placeId <= 0

    var name by mutableStateOf(initialName.orEmpty())
    var lat by mutableStateOf(initialLat ?: 0.0); private set
    var lon by mutableStateOf(initialLon ?: 0.0); private set
    var hasLocation by mutableStateOf(initialLat != null && initialLon != null); private set
    var radius by mutableStateOf(100f)
    var address by mutableStateOf("")
    var defaultPurpose by mutableStateOf("")
    var notes by mutableStateOf("")
    var defaultTags by mutableStateOf<Set<String>>(emptySet()); private set
    var applyToHistory by mutableStateOf(true)

    var busy by mutableStateOf<String?>(null); private set
    var searchResults by mutableStateOf<List<AddressResult>>(emptyList()); private set
    var visitCount by mutableStateOf(0); private set

    /** Bumped when the map should re-center (search result, current location), but not on map taps. */
    var cameraVersion by mutableStateOf(0); private set

    val tags: StateFlow<List<Tag>> = c.repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<PlaceEditEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PlaceEditEvent> = _events

    init {
        viewModelScope.launch {
            if (!isNew) {
                val p = c.repo.getPlace(placeId)
                if (p == null) {
                    _events.emit(PlaceEditEvent.Closed)
                    return@launch
                }
                original = p
                name = p.name
                lat = p.latitude
                lon = p.longitude
                hasLocation = true
                radius = p.radiusMeters
                address = p.address.orEmpty()
                defaultPurpose = p.defaultPurpose.orEmpty()
                notes = p.notes.orEmpty()
                defaultTags = p.defaultTags.toSet()
                visitCount = c.repo.visitCountForPlace(p.id)
            } else if (hasLocation) {
                fillAddress()
            }
            loaded = true
        }
    }

    fun setLocation(newLat: Double, newLon: Double, newAddress: String? = null, recenter: Boolean = false) {
        val first = !hasLocation
        lat = newLat
        lon = newLon
        hasLocation = true
        if (recenter || first) cameraVersion++
        if (newAddress != null) address = newAddress else fillAddress()
    }

    private fun fillAddress() {
        viewModelScope.launch {
            c.placeLookup.reverseAddress(lat, lon)?.let { address = it }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            busy = "Searching…"
            val near = if (hasLocation) LatLon(lat, lon) else c.db.visitDao().latestTracked()?.let { LatLon(it.latitude, it.longitude) }
            searchResults = c.placeLookup.searchAddress(query, near)
            if (searchResults.isEmpty()) _events.emit(PlaceEditEvent.Message("No results. Try adding the city or area."))
            busy = null
        }
    }

    fun pickResult(r: AddressResult) {
        setLocation(r.lat, r.lon, r.displayName, recenter = true)
        if (name.isBlank()) name = r.displayName.substringBefore(",").trim()
        searchResults = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun useCurrentLocation(context: Context) {
        if (!Permissions.hasLocation(context)) {
            _events.tryEmit(PlaceEditEvent.Message("Location permission is needed (Settings tab)"))
            return
        }
        viewModelScope.launch {
            busy = "Getting your location…"
            try {
                val loc = LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (loc != null) setLocation(loc.latitude, loc.longitude, recenter = true)
                else _events.emit(PlaceEditEvent.Message("Location unavailable right now"))
            } catch (e: Exception) {
                _events.emit(PlaceEditEvent.Message("Location failed: ${e.message}"))
            } finally {
                busy = null
            }
        }
    }

    fun toggleTag(tag: Tag) {
        defaultTags = if (tag.name in defaultTags) defaultTags - tag.name else defaultTags + tag.name
    }

    fun createTag(tagName: String, color: Int) {
        viewModelScope.launch {
            val t = c.repo.getOrCreateTag(tagName, color)
            defaultTags = defaultTags + t.name
        }
    }

    fun save() {
        if (name.isBlank()) return emit("Give the place a name")
        if (!hasLocation) return emit("Set the location: search an address, tap the map or use your current location")
        viewModelScope.launch {
            val base = original ?: SavedPlace(name = name.trim(), latitude = lat, longitude = lon)
            c.repo.savePlace(
                base.copy(
                    name = name.trim(),
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius,
                    address = address.trim().ifEmpty { null },
                    defaultPurpose = defaultPurpose.trim().ifEmpty { null },
                    defaultTags = defaultTags.toList(),
                    notes = notes.trim().ifEmpty { null },
                ),
            )
            if (applyToHistory) {
                val n = c.repo.applySavedPlacesToHistory()
                if (n > 0) _events.emit(PlaceEditEvent.Message("Linked $n past entries"))
            }
            _events.emit(PlaceEditEvent.Closed)
        }
    }

    fun delete() {
        val p = original ?: return
        viewModelScope.launch {
            c.repo.deletePlace(p)
            _events.emit(PlaceEditEvent.Closed)
        }
    }

    private fun emit(text: String) {
        _events.tryEmit(PlaceEditEvent.Message(text))
    }
}
