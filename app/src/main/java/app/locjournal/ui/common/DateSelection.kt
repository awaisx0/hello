package app.locjournal.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import app.locjournal.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface DateSelection {
    val from: LocalDate
    val to: LocalDate

    data class Day(val date: LocalDate) : DateSelection {
        override val from: LocalDate get() = date
        override val to: LocalDate get() = date
    }

    data class Range(override val from: LocalDate, override val to: LocalDate) : DateSelection

    /** Epoch millis [start of first day, start of the day after the last). */
    fun millisRange(): Pair<Long, Long> =
        TimeUtils.startOfDay(from) to TimeUtils.startOfDay(to.plusDays(1))

    fun label(): String = when (this) {
        is Day -> when (date) {
            LocalDate.now() -> "Today · ${TimeUtils.formatShortDate(date)}"
            LocalDate.now().minusDays(1) -> "Yesterday · ${TimeUtils.formatShortDate(date)}"
            else -> TimeUtils.formatShortDate(date) + " " + date.year
        }
        is Range -> {
            val f = DateTimeFormatter.ofPattern("d MMM yyyy")
            "${from.format(f)} – ${to.format(f)}"
        }
    }
}

/** Shared between Timeline and Stats so both show the same day/range. */
class SelectionViewModel : ViewModel() {
    private val _selection = MutableStateFlow<DateSelection>(DateSelection.Day(LocalDate.now()))
    val selection: StateFlow<DateSelection> = _selection.asStateFlow()

    fun select(selection: DateSelection) {
        _selection.value = selection
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.utcToLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
fun DateSelectionBar(
    selection: DateSelection,
    onSelect: (DateSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDayPicker by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selection is DateSelection.Day,
                onClick = { if (selection !is DateSelection.Day) onSelect(DateSelection.Day(selection.to)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
            ) { Text("Day") }
            SegmentedButton(
                selected = selection is DateSelection.Range,
                onClick = {
                    if (selection !is DateSelection.Range) {
                        onSelect(DateSelection.Range(selection.to.minusDays(6), selection.to))
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
            ) { Text("Date range") }
        }

        when (selection) {
            is DateSelection.Day -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { onSelect(DateSelection.Day(selection.date.minusDays(1))) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
                }
                TextButton(onClick = { showDayPicker = true }) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Text("  " + selection.label(), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(
                    onClick = { onSelect(DateSelection.Day(selection.date.plusDays(1))) },
                    enabled = selection.date.isBefore(today),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                }
            }

            is DateSelection.Range -> Column {
                TextButton(onClick = { showRangePicker = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.DateRange, null)
                    Text("  " + selection.label(), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = listOf(
                        "Last 7 days" to DateSelection.Range(today.minusDays(6), today),
                        "Last 30 days" to DateSelection.Range(today.minusDays(29), today),
                        "This month" to DateSelection.Range(today.withDayOfMonth(1), today),
                        "Last month" to today.minusMonths(1).let {
                            DateSelection.Range(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()))
                        },
                        "This year" to DateSelection.Range(today.withDayOfYear(1), today),
                    )
                    presets.forEach { (name, range) ->
                        FilterChip(selected = selection == range, onClick = { onSelect(range) }, label = { Text(name) })
                    }
                }
            }
        }
        if (selection is DateSelection.Day && selection.date != today) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AssistChip(onClick = { onSelect(DateSelection.Day(today)) }, label = { Text("Jump to today") })
                Spacer8()
                AssistChip(onClick = { onSelect(DateSelection.Day(today.minusDays(1))) }, label = { Text("Yesterday") })
            }
        }
    }

    if (showDayPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selection.to.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDayPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onSelect(DateSelection.Day(it.utcToLocalDate())) }
                    showDayPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDayPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (showRangePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = selection.from.toUtcMillis(),
            initialSelectedEndDateMillis = selection.to.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = state.selectedStartDateMillis
                        if (s != null) {
                            val e = state.selectedEndDateMillis ?: s
                            onSelect(DateSelection.Range(s.utcToLocalDate(), e.utcToLocalDate()))
                        }
                        showRangePicker = false
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(
                state = state,
                modifier = Modifier.weight(1f),
                title = { Text("Select dates", Modifier.padding(start = 24.dp, top = 16.dp)) },
            )
        }
    }
}

@Composable
fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
