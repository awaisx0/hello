package app.locjournal.ui.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locjournal.tracking.Permissions
import app.locjournal.ui.common.DateSelection
import app.locjournal.ui.common.DateSelectionBar
import app.locjournal.ui.common.MapMarker
import app.locjournal.ui.common.OsmMap
import app.locjournal.ui.common.TagPill
import app.locjournal.ui.common.appViewModel
import app.locjournal.util.TimeUtils
import java.time.LocalDate

@Composable
fun TimelineScreen(
    selection: DateSelection,
    onSelect: (DateSelection) -> Unit,
    onOpenVisit: (Long) -> Unit,
    onAddVisit: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = appViewModel { TimelineViewModel(it) }
    LaunchedEffect(selection) { vm.setSelection(selection) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tagFilter by vm.tagFilter.collectAsStateWithLifecycle()
    var showMap by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location Journal") },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) vm.query.value = ""
                    }) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = { showMap = !showMap }) {
                        if (showMap) Icon(Icons.AutoMirrored.Filled.ViewList, "Show list")
                        else Icon(Icons.Default.Map, "Show map")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export this ${if (selection is DateSelection.Day) "day" else "range"} as CSV") },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("locations_${selection.from}_${selection.to}.csv")
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddVisit(if (selection is DateSelection.Day) selection.date else LocalDate.now()) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add entry") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DateSelectionBar(selection, onSelect)

            val trackingOff = !state.loading && (!state.settings.trackingEnabled || !Permissions.hasLocation(context))
            if (trackingOff) TrackingOffBanner(onOpenSettings)

            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Search place, purpose, notes, labels…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") }
                    },
                )
            }
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tagFilter == tag.id,
                            onClick = { vm.tagFilter.value = if (tagFilter == tag.id) null else tag.id },
                            label = { Text(tag.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(tag.color))) },
                        )
                    }
                }
            }

            when {
                state.loading -> Unit
                showMap -> TimelineMap(state, onOpenVisit)
                state.days.isEmpty() -> EmptyState(state.filtered, selection)
                else -> TimelineList(state, onOpenVisit)
            }
        }
    }
}

@Composable
private fun TrackingOffBanner(onOpenSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Tracking is off. New places aren't being recorded.",
                Modifier.weight(1f).padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSettings) { Text("Set up") }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean, selection: DateSelection) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.EditNote, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                filtered -> "Nothing matches your search or label filter."
                selection is DateSelection.Day -> "No places recorded on this day."
                else -> "No places recorded in this date range."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Use “Add entry” to add one by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineList(state: TimelineUiState, onOpenVisit: (Long) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.days.forEach { day ->
            stickyHeader(key = "h-${day.date}") { DayHeader(day) }
            val entries = day.entries
            entries.forEachIndexed { i, entry ->
                if (i > 0) {
                    val gap = entry.segment.start - entries[i - 1].segment.end
                    if (gap >= 5 * 60_000L) {
                        item(key = "g-${day.date}-${entry.visit.id}") { GapRow(gap) }
                    }
                }
                item(key = "v-${day.date}-${entry.visit.id}") {
                    VisitCard(entry, onClick = { onOpenVisit(entry.visit.id) })
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: DayGroup) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Text(TimeUtils.formatDayHeader(day.date), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val places = day.entries.map { it.details.displayTitle }.distinct().size
            Text(
                "${day.entries.size} ${if (day.entries.size == 1) "entry" else "entries"} · $places " +
                    "${if (places == 1) "place" else "places"} · ${TimeUtils.formatDuration(day.recordedMillis)} recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun GapRow(gapMillis: Long) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            "  ${TimeUtils.formatDuration(gapMillis)} moving or no data",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun VisitCard(entry: TimelineEntry, onClick: () -> Unit) {
    val d = entry.details
    val v = d.visit
    val seg = entry.segment
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (entry.ongoing) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(56.dp), horizontalAlignment = Alignment.Start) {
                Text(
                    if (seg.continuedFromPreviousDay) "00:00" else TimeUtils.formatTime(seg.start),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("↓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    when {
                        entry.ongoing -> "now"
                        seg.continuesNextDay -> "24:00"
                        else -> TimeUtils.formatTime(seg.end)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    d.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                d.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                v.purpose?.takeIf { it.isNotBlank() }?.let {
                    Text("▸ $it", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                v.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (d.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        d.tags.forEach { TagPill(it) }
                    }
                }
                val notes = buildList {
                    if (seg.continuedFromPreviousDay) add("since ${TimeUtils.formatDateTime(v.startTime)}")
                    if (seg.continuesNextDay && !entry.ongoing) add("until ${TimeUtils.formatDateTime(v.endTime)}")
                    if (v.isManual) add("added by hand")
                }
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    TimeUtils.formatDuration(seg.duration),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.ongoing) {
                    Text("● now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun TimelineMap(state: TimelineUiState, onOpenVisit: (Long) -> Unit) {
    // One pin per visit in time order, connected by a line.
    val visits = state.days.flatMap { it.entries }.distinctBy { it.visit.id }.sortedBy { it.visit.startTime }
    val markers = visits.map {
        MapMarker(
            id = it.visit.id,
            lat = it.visit.latitude,
            lon = it.visit.longitude,
            title = it.details.displayTitle,
            label = it.number.toString(),
            color = it.details.tags.firstOrNull()?.color ?: 0xFF1E5B8C.toInt(),
        )
    }
    var selected by remember { mutableStateOf<TimelineEntry?>(null) }
    Box(Modifier.fillMaxSize()) {
        if (markers.isEmpty()) {
            EmptyState(state.filtered, state.selection ?: DateSelection.Day(LocalDate.now()))
        } else {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                markers = markers,
                path = markers,
                cameraKey = state.selection to markers.size,
                onMarkerClick = { m -> selected = visits.firstOrNull { it.visit.id == m.id } },
                onMapTap = { _, _ -> selected = null },
            )
        }
        selected?.let { e ->
            Card(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clickable { onOpenVisit(e.visit.id) },
                elevation = CardDefaults.cardElevation(6.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("#${e.number}  ${e.details.displayTitle}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${TimeUtils.formatDateTime(e.visit.startTime)} – ${TimeUtils.formatTime(e.visit.endTime)} · " +
                            TimeUtils.formatDuration(e.visit.endTime - e.visit.startTime),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    e.visit.purpose?.let { Text("▸ $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("Tap to edit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
