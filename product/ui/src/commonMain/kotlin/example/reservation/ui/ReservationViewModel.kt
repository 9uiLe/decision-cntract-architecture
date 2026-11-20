package example.reservation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import example.checkin.core.CheckInAcceptance
import example.checkin.core.CheckInCore
import example.checkin.core.CheckInRetryTicket
import example.checkin.core.CheckInStatusTicket
import example.eligibility.core.EligibilityAuthority
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.Acceptance
import example.reservation.core.CancelTicket
import example.reservation.core.Catalog
import example.reservation.core.ConfirmOutcome
import example.reservation.core.ConfirmedDraft
import example.reservation.core.NewBookingRequirement
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationCore
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class Notice(val message: String, val sequence: Long)

// 業務の処理は Core が実行する。ViewModel は Core の状態を画面状態へ投影し、操作を Core へ中継するだけで、
// viewModelScope では外部要求を起動しない。
class ReservationViewModel(
    private val core: ReservationCore,
    private val eligibility: EligibilityAuthority,
    private val checkIn: CheckInCore,
    private val catalog: Catalog,
) : ViewModel() {
    val state: StateFlow<ReservationScreenState> = combine(
        core.snapshot, eligibility.snapshot, checkIn.snapshot,
    ) { reservation, qualifications, checkIns -> project(reservation, qualifications, checkIns, catalog) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), currentState())

    private val pendingNotice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> get() = pendingNotice
    private var noticeSequence = 0L

    fun selectWorkshop(session: SessionId): Acceptance = relay(core.selectWorkshop(session))

    fun toggleParticipant(participant: ParticipantId): Acceptance = relay(core.toggleParticipant(participant))

    fun requestEligibility(): Acceptance = relay(core.requestEligibility())

    // どの確認の入口を使うかは開催回の要件に従う。条件を満たすかどうかは Core が判断する。
    fun confirm(): ConfirmOutcome {
        val requirement = core.snapshot.value.draft.workshop?.requirement
        val outcome = when (requirement) {
            null -> core.confirmWithoutQualification()
            NewBookingRequirement.NotRequired -> core.confirmWithoutQualification()
            is NewBookingRequirement.Required -> core.confirmQualified()
        }
        confirmFailureMessage(outcome)?.let(::notify)
        return outcome
    }

    fun submit(draft: ConfirmedDraft): Acceptance = relay(core.submit(draft))

    fun checkStatus(ticket: StatusCheckTicket): Acceptance = relay(core.checkStatus(ticket))

    fun retry(ticket: RetryTicket): Acceptance = relay(core.retry(ticket))

    fun cancel(ticket: CancelTicket): Acceptance = relay(core.cancel(ticket))

    fun checkInParticipant(admission: ReservationAdmission): CheckInAcceptance = relayCheckIn(checkIn.checkIn(admission))

    fun lookUpCheckIn(ticket: CheckInStatusTicket): CheckInAcceptance = relayCheckIn(checkIn.checkStatus(ticket))

    fun retryCheckIn(ticket: CheckInRetryTicket): CheckInAcceptance = relayCheckIn(checkIn.retry(ticket))

    fun consumeNotice(notice: Notice) {
        pendingNotice.compareAndSet(notice, null)
    }

    private fun currentState() =
        project(core.snapshot.value, eligibility.snapshot.value, checkIn.snapshot.value, catalog)

    private fun relay(acceptance: Acceptance): Acceptance {
        when (acceptance) {
            Acceptance.Accepted -> Unit
            is Acceptance.Rejected -> notify(rejectionMessage(acceptance.reason))
        }
        return acceptance
    }

    private fun relayCheckIn(acceptance: CheckInAcceptance): CheckInAcceptance {
        when (acceptance) {
            CheckInAcceptance.Accepted -> Unit
            is CheckInAcceptance.Rejected -> notify(checkInRejectionMessage(acceptance.reason))
        }
        return acceptance
    }

    private fun notify(message: String) {
        pendingNotice.value = Notice(message, ++noticeSequence)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
