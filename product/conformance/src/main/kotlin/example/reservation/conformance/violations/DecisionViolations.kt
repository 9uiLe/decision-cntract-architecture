package example.reservation.conformance.violations

import example.eligibility.core.EligibilityOutcome
import example.reservation.core.ActiveRequest
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.Rejection
import example.reservation.ui.RequestCard

private typealias Looked = LookupObservation

fun projectWithFallback(request: ActiveRequest?): String = when (request) {
    is ActiveRequest.Creating -> "sending"
    else -> "other" // EXPECT: ExhaustiveDecisionWhen
}

fun interpretWithFallback(observation: CreateObservation, lookup: Looked?): String {
    val created = when (observation) {
        is CreateObservation.Created -> "created"
        else -> "other" // EXPECT: ExhaustiveDecisionWhen
    }
    val looked = when (lookup) {
        is LookupObservation.Found -> "found"
        else -> "other" // EXPECT: ExhaustiveDecisionWhen
    }
    return created + looked
}

fun messageWithFallback(rejection: Rejection): String = when (rejection) {
    Rejection.Busy -> "busy"
    else -> "other" // EXPECT: ExhaustiveDecisionWhen
}

fun cardWithFallback(card: RequestCard): String = card.let { shown ->
    when (shown) {
        is RequestCard.SoldOut -> "sold out"
        else -> "other" // EXPECT: ExhaustiveDecisionWhen
    }
}

fun readinessWithFallback(outcome: EligibilityOutcome): Boolean = when (outcome) {
    is EligibilityOutcome.Eligible -> true
    else -> false // EXPECT: ExhaustiveDecisionWhen
}

fun projectExhaustively(request: ActiveRequest?): String = when (request) {
    null -> "none"
    is ActiveRequest.Creating -> "creating"
    is ActiveRequest.CheckingStatus -> "checking"
    is ActiveRequest.Cancelling -> "cancelling"
    is ActiveRequest.Unknown -> "unknown"
    is ActiveRequest.NotCreated -> "not created"
    is ActiveRequest.SoldOut -> "sold out"
    is ActiveRequest.RegistrationClosed -> "closed"
    is ActiveRequest.EligibilityLost -> "eligibility lost"
}

fun parseExternalCode(code: String): Int = when (code) {
    "ok" -> 1
    else -> 0
}
