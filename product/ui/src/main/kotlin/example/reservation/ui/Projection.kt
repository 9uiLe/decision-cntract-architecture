package example.reservation.ui

import example.reservation.core.ActiveRequest
import example.reservation.core.Catalog
import example.reservation.core.Confirmation
import example.reservation.core.ConfirmedDraft
import example.reservation.core.CoreSnapshot
import example.reservation.core.ReservationRecord
import example.reservation.core.Workshop
import example.reservation.ports.ParticipantId

fun project(snapshot: CoreSnapshot, catalog: Catalog): ReservationScreenState {
    val names = catalog.participants.associate { it.id to it.name }
    val status = StatusState(
        request = projectRequest(snapshot.request, names),
        reservations = snapshot.reservations.map { projectReservation(it, snapshot.request, names) },
    )
    val draft = snapshot.draft
    return ReservationScreenState(
        list = ListState(catalog.workshops.map { it.toItem() }, status),
        details = DetailsState(
            workshop = draft.workshop?.toItem(),
            participants = catalog.participants.map { ParticipantRow(it.id, it.name, it.id in draft.participants) },
            stage = projectStage(draft.confirmation),
            canConfirm = draft.workshop != null && draft.participants.isNotEmpty(),
        ),
        status = status,
    )
}

fun projectStage(confirmation: Confirmation): DetailsStage = when (confirmation) {
    Confirmation.Unconfirmed -> DetailsStage.Editing
    is Confirmation.Confirmed -> DetailsStage.Confirmed(confirmation.draft)
}

fun projectRequest(request: ActiveRequest?, names: Map<ParticipantId, String>): RequestCard? = when (request) {
    null -> null
    is ActiveRequest.Creating -> RequestCard.Sending(request.draft.summary(names))
    is ActiveRequest.CheckingStatus -> RequestCard.CheckingStatus(request.draft.summary(names))
    is ActiveRequest.Unknown -> RequestCard.Unknown(request.draft.summary(names), request.check)
    is ActiveRequest.NotCreated -> RequestCard.NotCreated(request.draft.summary(names), request.retry)
    is ActiveRequest.SoldOut -> RequestCard.SoldOut(request.draft.summary(names))
    is ActiveRequest.RegistrationClosed -> RequestCard.RegistrationClosed(request.draft.summary(names))
    is ActiveRequest.Cancelling -> projectRequest(request.resting, names)
}

fun projectReservation(
    record: ReservationRecord,
    request: ActiveRequest?,
    names: Map<ParticipantId, String>,
): ReservationCard {
    val summary = record.draft.summary(names)
    val id = record.reservation.value
    return when (record) {
        is ReservationRecord.Reserved ->
            if (request is ActiveRequest.Cancelling && request.reservation == record.reservation) {
                ReservationCard.Cancelling(summary, id)
            } else {
                ReservationCard.Reserved(summary, id, record.cancel, record.lastCancelFailure)
            }
        is ReservationRecord.Cancelled -> ReservationCard.Cancelled(summary, id)
    }
}

private fun Workshop.toItem() = WorkshopItem(id, name, start, seatsHint)

private fun ConfirmedDraft.summary(names: Map<ParticipantId, String>) = BookingSummary(
    workshopName = workshop.name,
    start = workshop.start,
    participantNames = participants.map { names[it] ?: it.value },
)
