package example.reservation.ui

import example.kernel.EpochMillis
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val Tokyo = TimeZone.of("Asia/Tokyo")

fun formatStart(time: EpochMillis): String = time.inTokyo().let { "${it.date()} ${it.hourMinute()}" }

fun formatPrecise(time: EpochMillis): String = time.inTokyo().let { "${it.date()} ${it.hourMinute()}:${it.second.twoDigits()}" }

private fun EpochMillis.inTokyo(): LocalDateTime = Instant.fromEpochMilliseconds(value).toLocalDateTime(Tokyo)

private fun LocalDateTime.date() = "${month.ordinal + 1}月${day}日(${dayOfWeek.japanese()})"

private fun LocalDateTime.hourMinute() = "${hour.twoDigits()}:${minute.twoDigits()}"

private fun Int.twoDigits() = toString().padStart(2, '0')

private fun DayOfWeek.japanese(): String = when (this) {
    DayOfWeek.MONDAY -> "月"
    DayOfWeek.TUESDAY -> "火"
    DayOfWeek.WEDNESDAY -> "水"
    DayOfWeek.THURSDAY -> "木"
    DayOfWeek.FRIDAY -> "金"
    DayOfWeek.SATURDAY -> "土"
    DayOfWeek.SUNDAY -> "日"
}
