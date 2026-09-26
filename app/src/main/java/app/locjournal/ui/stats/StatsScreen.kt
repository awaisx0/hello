package app.locjournal.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.locjournal.AppContainer
import app.locjournal.data.VisitWithDetails
import app.locjournal.ui.common.DateSelection
import app.locjournal.ui.common.DateSelectionBar
import app.locjournal.ui.common.SectionTitle
import app.locjournal.ui.common.appViewModel
import app.locjournal.util.DaySplitter
import app.locjournal.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class StatRow(val name: String, val millis: Long, val count: Int, val color: Int? = null)

data class StatsState(
    val totalMillis: Long = 0,
    val entries: Int = 0,
    val places: Int = 0,
    val daysWithData: Int = 0,
    val byPlace: List<StatRow> = emptyList(),
    val byLabel: List<StatRow> = emptyList(),
    val byPurpose: List<StatRow> = emptyList(),
    val byCategory: List<StatRow> = emptyList(),
    val loaded: Boolean = false,
)

class StatsViewModel(private val c: AppContainer) : ViewModel() {
    private val selection = MutableStateFlow<DateSelection?>(null)

    fun setSelection(s: DateSelection) {
        selection.value = s
    }

    val state: StateFlow<StatsState> = selection.filterNotNull().flatMapLatest { sel ->
        val (from, to) = sel.millisRange()
        combine(c.repo.observeRange(from, to), c.repo.observeLatestTracked(), c.settings.settings) { visits, latest, s ->
            val now = System.currentTimeMillis()
            compute(sel, visits) { v ->
                val ongoing = s.trackingEnabled && latest?.id == v.visit.id &&
                    now - v.visit.endTime < (s.intervalMinutes * 2 + 5) * 60_000L
                if (ongoing) now else v.visit.endTime
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    private fun compute(sel: DateSelection, visits: List<VisitWithDetails>, endOf: (VisitWithDetails) -> Long): StatsState {
        // Clip each visit to the selected dates so a stay crossing midnight only counts the part inside.
        val segments = DaySplitter.split(visits, sel.from, sel.to, TimeUtils.zone) { it.visit.startTime to endOf(it) }
            .values.flatten()
        val perVisit = segments.groupBy { it.item.visit.id }.mapValues { (_, segs) -> segs.sumOf { it.duration } }
        val items = visits.filter { it.visit.id in perVisit }

        fun rows(keys: (VisitWithDetails) -> List<Pair<String, Int?>>): List<StatRow> {
            val acc = LinkedHashMap<String, Triple<Long, Int, Int?>>()
            for (v in items) {
                val ms = perVisit[v.visit.id] ?: 0L
                for ((k, color) in keys(v)) {
                    val (t, n, col) = acc[k] ?: Triple(0L, 0, color)
                    acc[k] = Triple(t + ms, n + 1, col)
                }
            }
            return acc.map { (k, v) -> StatRow(k, v.first, v.second, v.third) }.sortedByDescending { it.millis }
        }

        return StatsState(
            totalMillis = perVisit.values.sum(),
            entries = items.size,
            places = items.map { it.displayTitle }.distinct().size,
            daysWithData = segments.map { it.date }.distinct().size,
            byPlace = rows { listOf(it.displayTitle to null) },
            byLabel = rows { v ->
                if (v.tags.isEmpty()) listOf("No label" to null) else v.tags.map { it.name to it.color }
            },
            byPurpose = rows { listOf((it.visit.purpose?.takeIf { p -> p.isNotBlank() } ?: "Not set") to null) },
            byCategory = rows { v ->
                listOf((v.visit.placeCategory?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Unknown") to null)
            },
            loaded = true,
        )
    }
}

@Composable
fun StatsScreen(selection: DateSelection, onSelect: (DateSelection) -> Unit) {
    val vm = appViewModel { StatsViewModel(it) }
    LaunchedEffect(selection) { vm.setSelection(selection) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Stats") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            DateSelectionBar(selection, onSelect)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    StatTile("Recorded", TimeUtils.formatDuration(state.totalMillis), Modifier.weight(1f))
                    StatTile("Entries", state.entries.toString(), Modifier.weight(1f))
                    StatTile("Places", state.places.toString(), Modifier.weight(1f))
                }
                if (selection is DateSelection.Range && state.daysWithData > 0) {
                    Text(
                        "${state.daysWithData} days with data · average " +
                            TimeUtils.formatDuration(state.totalMillis / state.daysWithData) + " recorded per day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (state.loaded && state.entries == 0) {
                    Text(
                        "No entries in this period.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    BarSection("Time per place", state.byPlace, state.totalMillis)
                    BarSection("Time per label", state.byLabel, state.totalMillis)
                    BarSection("Time per purpose", state.byPurpose, state.totalMillis)
                    BarSection("Time per kind of place", state.byCategory, state.totalMillis)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Horizontal bars: one hue for magnitude; a label's own color appears only as a small dot beside its name. */
@Composable
private fun BarSection(title: String, rows: List<StatRow>, total: Long) {
    if (rows.isEmpty()) return
    var showAll by remember(rows) { mutableStateOf(false) }
    val max = rows.maxOf { it.millis }.coerceAtLeast(1)
    val shown = if (showAll) rows else rows.take(8)
    SectionTitle(title)
    shown.forEach { row ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.color?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it))) }
                Text(
                    (if (row.color != null) " " else "") + row.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pct = if (total > 0) row.millis * 100 / total else 0
                Text(
                    "${TimeUtils.formatDuration(row.millis)} · $pct% · ${row.count}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(top = 2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((row.millis.toFloat() / max).coerceIn(0.01f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
    if (rows.size > 8) {
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${rows.size}") }
    }
}
