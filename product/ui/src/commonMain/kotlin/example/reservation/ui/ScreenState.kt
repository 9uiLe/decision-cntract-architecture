package example.reservation.ui

import example.checkin.core.CheckInRetryTicket
import example.checkin.core.CheckInStatusTicket
import example.checkin.core.Denial
import example.checkin.core.ProcessingStage
import example.eligibility.core.ReverificationFailure
import example.kernel.DecisionResult
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.CancelFailure
import example.reservation.core.CancelTicket
import example.reservation.core.ConfirmedDraft
import example.reservation.core.CreateDenial
import example.reservation.core.ReservationAdmission
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket

@DecisionResult
sealed interface RequirementView {
    data object NotRequired : RequirementView
    data class Required(val qualificationName: String) : RequirementView
}

data class WorkshopItem(val id: SessionId, val name: String, val start: EpochMillis, val seatsHint: Int, val requirement: RequirementView)

data class BookingSummary(val workshopName: String, val start: EpochMillis, val participantNames: List<String>)

@DecisionResult
enum class QualificationBadge { NotChecked, Checking, Valid, NotHeld, Expired, Unknown }

data class ParticipantRow(val id: ParticipantId, val name: String, val selected: Boolean, val qualification: QualificationBadge?)

@DecisionResult
sealed interface DetailsStage {
    data object Editing : DetailsStage
    class Confirmed(val draft: ConfirmedDraft) : DetailsStage
}

data class DetailsState(
    val workshop: WorkshopItem?,
    val participants: List<ParticipantRow>,
    val stage: DetailsStage,
    val canConfirm: Boolean,
    val canRequestEligibility: Boolean,
)

@DecisionResult
sealed interface RequestCard {
    val summary: BookingSummary

    data class Sending(override val summary: BookingSummary) : RequestCard
    data class CheckingStatus(override val summary: BookingSummary) : RequestCard
    class Unknown(override val summary: BookingSummary, val check: StatusCheckTicket) : RequestCard
    class NotCreated(override val summary: BookingSummary, val retry: RetryTicket, val denial: CreateDenial?) : RequestCard
    data class SoldOut(override val summary: BookingSummary) : RequestCard
    data class RegistrationClosed(override val summary: BookingSummary) : RequestCard
    data class EligibilityLost(override val summary: BookingSummary, val reason: ReverificationFailure) : RequestCard
}

@DecisionResult
sealed interface CheckInView {
    data object NotCheckedIn : CheckInView
    data class Processing(val stage: ProcessingStage) : CheckInView
    data object CheckedIn : CheckInView
    class Unknown(val check: CheckInStatusTicket) : CheckInView
    class NotCreated(val retry: CheckInRetryTicket) : CheckInView
    class NotAdmitted(val denial: Denial) : CheckInView
}

class ParticipantCheckInRow(val name: String, val admission: ReservationAdmission, val view: CheckInView)

@DecisionResult
sealed interface ReservationCard {
    val summary: BookingSummary
    val reservationId: String

    class Reserved(
        override val summary: BookingSummary,
        override val reservationId: String,
        val cancel: CancelTicket,
        val lastCancelFailure: CancelFailure?,
        val participants: List<ParticipantCheckInRow>,
    ) : ReservationCard

    data class Cancelling(override val summary: BookingSummary, override val reservationId: String) : ReservationCard
    data class Cancelled(override val summary: BookingSummary, override val reservationId: String) : ReservationCard
}

data class StatusState(val request: RequestCard?, val reservations: List<ReservationCard>)

data class ListState(val workshops: List<WorkshopItem>, val ongoing: StatusState)

data class ReservationScreenState(val list: ListState, val details: DetailsState, val status: StatusState)
