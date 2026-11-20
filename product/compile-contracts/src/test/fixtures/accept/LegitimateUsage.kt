package contracts.accept

import example.checkin.core.CheckInAcceptance
import example.checkin.core.CheckInCore
import example.checkin.core.ParticipantCheckIn
import example.eligibility.core.EligibilityOutcome
import example.reservation.core.Acceptance
import example.reservation.core.AdmissionLookup
import example.reservation.core.AdmissionUnavailableReason
import example.reservation.core.ConfirmOutcome
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationCore

fun confirmAndSubmit(core: ReservationCore): Acceptance? = when (val outcome = core.confirmWithoutQualification()) {
    is ConfirmOutcome.Confirmed -> core.submit(outcome.draft)
    ConfirmOutcome.NoWorkshop, ConfirmOutcome.EmptyParticipants, ConfirmOutcome.QualificationRequired,
    ConfirmOutcome.QualificationNotRequired, is ConfirmOutcome.EligibilityNotMet -> null
}

fun checkInWithAdmission(core: CheckInCore, admission: ReservationAdmission): CheckInAcceptance = core.checkIn(admission)

fun describe(outcome: EligibilityOutcome): String = when (outcome) {
    is EligibilityOutcome.Eligible -> "eligible"
    EligibilityOutcome.NotHeld -> "not held"
    EligibilityOutcome.Expired -> "expired"
    EligibilityOutcome.Unknown -> "unknown"
}

fun isDone(state: ParticipantCheckIn?): Boolean = state == ParticipantCheckIn.CheckedIn

fun recordOfCurrentAdmission(lookup: AdmissionLookup): ReservationAdmission? = when (lookup) {
    is AdmissionLookup.Valid -> lookup.admission.record
    is AdmissionLookup.Unavailable -> null
    AdmissionLookup.Unconfirmed -> null
}

fun describe(reason: AdmissionUnavailableReason): String = when (reason) {
    AdmissionUnavailableReason.NotIncluded -> "not included"
    AdmissionUnavailableReason.NotReserved -> "not reserved"
    AdmissionUnavailableReason.Cancelled -> "cancelled"
}
