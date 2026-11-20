package example.reservation.ui

import example.reservation.core.EpochMillis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
private val Compact = DateTimeFormatter.ofPattern("M月d日(E) HH:mm", Locale.JAPAN).withZone(Tokyo)
private val Precise = DateTimeFormatter.ofPattern("M月d日(E) HH:mm:ss", Locale.JAPAN).withZone(Tokyo)

fun formatStart(time: EpochMillis): String = Compact.format(Instant.ofEpochMilli(time.value))

fun formatPrecise(time: EpochMillis): String = Precise.format(Instant.ofEpochMilli(time.value))
