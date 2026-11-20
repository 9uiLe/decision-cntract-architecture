package example.reservation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import example.reservation.core.Acceptance
import example.reservation.core.Catalog
import example.reservation.core.ConfirmOutcome
import example.reservation.core.ConfirmedDraft
import example.reservation.core.CancelTicket
import example.reservation.core.ReservationCore
import example.reservation.core.Rejection
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket
import example.reservation.ports.ParticipantId
import example.reservation.ports.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Notice(val rejection: Rejection, val sequence: Long)

// 業務の処理は Core が実行する。ViewModel は Core の状態を画面状態へ投影し、操作を Core へ中継するだけで、
// viewModelScope では外部要求を起動しない。
class ReservationViewModel(private val core: ReservationCore, private val catalog: Catalog) : ViewModel() {
    val state: StateFlow<ReservationScreenState> = core.snapshot
        .map { project(it, catalog) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), project(core.snapshot.value, catalog))

    private val pendingNotice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> get() = pendingNotice
    private var noticeSequence = 0L

    fun selectWorkshop(session: SessionId): Acceptance = relay(core.selectWorkshop(session))

    fun toggleParticipant(participant: ParticipantId): Acceptance = relay(core.toggleParticipant(participant))

    fun confirm(): ConfirmOutcome = core.confirm()

    fun submit(draft: ConfirmedDraft): Acceptance = relay(core.submit(draft))

    fun checkStatus(ticket: StatusCheckTicket): Acceptance = relay(core.checkStatus(ticket))

    fun retry(ticket: RetryTicket): Acceptance = relay(core.retry(ticket))

    fun cancel(ticket: CancelTicket): Acceptance = relay(core.cancel(ticket))

    fun consumeNotice(notice: Notice) {
        pendingNotice.compareAndSet(notice, null)
    }

    private fun relay(acceptance: Acceptance): Acceptance {
        when (acceptance) {
            Acceptance.Accepted -> Unit
            is Acceptance.Rejected -> pendingNotice.value = Notice(acceptance.reason, ++noticeSequence)
        }
        return acceptance
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
