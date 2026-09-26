package app.locjournal.ui.visit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locjournal.geo.GeoUtils
import app.locjournal.geo.PlaceCandidate
import app.locjournal.ui.common.DateTimePickerDialog
import app.locjournal.ui.common.MapMarker
import app.locjournal.ui.common.OsmMap
import app.locjournal.ui.common.SectionTitle
import app.locjournal.ui.common.TagSelector
import app.locjournal.ui.common.appViewModel
import app.locjournal.util.TimeUtils

@Composable
fun VisitEditScreen(
    visitId: Long,
    initialDate: String?,
    onBack: () -> Unit,
    onSaveAsPlace: (lat: Double, lon: Double, name: String?) -> Unit,
) {
    val vm = appViewModel(key = "visit-$visitId-$initialDate") { VisitEditViewModel(it, visitId, initialDate) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                VisitEditEvent.Closed -> onBack()
                is VisitEditEvent.Message -> {
                    if (e.text == "Entry deleted") Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                    else snackbar.showSnackbar(e.text)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New entry" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    TextButton(onClick = vm::save, enabled = vm.loaded) {
                        Icon(Icons.Default.Check, null)
                        Text(" Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!vm.loaded) {
            Box(Modifier.padding(padding).fillMaxWidth()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            LocationSection(vm)
            Column(Modifier.padding(horizontal = 16.dp)) {
                PlaceSection(vm)
                TimeSection(vm)
                PurposeSection(vm)

                SectionTitle("Labels")
                val tags by vm.tags.collectAsStateWithLifecycle()
                TagSelector(
                    allTags = tags,
                    selected = vm.selectedTags,
                    onToggle = vm::toggleTag,
                    onCreate = vm::createTag,
                )

                SectionTitle("Notes")
                OutlinedTextField(
                    value = vm.notes,
                    onValueChange = { vm.notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Anything you want to remember about this") },
                    minLines = 3,
                )

                ExtrasSection(vm)
                InfoSection(vm, onSaveAsPlace = {
                    onSaveAsPlace(vm.lat, vm.lon, vm.title.ifBlank { null } ?: vm.placeName)
                })
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LocationSection(vm: VisitEditViewModel) {
    val context = LocalContext.current
    var movePin by remember { mutableStateOf(!vm.hasLocation) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Box(Modifier.fillMaxWidth().height(240.dp)) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            markers = if (vm.hasLocation) listOf(MapMarker(0, vm.lat, vm.lon, vm.title)) else emptyList(),
            cameraKey = vm.hasLocation to (vm.lat to vm.lon).takeIf { !movePin },
            defaultZoom = if (vm.hasLocation) 17.0 else 3.0,
            onMapTap = if (movePin) { la, lo -> vm.setLocation(la, lo) } else null,
        )
        if (movePin) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    "Tap the map to place the pin",
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = movePin,
            onClick = { movePin = !movePin },
            label = { Text(if (movePin) "Moving pin (tap map)" else "Move pin") },
            leadingIcon = { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = { vm.useCurrentLocation(context) },
            label = { Text("Current location") },
            leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = { showSearch = !showSearch },
            label = { Text("Search address") },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
        )
    }
    if (showSearch) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Address or place name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchAddress(query) }),
                trailingIcon = { IconButton(onClick = { vm.searchAddress(query) }) { Icon(Icons.Default.Search, "Search") } },
            )
            vm.addressResults.forEach { r ->
                Text(
                    r.displayName,
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.pickAddress(r)
                            showSearch = false
                            movePin = false
                        }
                        .padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlaceSection(vm: VisitEditViewModel) {
    val places by vm.places.collectAsStateWithLifecycle()
    SectionTitle("Place")
    OutlinedTextField(
        value = vm.title,
        onValueChange = { vm.title = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Title") },
        placeholder = {
            Text(places.firstOrNull { it.id == vm.savedPlaceId }?.name ?: vm.placeName ?: "e.g. University library")
        },
        singleLine = true,
        trailingIcon = {
            if (vm.title.isNotEmpty()) IconButton(onClick = { vm.title = "" }) { Icon(Icons.Default.Close, "Clear title") }
        },
    )
    val auto = listOfNotNull(vm.placeName, vm.placeCategory?.replace('_', ' ')).joinToString(" · ")
    if (auto.isNotEmpty()) {
        Text(
            "Detected: $auto",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::findNearbyNames, enabled = vm.hasLocation && vm.busy == null) {
            Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
            Text(" Suggest names")
        }
        TextButton(onClick = vm::refreshPlaceInfo, enabled = vm.hasLocation && vm.busy == null) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Text(" Re-detect place")
        }
    }
    if (vm.candidates.isNotEmpty()) {
        Text("Tap a name to use it as the title:", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.candidates.take(20).forEach { c -> CandidateChip(c) { vm.pickCandidate(c) } }
        }
    }

    // Saved place link
    var expanded by remember { mutableStateOf(false) }
    val current = places.firstOrNull { it.id == vm.savedPlaceId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = current?.name ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Saved place") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { vm.savedPlaceId = null; expanded = false })
            places.forEach { p ->
                val dist = GeoUtils.distanceMeters(p.latitude, p.longitude, vm.lat, vm.lon)
                DropdownMenuItem(
                    text = { Text("${p.name}  ·  ${GeoUtils.formatDistance(dist)} away") },
                    onClick = { vm.savedPlaceId = p.id; expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CandidateChip(c: PlaceCandidate, onClick: () -> Unit) {
    val where = if (c.enclosing) "you're inside" else GeoUtils.formatDistance(c.distanceMeters)
    SuggestionChip(
        onClick = onClick,
        label = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(c.name, style = MaterialTheme.typography.labelLarge)
                Text(
                    listOfNotNull(c.category?.replace('_', ' '), where).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun TimeSection(vm: VisitEditViewModel) {
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    SectionTitle("Time")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { pickStart = true }, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("From", style = MaterialTheme.typography.labelSmall)
                Text(TimeUtils.formatDateTime(vm.startTime))
            }
        }
        OutlinedButton(onClick = { pickEnd = true }, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("To", style = MaterialTheme.typography.labelSmall)
                Text(TimeUtils.formatDateTime(vm.endTime))
            }
        }
    }
    Text(
        "Time here: ${TimeUtils.formatDuration(vm.endTime - vm.startTime)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (vm.original?.isManual == false) {
        Text(
            "If this is your current place, tracking keeps moving the end time forward while you stay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pickStart) {
        DateTimePickerDialog(vm.startTime, onDismiss = { pickStart = false }) { vm.updateStart(it); pickStart = false }
    }
    if (pickEnd) {
        DateTimePickerDialog(vm.endTime, onDismiss = { pickEnd = false }) { vm.updateEnd(it); pickEnd = false }
    }
}

@Composable
private fun PurposeSection(vm: VisitEditViewModel) {
    val suggestions by vm.purposeSuggestions.collectAsStateWithLifecycle()
    SectionTitle("What were you doing?")
    OutlinedTextField(
        value = vm.purpose,
        onValueChange = { vm.purpose = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("e.g. Lecture, groceries, meeting a friend") },
        singleLine = true,
    )
    val shown = suggestions.filter { it != vm.purpose && (vm.purpose.isBlank() || it.contains(vm.purpose, true)) }
    if (shown.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.take(8).forEach { s -> SuggestionChip(onClick = { vm.purpose = s }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun ExtrasSection(vm: VisitEditViewModel) {
    SectionTitle("Custom fields")
    if (vm.extras.isEmpty()) {
        Text(
            "Add your own details, e.g. who you were with, cost, mood.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    vm.extras.forEachIndexed { i, field ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            OutlinedTextField(
                value = field.key,
                onValueChange = { vm.updateExtra(i, field.copy(key = it)) },
                modifier = Modifier.weight(0.4f),
                label = { Text("Field") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = field.value,
                onValueChange = { vm.updateExtra(i, field.copy(value = it)) },
                modifier = Modifier.weight(0.6f),
                label = { Text("Value") },
                singleLine = true,
            )
            IconButton(onClick = { vm.removeExtra(i) }) { Icon(Icons.Default.Close, "Remove field") }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { vm.addExtra() },
            label = { Text("Add field") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
        val used = vm.extras.map { it.key.lowercase() }.toSet()
        listOf("With", "Mood", "Cost", "Rating").filter { it.lowercase() !in used }.forEach { key ->
            SuggestionChip(onClick = { vm.addExtra(key) }, label = { Text("+ $key") })
        }
    }
}

@Composable
private fun InfoSection(vm: VisitEditViewModel, onSaveAsPlace: () -> Unit) {
    val context = LocalContext.current
    SectionTitle("Details")
    val o = vm.original
    val rows = buildList {
        vm.address?.let { add("Address" to it) }
        if (vm.hasLocation) add("Coordinates" to GeoUtils.formatCoords(vm.lat, vm.lon))
        o?.accuracy?.let { add("GPS accuracy" to "±${it.toInt()} m") }
        if (o != null && !o.isManual) add("Location samples" to o.sampleCount.toString())
        o?.let { add("Source" to if (it.isManual) "Added by hand" else "Recorded automatically") }
        o?.let { add("Last edited" to TimeUtils.formatDateTime(it.updatedAt)) }
    }
    rows.forEach { (k, v) ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v, style = MaterialTheme.typography.bodySmall)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        AssistChip(
            onClick = {
                val label = Uri.encode(vm.title.ifBlank { vm.placeName ?: "Entry" })
                val uri = Uri.parse("geo:${vm.lat},${vm.lon}?q=${vm.lat},${vm.lon}($label)")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = vm.hasLocation,
            label = { Text("Open in maps app") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = onSaveAsPlace,
            enabled = vm.hasLocation,
            label = { Text("Save as a place") },
            leadingIcon = { Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp)) },
        )
    }
}
