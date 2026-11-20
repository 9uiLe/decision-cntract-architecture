package example.reservation.ui

import example.reservation.core.CancelFailure
import example.reservation.core.CancelTicket
import example.reservation.core.ConfirmedDraft
import example.reservation.core.EpochMillis
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket
import example.reservation.ports.ParticipantId
import example.reservation.ports.SessionId

data class WorkshopItem(val id: SessionId, val name: String, val start: EpochMillis, val seatsHint: Int)

data class BookingSummary(val workshopName: String, val start: EpochMillis, val participantNames: List<String>)

data class ParticipantRow(val id: ParticipantId, val name: String, val selected: Boolean)

sealed interface DetailsStage {
    data object Editing : DetailsStage
    class Confirmed(val draft: ConfirmedDraft) : DetailsStage
}

data class DetailsState(
    val workshop: WorkshopItem?,
    val participants: List<ParticipantRow>,
    val stage: DetailsStage,
    val canConfirm: Boolean,
)

sealed interface RequestCard {
    val summary: BookingSummary

    data class Sending(override val summary: BookingSummary) : RequestCard
    data class CheckingStatus(override val summary: BookingSummary) : RequestCard
    class Unknown(override val summary: BookingSummary, val check: StatusCheckTicket) : RequestCard
    class NotCreated(override val summary: BookingSummary, val retry: RetryTicket) : RequestCard
    data class SoldOut(override val summary: BookingSummary) : RequestCard
    data class RegistrationClosed(override val summary: BookingSummary) : RequestCard
}

sealed interface ReservationCard {
    val summary: BookingSummary
    val reservationId: String

    class Reserved(
        override val summary: BookingSummary,
        override val reservationId: String,
        val cancel: CancelTicket,
        val lastCancelFailure: CancelFailure?,
    ) : ReservationCard

    data class Cancelling(override val summary: BookingSummary, override val reservationId: String) : ReservationCard
    data class Cancelled(override val summary: BookingSummary, override val reservationId: String) : ReservationCard
}

data class StatusState(val request: RequestCard?, val reservations: List<ReservationCard>)

data class ListState(val workshops: List<WorkshopItem>, val ongoing: StatusState)

data class ReservationScreenState(val list: ListState, val details: DetailsState, val status: StatusState)
