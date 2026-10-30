package dev.piatra.ui

import android.text.format.DateFormat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Picking a reminder time: a date picker, then a time picker (like upstream's DueAtRow).
// initial: the current time to start from (epoch millis). onPicked gets the new time.
// Cancel at either step changes nothing.
@OptIn(ExperimentalMaterial3Api::class) // Material marks the pickers as still changing
@Composable
fun DueTimePicker(initial: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val zone = TimeZone.currentSystemDefault()
    val start = Instant.fromEpochMilliseconds(initial).toLocalDateTime(zone)
    // null: still picking the date. Then the picked day, while the time picker shows.
    var pickedDay by remember { mutableStateOf<LocalDate?>(null) }

    val day = pickedDay
    if (day == null) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = datePickerMillis(start.date))
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { pickedDay = dateFromPicker(it) }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    } else {
        // 12 or 24 hours, as the phone is set.
        val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
        val timeState = rememberTimePickerState(start.hour, start.minute, is24Hour)
        TimePickerDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time") },
            confirmButton = {
                TextButton(onClick = { onPicked(combine(day, timeState.hour, timeState.minute, zone)) }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { TimePicker(state = timeState) }
    }
}

// The date picker speaks in "midnight UTC of the chosen day", whatever the phone's timezone.
// Reading it as a local time would give the day before for anyone west of UTC.
internal fun datePickerMillis(day: LocalDate): Long = day.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun dateFromPicker(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

// The picked day and time, in the phone's timezone, as epoch millis.
internal fun combine(day: LocalDate, hour: Int, minute: Int, zone: TimeZone): Long =
    LocalDateTime(day, LocalTime(hour, minute)).toInstant(zone).toEpochMilliseconds()
