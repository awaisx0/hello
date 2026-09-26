package app.locjournal.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.locjournal.data.Tag
import app.locjournal.data.TagColors
import app.locjournal.util.TimeUtils
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Small colored label chip used on timeline cards. */
@Composable
fun TagPill(tag: Tag, modifier: Modifier = Modifier) {
    val color = Color(tag.color)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            " " + tag.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Toggleable label chips plus a "New label" chip. */
@Composable
fun TagSelector(
    allTags: List<Tag>,
    selected: Set<Long>,
    onToggle: (Tag) -> Unit,
    onCreate: (name: String, color: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNew by remember { mutableStateOf(false) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        allTags.forEach { tag ->
            val isSel = tag.id in selected
            FilterChip(
                selected = isSel,
                onClick = { onToggle(tag) },
                label = { Text(tag.name) },
                leadingIcon = {
                    if (isSel) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    else Box(Modifier.size(10.dp).clip(CircleShape).background(Color(tag.color)))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(tag.color).copy(alpha = 0.25f),
                ),
            )
        }
        InputChip(
            selected = false,
            onClick = { showNew = true },
            label = { Text("New label") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
    }
    if (showNew) {
        TagEditDialog(
            initialName = "",
            initialColor = null,
            title = "New label",
            onDismiss = { showNew = false },
            onConfirm = { name, color ->
                onCreate(name, color)
                showNew = false
            },
        )
    }
}

@Composable
fun TagEditDialog(
    initialName: String,
    initialColor: Int?,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Work, Study, Family)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val effective = color ?: TagColors.forName(name)
                    TagColors.palette.forEach { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == effective) Icon(Icons.Default.Check, "Selected", tint = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), color ?: TagColors.forName(name.trim())) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Date picker followed by a time picker. Works in local time. */
@Composable
fun DateTimePickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
) {
    val initial = TimeUtils.toLocalDateTime(initialMillis)
    var pickedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    if (pickedDate == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: initial.toLocalDate()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    } else {
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val dt = LocalDateTime.of(pickedDate!!, java.time.LocalTime.of(state.hour, state.minute))
                    onPicked(TimeUtils.toMillis(dt))
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
    )
}
