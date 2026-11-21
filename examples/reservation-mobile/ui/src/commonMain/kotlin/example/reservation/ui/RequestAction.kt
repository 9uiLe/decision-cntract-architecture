package example.reservation.ui

import example.kernel.BoundAction
import example.kernel.DecisionResult
import example.reservation.core.Acceptance
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket

// 予約状況カードの操作。投影は券だけを保持し、画面の接続点で実行先と束ねて action にする。
// data class の等価性は endpoint と券の同一性の両方を含むので、キャッシュのキーに使っても片方の変更を落とさない。
@DecisionResult
@BoundAction
sealed interface RequestAction {
    val endpoint: ReservationViewModel

    fun execute(): Acceptance

    @BoundAction
    data class CheckStatus(override val endpoint: ReservationViewModel, val ticket: StatusCheckTicket) : RequestAction {
        override fun execute(): Acceptance = endpoint.checkStatus(ticket)
    }

    @BoundAction
    data class Retry(override val endpoint: ReservationViewModel, val ticket: RetryTicket) : RequestAction {
        override fun execute(): Acceptance = endpoint.retry(ticket)
    }
}

fun RequestCard.Unknown.boundTo(endpoint: ReservationViewModel): RequestAction.CheckStatus =
    RequestAction.CheckStatus(endpoint, check)

fun RequestCard.NotCreated.boundTo(endpoint: ReservationViewModel): RequestAction.Retry =
    RequestAction.Retry(endpoint, retry)
