package example.reservation.core

import example.reservation.ports.ParticipantId
import example.reservation.ports.SessionId

@JvmInline value class EpochMillis(val value: Long) : Comparable<EpochMillis> {
    override fun compareTo(other: EpochMillis): Int = value.compareTo(other.value)
}

fun interface Clock { fun now(): EpochMillis }

data class Workshop(val id: SessionId, val name: String, val start: EpochMillis, val seatsHint: Int)

data class Participant(val id: ParticipantId, val name: String)

class Catalog(val workshops: List<Workshop>, val participants: List<Participant>)
