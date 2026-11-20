package example.reservation.ui

import example.checkin.core.CheckInKey
import example.checkin.core.CheckInSnapshot
import example.checkin.core.ParticipantCheckIn
import example.eligibility.core.EligibilityKey
import example.eligibility.core.EligibilityOutcome
import example.eligibility.core.EligibilitySnapshot
import example.eligibility.core.EligibilityStatus
import example.kernel.ParticipantId
import example.reservation.core.ActiveRequest
import example.reservation.core.Booking
import example.reservation.core.Catalog
import example.reservation.core.Confirmation
import example.reservation.core.CoreSnapshot
import example.reservation.core.NewBookingRequirement
import example.reservation.core.ReservationRecord
import example.reservation.core.Workshop

fun project(
    snapshot: CoreSnapshot,
    eligibility: EligibilitySnapshot,
    checkIn: CheckInSnapshot,
    catalog: Catalog,
): ReservationScreenState {
    val names = catalog.participants.associate { it.id to it.name }
    val status = StatusState(
        request = projectRequest(snapshot.request, names),
        reservations = snapshot.reservations.map { projectReservation(it, snapshot.request, names, checkIn) },
    )
    val draft = snapshot.draft
    val kind = (draft.workshop?.requirement as? NewBookingRequirement.Required)?.kind
    val rows = catalog.participants.map { participant ->
        val badge = kind?.let { projectBadge(eligibility.statuses[EligibilityKey(participant.id, it)] ?: EligibilityStatus.NotChecked) }
        ParticipantRow(participant.id, participant.name, participant.id in draft.participants, badge)
    }
    val selected = rows.filter { it.selected }
    return ReservationScreenState(
        list = ListState(catalog.workshops.map { it.toItem() }, status),
        details = DetailsState(
            workshop = draft.workshop?.toItem(),
            participants = rows,
            stage = projectStage(draft.confirmation),
            canConfirm = draft.workshop != null && selected.isNotEmpty() &&
                (kind == null || selected.all { it.qualification == QualificationBadge.Valid }),
            canRequestEligibility = kind != null && selected.isNotEmpty(),
        ),
        status = status,
    )
}

fun projectStage(confirmation: Confirmation): DetailsStage = when (confirmation) {
    Confirmation.Unconfirmed -> DetailsStage.Editing
    is Confirmation.Confirmed -> DetailsStage.Confirmed(confirmation.draft)
}

fun projectRequirement(requirement: NewBookingRequirement): RequirementView = when (requirement) {
    NewBookingRequirement.NotRequired -> RequirementView.NotRequired
    is NewBookingRequirement.Required -> RequirementView.Required(requirement.kind.name)
}

fun projectBadge(status: EligibilityStatus): QualificationBadge = when (status) {
    EligibilityStatus.NotChecked -> QualificationBadge.NotChecked
    EligibilityStatus.Checking -> QualificationBadge.Checking
    is EligibilityStatus.Settled -> projectOutcome(status.outcome)
}

fun projectOutcome(outcome: EligibilityOutcome): QualificationBadge = when (outcome) {
    is EligibilityOutcome.Eligible -> QualificationBadge.Valid
    EligibilityOutcome.NotHeld -> QualificationBadge.NotHeld
    EligibilityOutcome.Expired -> QualificationBadge.Expired
    EligibilityOutcome.Unknown -> QualificationBadge.Unknown
}

fun projectRequest(request: ActiveRequest?, names: Map<ParticipantId, String>): RequestCard? = when (request) {
    null -> null
    is ActiveRequest.Creating -> RequestCard.Sending(request.booking.summary(names))
    is ActiveRequest.CheckingStatus -> RequestCard.CheckingStatus(request.booking.summary(names))
    is ActiveRequest.Unknown -> RequestCard.Unknown(request.booking.summary(names), request.check)
    is ActiveRequest.NotCreated -> RequestCard.NotCreated(request.booking.summary(names), request.retry, request.lastDenial)
    is ActiveRequest.SoldOut -> RequestCard.SoldOut(request.booking.summary(names))
    is ActiveRequest.RegistrationClosed -> RequestCard.RegistrationClosed(request.booking.summary(names))
    is ActiveRequest.EligibilityLost -> RequestCard.EligibilityLost(request.booking.summary(names), request.reason)
    is ActiveRequest.Cancelling -> projectRequest(request.resting, names)
}

fun projectReservation(
    record: ReservationRecord,
    request: ActiveRequest?,
    names: Map<ParticipantId, String>,
    checkIn: CheckInSnapshot,
): ReservationCard {
    val summary = record.booking.summary(names)
    val id = record.reservation.value
    return when (record) {
        is ReservationRecord.Reserved ->
            if (request is ActiveRequest.Cancelling && request.reservation == record.reservation) {
                ReservationCard.Cancelling(summary, id)
            } else {
                ReservationCard.Reserved(
                    summary, id, record.cancel, record.lastCancelFailure,
                    record.admissions.map { admission ->
                        ParticipantCheckInRow(
                            names[admission.participant] ?: admission.participant.value,
                            admission,
                            projectCheckIn(checkIn.entries[CheckInKey(record.reservation, admission.participant)]),
                        )
                    },
                )
            }
        is ReservationRecord.Cancelled -> ReservationCard.Cancelled(summary, id)
    }
}

fun projectCheckIn(entry: ParticipantCheckIn?): CheckInView = when (entry) {
    null -> CheckInView.NotCheckedIn
    is ParticipantCheckIn.Processing -> CheckInView.Processing(entry.stage)
    ParticipantCheckIn.CheckedIn -> CheckInView.CheckedIn
    is ParticipantCheckIn.Unknown -> CheckInView.Unknown(entry.check)
    is ParticipantCheckIn.NotCreated -> CheckInView.NotCreated(entry.retry)
    is ParticipantCheckIn.NotAdmitted -> CheckInView.NotAdmitted(entry.denial)
}

private fun Workshop.toItem() = WorkshopItem(id, name, start, seatsHint, projectRequirement(requirement))

private fun Booking.summary(names: Map<ParticipantId, String>) = BookingSummary(
    workshopName = workshop.name,
    start = workshop.start,
    participantNames = participants.map { names[it] ?: it.value },
)
