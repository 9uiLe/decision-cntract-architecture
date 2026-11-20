package contracts.accept

import example.checkin.core.CheckInAcceptance
import example.checkin.core.CheckInCore
import example.checkin.core.ParticipantCheckIn
import example.eligibility.core.EligibilityOutcome
import example.reservation.core.Acceptance
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
