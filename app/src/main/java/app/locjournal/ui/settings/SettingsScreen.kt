package app.locjournal.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.locjournal.AppContainer
import app.locjournal.data.AppSettings
import app.locjournal.data.Tag
import app.locjournal.tracking.LocationTrackingService
import app.locjournal.tracking.Permissions
import app.locjournal.tracking.WatchdogReceiver
import app.locjournal.ui.common.SectionTitle
import app.locjournal.ui.common.TagEditDialog
import app.locjournal.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tags: StateFlow<List<Tag>> = c.repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun startTracking(context: Context) {
        viewModelScope.launch {
            c.settings.setTrackingEnabled(true)
            try {
                LocationTrackingService.start(context)
                _messages.emit("Tracking started")
            } catch (e: Exception) {
                _messages.emit("Could not start tracking: ${e.message}")
            }
        }
    }

    fun stopTracking(context: Context) {
        viewModelScope.launch {
            c.settings.setTrackingEnabled(false)
            WatchdogReceiver.cancel(context)
            runCatching { LocationTrackingService.stop(context) }
            _messages.emit("Tracking stopped")
        }
    }

    fun setInterval(v: Int) = viewModelScope.launch { c.settings.setIntervalMinutes(v) }
    fun setDistance(v: Int) = viewModelScope.launch { c.settings.setDistanceThreshold(v) }
    fun setMaxAccuracy(v: Int) = viewModelScope.launch { c.settings.setMaxAccuracy(v) }
    fun setHighAccuracy(v: Boolean) = viewModelScope.launch { c.settings.setHighAccuracy(v) }
    fun setFilterGlitches(v: Boolean) = viewModelScope.launch { c.settings.setFilterGlitches(v) }
    fun setOnlineLookup(v: Boolean) = viewModelScope.launch { c.settings.setOnlineLookup(v) }

    fun saveTag(tag: Tag?, name: String, color: Int) = viewModelScope.launch {
        if (tag == null) c.repo.getOrCreateTag(name, color)
        else runCatching { c.repo.updateTag(tag.copy(name = name, color = color)) }
            .onFailure { _messages.emit("A label with that name already exists") }
    }

    fun deleteTag(tag: Tag) = viewModelScope.launch {
        val used = c.repo.tagUsage(tag.id)
        c.repo.deleteTag(tag)
        _messages.emit("Deleted “${tag.name}”" + if (used > 0) ", removed from $used entries" else "")
    }

    fun lookupMissing() = viewModelScope.launch {
        _messages.emit("Looking up missing place names…")
        c.recorder.processPendingLookups(limit = 50)
        _messages.emit("Done")
    }

    fun exportJson(uri: Uri) = runIo("Backed up %d entries") { c.backup.exportJson(uri) }
    fun exportCsv(uri: Uri) = runIo("Exported %d entries") { c.backup.exportCsv(uri) }
    fun importJson(uri: Uri) = viewModelScope.launch {
        runCatching { c.backup.importJson(uri) }
            .onSuccess { _messages.emit("Imported ${it.visits} entries, ${it.places} places, ${it.tags} labels") }
            .onFailure { _messages.emit("Import failed: ${it.message}") }
    }
    fun importCsv(uri: Uri) = viewModelScope.launch {
        runCatching { c.backup.importCsv(uri) }
            .onSuccess { _messages.emit("Imported ${it.visits} entries") }
            .onFailure { _messages.emit("Import failed: ${it.message}") }
    }

    private fun runIo(success: String, block: suspend () -> Int) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { _messages.emit(success.format(it)) }
            .onFailure { _messages.emit("Failed: ${it.message}") }
    }
}

@Composable
fun SettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            TrackingSection(vm, s)
            TuningSection(vm, s)
            LabelsSection(vm, tags)
            DataSection(vm)
            AboutSection()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun TrackingSection(vm: SettingsViewModel, s: AppSettings) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var startAfterGrant by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    // Re-read on every resume, since permissions are granted in system screens.
    val hasLocation = remember(refresh) { Permissions.hasLocation(context) }
    val hasFine = remember(refresh) { Permissions.hasFineLocation(context) }
    val hasBackground = remember(refresh) { Permissions.hasBackgroundLocation(context) }
    val canNotify = remember(refresh) { Permissions.canPostNotifications(context) }
    val batteryOk = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(context) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        if (startAfterGrant && result.values.any { it }) vm.startTracking(context)
        startAfterGrant = false
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    SectionTitle("Tracking")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (s.trackingEnabled && hasLocation) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record my location", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.trackingEnabled) "Checking every ${s.intervalMinutes} min, all day. A new entry is added only when you move more than ${s.distanceThresholdMeters} m."
                    else "Off. Turn on to record where you go, 24/7.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = s.trackingEnabled,
                onCheckedChange = { on ->
                    if (!on) vm.stopTracking(context)
                    else if (!hasLocation) {
                        startAfterGrant = true
                        locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    } else vm.startTracking(context)
                },
            )
        }
    }

    Text(
        "For reliable 24/7 recording, all of these should be green:",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    PermissionRow(
        ok = hasFine,
        title = "Precise location",
        detail = if (hasLocation && !hasFine) "Only approximate location is allowed; places will be imprecise." else "Needed to know where you are.",
        action = "Allow",
    ) {
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PermissionRow(
            ok = hasBackground,
            title = "Location “Allow all the time”",
            detail = "Lets tracking restart by itself after a reboot. Choose “Allow all the time” on the next screen.",
            action = "Allow",
            enabled = hasLocation,
        ) {
            // On Android 11+ this opens the app's location permission page.
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(
            ok = canNotify,
            title = "Notifications",
            detail = "Shows the “recording” notification and warns you if tracking stops.",
            action = "Allow",
        ) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    PermissionRow(
        ok = batteryOk,
        title = "Unrestricted battery use",
        detail = "Stops Android from pausing tracking to save battery.",
        action = "Allow",
    ) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
    TextButton(onClick = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }) { Text("Open app settings") }
}

@Composable
private fun PermissionRow(
    ok: Boolean,
    title: String,
    detail: String,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (ok) "Granted" else "Missing",
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun TuningSection(vm: SettingsViewModel, s: AppSettings) {
    SectionTitle("How tracking works")
    SliderSetting(
        title = "Check location every",
        value = s.intervalMinutes,
        range = 5..60,
        step = 5,
        format = { "$it min (${60 / it}× per hour)" },
        help = "15 minutes = 4 times an hour. Shorter uses more battery.",
        onCommit = { vm.setInterval(it) },
    )
    SliderSetting(
        title = "New entry when I move more than",
        value = s.distanceThresholdMeters,
        range = 50..1000,
        step = 25,
        format = { "$it m" },
        help = "Smaller catches nearby stops but GPS drift indoors (50–100 m) may create extra entries.",
        onCommit = { vm.setDistance(it) },
    )
    SliderSetting(
        title = "Ignore fixes less accurate than",
        value = s.maxAccuracyMeters,
        range = 50..500,
        step = 25,
        format = { "$it m" },
        help = "Rough network-only fixes above this are skipped.",
        onCommit = { vm.setMaxAccuracy(it) },
    )
    SwitchSetting("High accuracy (GPS)", "Uses GPS for each check. Off = Wi-Fi/cell only, saves battery, less precise.", s.highAccuracy, vm::setHighAccuracy)
    SwitchSetting("Filter GPS glitches", "Removes one-off jumps that immediately return to the previous place.", s.filterGlitches, vm::setFilterGlitches)
    SwitchSetting(
        "Look up place names online",
        "Names like “City University” and addresses from OpenStreetMap. Sends the coordinates of new places to OpenStreetMap's servers.",
        s.onlineLookup,
        vm::setOnlineLookup,
    )
    TextButton(onClick = { vm.lookupMissing() }) { Text("Look up missing place names now") }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    help: String,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(local.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = ((it / step).roundToInt() * step).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()) },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchSetting(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabelsSection(vm: SettingsViewModel, tags: List<Tag>) {
    var editing by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    SectionTitle("Labels")
    if (tags.isEmpty()) {
        Text("No labels yet. Create them here or while editing an entry.", style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { editing = tag },
                label = { Text(tag.name) },
                leadingIcon = { Box(Modifier.size(10.dp).background(Color(tag.color), CircleShape)) },
            )
        }
        AssistChip(
            onClick = { creating = true },
            label = { Text("New label") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
    }
    editing?.let { tag ->
        TagEditDialog(
            initialName = tag.name,
            initialColor = tag.color,
            title = "Edit label",
            onDismiss = { editing = null },
            onConfirm = { name, color -> vm.saveTag(tag, name, color); editing = null },
            onDelete = { vm.deleteTag(tag); editing = null },
        )
    }
    if (creating) {
        TagEditDialog(
            initialName = "",
            initialColor = null,
            title = "New label",
            onDismiss = { creating = false },
            onConfirm = { name, color -> vm.saveTag(null, name, color); creating = false },
        )
    }
}

@Composable
private fun DataSection(vm: SettingsViewModel) {
    val today = LocalDate.now()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(vm::exportJson)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        it?.let(vm::exportCsv)
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importJson) }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importCsv) }

    SectionTitle("Backup & export")
    Text(
        "JSON is a full backup (entries, places, labels) and can be imported on another phone. " +
            "CSV opens in any spreadsheet; importing CSV adds entries (start, end, latitude, longitude columns required).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { exportJson.launch("location-journal-backup-$today.json") }) { Text("Export JSON") }
        OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import JSON") }
        OutlinedButton(onClick = { exportCsv.launch("location-journal-$today.csv") }) { Text("Export CSV") }
        OutlinedButton(onClick = {
            importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
        }) { Text("Import CSV") }
    }
}

@Composable
private fun AboutSection() {
    SectionTitle("About")
    HorizontalDivider()
    Text(
        "Your journal is stored only on this phone. Place names and addresses come from OpenStreetMap " +
            "(Nominatim and Overpass) and the Android geocoder; map tiles © OpenStreetMap contributors.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
