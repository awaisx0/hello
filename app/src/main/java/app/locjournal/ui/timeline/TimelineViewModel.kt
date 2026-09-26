package app.locjournal.ui.timeline

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.locjournal.AppContainer
import app.locjournal.data.AppSettings
import app.locjournal.data.Tag
import app.locjournal.data.Visit
import app.locjournal.data.VisitWithDetails
import app.locjournal.ui.common.DateSelection
import app.locjournal.util.DaySegment
import app.locjournal.util.DaySplitter
import app.locjournal.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TimelineEntry(
    val segment: DaySegment<VisitWithDetails>,
    val ongoing: Boolean,
    /** 1-based position in the whole selection, used for map pins. */
    val number: Int,
) {
    val details: VisitWithDetails get() = segment.item
    val visit: Visit get() = segment.item.visit
}

data class DayGroup(val date: LocalDate, val entries: List<TimelineEntry>, val recordedMillis: Long)

data class TimelineUiState(
    val loading: Boolean = true,
    val selection: DateSelection? = null,
    val days: List<DayGroup> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val filtered: Boolean = false,
) {
    val entryCount: Int get() = days.sumOf { it.entries.size }
}

class TimelineViewModel(private val c: AppContainer) : ViewModel() {
    private val selection = MutableStateFlow<DateSelection?>(null)
    val query = MutableStateFlow("")
    val tagFilter = MutableStateFlow<Long?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val tags: StateFlow<List<Tag>> = c.repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    private val visitsForSelection = selection.filterNotNull().flatMapLatest { sel ->
        val (from, to) = sel.millisRange()
        c.repo.observeRange(from, to).map { sel to it }
    }

    private val liveContext = combine(c.settings.settings, c.repo.observeLatestTracked(), ticker) { s, latest, now ->
        Triple(s, latest, now)
    }

    val state: StateFlow<TimelineUiState> =
        combine(visitsForSelection, query, tagFilter, liveContext) { (sel, visits), q, tagId, (settings, latest, now) ->
            build(sel, visits, q.trim(), tagId, settings, latest, now)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun setSelection(sel: DateSelection) {
        selection.value = sel
    }

    private fun build(
        sel: DateSelection,
        visits: List<VisitWithDetails>,
        q: String,
        tagId: Long?,
        settings: AppSettings,
        latest: Visit?,
        now: Long,
    ): TimelineUiState {
        val ongoingWindow = (settings.intervalMinutes * 2 + 5) * 60_000L
        fun isOngoing(v: Visit) =
            settings.trackingEnabled && latest != null && v.id == latest.id && now - v.endTime < ongoingWindow

        val filtered = visits.filter { d ->
            (tagId == null || d.tags.any { it.id == tagId }) && (q.isEmpty() || matches(d, q))
        }
        val numbers = filtered.withIndex().associate { (i, d) -> d.visit.id to i + 1 }
        val byDay = DaySplitter.split(filtered, sel.from, sel.to, TimeUtils.zone) { d ->
            d.visit.startTime to (if (isOngoing(d.visit)) now else d.visit.endTime)
        }
        val days = byDay.entries.sortedByDescending { it.key }.map { (date, segments) ->
            val entries = segments.sortedBy { it.start }.map { seg ->
                TimelineEntry(seg, isOngoing(seg.item.visit), numbers[seg.item.visit.id] ?: 0)
            }
            DayGroup(date, entries, entries.sumOf { it.segment.duration })
        }
        return TimelineUiState(
            loading = false,
            selection = sel,
            days = days,
            settings = settings,
            filtered = tagId != null || q.isNotEmpty(),
        )
    }

    private fun matches(d: VisitWithDetails, q: String): Boolean {
        val haystack = buildList {
            add(d.displayTitle)
            add(d.visit.placeName)
            add(d.visit.address)
            add(d.visit.purpose)
            add(d.visit.notes)
            add(d.visit.placeCategory)
            add(d.savedPlace?.name)
            addAll(d.tags.map { it.name })
            addAll(d.visit.extras.values)
            addAll(d.visit.extras.keys)
        }
        return haystack.any { it?.contains(q, ignoreCase = true) == true }
    }

    fun exportCsv(uri: Uri) {
        val sel = selection.value ?: return
        viewModelScope.launch {
            val (from, to) = sel.millisRange()
            val msg = runCatching { c.backup.exportCsv(uri, from, to) }
                .fold({ "Exported $it entries" }, { "Export failed: ${it.message}" })
            _messages.emit(msg)
        }
    }
}
