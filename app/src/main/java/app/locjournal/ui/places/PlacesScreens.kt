package app.locjournal.ui.places

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locjournal.geo.GeoUtils
import app.locjournal.ui.common.MapCircle
import app.locjournal.ui.common.MapMarker
import app.locjournal.ui.common.OsmMap
import app.locjournal.ui.common.SectionTitle
import app.locjournal.ui.common.TagSelector
import app.locjournal.ui.common.appViewModel
import kotlin.math.roundToInt

@Composable
fun PlacesScreen(onOpenPlace: (Long) -> Unit, onAddPlace: () -> Unit) {
    val vm = appViewModel { PlacesViewModel(it) }
    val places by vm.places.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved places") },
                actions = {
                    IconButton(onClick = { showMap = !showMap }) { Icon(Icons.Default.Place, "Toggle map") }
                    IconButton(onClick = vm::applyToHistory) { Icon(Icons.Default.History, "Apply to past entries") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPlace,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add place") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = places
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showMap && !list.isNullOrEmpty()) {
                OsmMap(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    markers = list.map { MapMarker(it.place.id, it.place.latitude, it.place.longitude, it.place.name, it.place.name.take(1)) },
                    cameraKey = list.size,
                    onMarkerClick = { onOpenPlace(it.id) },
                )
            }
            when {
                list == null -> Unit
                list.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("No saved places yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Save places like Home, Work or your university by address or on the map. Whenever you are " +
                            "within the place's radius (GPS drift is allowed for), entries get its name, labels and " +
                            "purpose automatically, including past entries.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.place.id }) { row ->
                        val p = row.place
                        Card(Modifier.fillMaxWidth().clickable { onOpenPlace(p.id) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                p.address?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    listOfNotNull(
                                        "radius ${p.radiusMeters.roundToInt()} m",
                                        "${row.visitCount} entries",
                                        p.defaultPurpose,
                                        p.defaultTags.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceEditScreen(
    placeId: Long,
    initialLat: Double?,
    initialLon: Double?,
    initialName: String?,
    onBack: () -> Unit,
) {
    val vm = appViewModel(key = "place-$placeId-$initialLat-$initialLon") {
        PlaceEditViewModel(it, placeId, initialLat, initialLon, initialName)
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val tags by vm.tags.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                PlaceEditEvent.Closed -> onBack()
                is PlaceEditEvent.Message ->
                    if (e.text.startsWith("Linked")) Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                    else snackbar.showSnackbar(e.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New place" else "Edit place") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = vm::save, enabled = vm.loaded) {
                        Icon(Icons.Default.Check, null)
                        Text(" Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        ) {
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Name (e.g. Home, University, Gym)") },
                    singleLine = true,
                )
                SectionTitle("Find by address")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address or place name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    trailingIcon = { IconButton(onClick = { vm.search(query) }) { Icon(Icons.Default.Search, "Search") } },
                )
                vm.searchResults.forEach { r ->
                    Text(
                        r.displayName,
                        Modifier.fillMaxWidth().clickable { vm.pickResult(r) }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { vm.useCurrentLocation(context) },
                        label = { Text("Use current location") },
                        leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)) },
                    )
                }
                Text(
                    "Or tap the map to set the center. The circle is the area that counts as this place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.fillMaxWidth().height(280.dp).padding(top = 8.dp)) {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    markers = if (vm.hasLocation) listOf(MapMarker(0, vm.lat, vm.lon, vm.name)) else emptyList(),
                    circle = if (vm.hasLocation) MapCircle(vm.lat, vm.lon, vm.radius.toDouble()) else null,
                    cameraKey = vm.loaded to vm.cameraVersion,
                    defaultZoom = if (vm.hasLocation) 16.5 else 3.0,
                    onMapTap = { la, lo -> vm.setLocation(la, lo) },
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionTitle("Radius: ${vm.radius.roundToInt()} m")
                Slider(
                    value = vm.radius,
                    onValueChange = { vm.radius = (it / 5).roundToInt() * 5f },
                    valueRange = 25f..1000f,
                )
                Text(
                    "Entries within this distance (plus up to 75 m for GPS drift) are matched to this place. " +
                        "Use ~50–100 m for a house, 300–800 m for a campus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = vm.address,
                    onValueChange = { vm.address = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Address") },
                )
                if (vm.hasLocation) {
                    Text(
                        GeoUtils.formatCoords(vm.lat, vm.lon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionTitle("Applied automatically to entries here")
                OutlinedTextField(
                    value = vm.defaultPurpose,
                    onValueChange = { vm.defaultPurpose = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Default purpose (optional)") },
                    placeholder = { Text("e.g. Classes") },
                    singleLine = true,
                )
                Text("Default labels", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                TagSelector(
                    allTags = tags,
                    selected = tags.filter { it.name in vm.defaultTags }.map { it.id }.toSet(),
                    onToggle = vm::toggleTag,
                    onCreate = vm::createTag,
                )
                OutlinedTextField(
                    value = vm.notes,
                    onValueChange = { vm.notes = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Notes") },
                    minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = vm.applyToHistory, onCheckedChange = { vm.applyToHistory = it })
                    Text("Also link matching past entries", style = MaterialTheme.typography.bodyMedium)
                }
                if (!vm.isNew) {
                    Text(
                        "${vm.visitCount} entries are linked to this place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this place?") },
            text = { Text("Entries stay in your journal; they just won't be linked to this place anymore.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
