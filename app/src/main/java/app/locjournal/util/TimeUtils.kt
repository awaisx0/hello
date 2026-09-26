package app.locjournal.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object TimeUtils {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun startOfDay(date: LocalDate, zone: ZoneId = this.zone): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun toLocalDate(millis: Long, zone: ZoneId = this.zone): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun toLocalDateTime(millis: Long, zone: ZoneId = this.zone): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    fun toMillis(dateTime: LocalDateTime, zone: ZoneId = this.zone): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy")
    private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    val isoDateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun formatTime(millis: Long): String = toLocalDateTime(millis).format(timeFmt)
    fun formatDayHeader(date: LocalDate): String = date.format(dayHeaderFmt)
    fun formatShortDate(date: LocalDate): String = date.format(shortDateFmt)
    fun formatDateTime(millis: Long): String = toLocalDateTime(millis).format(dateTimeFmt)

    fun formatDuration(millis: Long): String {
        val totalMin = (millis / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

/** A piece of a visit that falls inside one calendar day. */
data class DaySegment<T>(
    val item: T,
    val date: LocalDate,
    val start: Long,
    val end: Long,
    /** The visit started before this day. */
    val continuedFromPreviousDay: Boolean,
    /** The visit continues past this day. */
    val continuesNextDay: Boolean,
) {
    val duration: Long get() = end - start
}

object DaySplitter {
    /**
     * Splits intervals into per-day segments limited to [from]..[to] (inclusive dates), so a stay
     * from 22:00 to 08:00 shows up under both days with the correct time on each.
     */
    fun <T> split(
        items: List<T>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        interval: (T) -> Pair<Long, Long>,
    ): Map<LocalDate, List<DaySegment<T>>> {
        val result = sortedMapOf<LocalDate, MutableList<DaySegment<T>>>()
        for (item in items) {
            val (start, rawEnd) = interval(item)
            val end = maxOf(start, rawEnd)
            var day = maxOf(TimeUtils.toLocalDate(start, zone), from)
            val lastDay = minOf(TimeUtils.toLocalDate(end, zone), to)
            while (!day.isAfter(lastDay)) {
                val dayStart = TimeUtils.startOfDay(day, zone)
                val dayEnd = TimeUtils.startOfDay(day.plusDays(1), zone)
                val segStart = maxOf(start, dayStart)
                val segEnd = minOf(end, dayEnd)
                // Zero-length visits (single sample) still count, as long as they start inside the day.
                if (segEnd > segStart || (start >= dayStart && start < dayEnd)) {
                    result.getOrPut(day) { mutableListOf() }.add(
                        DaySegment(
                            item = item,
                            date = day,
                            start = segStart,
                            end = segEnd,
                            continuedFromPreviousDay = start < dayStart,
                            continuesNextDay = end > dayEnd,
                        ),
                    )
                }
                day = day.plusDays(1)
            }
        }
        return result
    }
}
