package example.reservation.core

import example.eligibility.core.QualificationKind
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId

// 新しい予約に必要な条件。成立済みの予約がどの条件で成立したかは BookedBasis が別に持つ。
sealed interface NewBookingRequirement {
    data object NotRequired : NewBookingRequirement
    data class Required(val kind: QualificationKind) : NewBookingRequirement
}

data class Workshop(
    val id: SessionId,
    val name: String,
    val start: EpochMillis,
    val seatsHint: Int,
    val requirement: NewBookingRequirement,
)

data class Participant(val id: ParticipantId, val name: String)

class Catalog(val workshops: List<Workshop>, val participants: List<Participant>)
