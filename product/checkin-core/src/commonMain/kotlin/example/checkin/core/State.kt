package example.checkin.core

import example.kernel.DecisionResult
import example.kernel.IssuedEvidence
import example.kernel.ParticipantId
import example.reservation.core.AdmissionUnavailableReason
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationId

data class CheckInKey(val reservation: ReservationId, val participant: ParticipantId)

@IssuedEvidence
class CheckInStatusTicket internal constructor(
    internal val attempt: CheckInAttemptId,
    internal val admission: ReservationAdmission,
)

@IssuedEvidence
class CheckInRetryTicket internal constructor(
    internal val admission: ReservationAdmission,
)

// 受付できない理由。受付結果不明や未成立の確定とは別の状態として扱う。
@DecisionResult
sealed interface Denial {
    class ReservationUnavailable(val reason: AdmissionUnavailableReason) : Denial
    data object ReservationUnconfirmed : Denial
    data object NotYetOpen : Denial
    data object Closed : Denial
    data object WrongTarget : Denial
}

@DecisionResult
enum class ProcessingStage { ConfirmingReservation, Recording, LookingUp }

// 参加者ごとの受付状態。記録が無い参加者は未受付。
@DecisionResult
sealed interface ParticipantCheckIn {
    data class Processing(val stage: ProcessingStage) : ParticipantCheckIn
    data object CheckedIn : ParticipantCheckIn
    class Unknown internal constructor(val check: CheckInStatusTicket) : ParticipantCheckIn
    class NotCreated internal constructor(val retry: CheckInRetryTicket) : ParticipantCheckIn
    class NotAdmitted internal constructor(val denial: Denial, internal val admission: ReservationAdmission) : ParticipantCheckIn
}

data class CheckInSnapshot(val entries: Map<CheckInKey, ParticipantCheckIn>)

@DecisionResult
enum class CheckInRejection { Busy, AlreadyCheckedIn, Unresolved, Stale, NotYetOpen, Closed }

@DecisionResult
sealed interface CheckInAcceptance {
    data object Accepted : CheckInAcceptance
    data class Rejected(val reason: CheckInRejection) : CheckInAcceptance
}
