package dca.ui

import dca.core.Outcome
import dca.core.RetryTicket
import dca.core.StatusCheckTicket

sealed interface ScreenState {
    data class Reserved(val reservationId: String) : ScreenState
    data object SoldOut : ScreenState
    data object RegistrationClosed : ScreenState
    data class NotCreated(val retry: RetryTicket) : ScreenState
    data class Unknown(val check: StatusCheckTicket) : ScreenState
    data object RetryUnavailable : ScreenState
    data object Outdated : ScreenState
}

// else を置かない。Outcome にケースが増えたら、ここで表示の判断を求める。
fun project(outcome: Outcome): ScreenState = when (outcome) { // DECISION-POINT
    is Outcome.Reserved -> ScreenState.Reserved(outcome.reservation.id)
    Outcome.SoldOut -> ScreenState.SoldOut
    Outcome.RegistrationClosed -> ScreenState.RegistrationClosed
    is Outcome.NotCreated -> ScreenState.NotCreated(outcome.retry)
    is Outcome.Unknown -> ScreenState.Unknown(outcome.check)
    is Outcome.RetryClosed -> ScreenState.RetryUnavailable
    Outcome.Stale -> ScreenState.Outdated
}
