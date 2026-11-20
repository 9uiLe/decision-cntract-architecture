package example.reservation.core

import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId

@JvmInline internal value class OperationId(val value: Long)

sealed interface Confirmation {
    data object Unconfirmed : Confirmation
    class Confirmed(val draft: ConfirmedDraft) : Confirmation
}

data class Draft(
    val workshop: Workshop?,
    val participants: List<ParticipantId>,
    val confirmation: Confirmation,
)

// 外部要求の進行と、結果が確定していない操作を最大一つ保持する。確定した予約はここに残さない。
sealed class ActiveRequest {
    abstract val draft: ConfirmedDraft
    val workshop: Workshop get() = draft.workshop
    val participants: List<ParticipantId> get() = draft.participants

    // null は外部要求を出していない（結果の保持だけ）ことを表し、応答の適用対象にならない。
    internal abstract val operation: OperationId?

    class Creating internal constructor(
        override val operation: OperationId,
        internal val request: RequestId,
        override val draft: ConfirmedDraft,
        internal val trigger: Any,
    ) : ActiveRequest()

    class CheckingStatus internal constructor(
        override val operation: OperationId,
        internal val ticket: StatusCheckTicket,
    ) : ActiveRequest() {
        override val draft: ConfirmedDraft get() = ticket.draft
    }

    // 取消は外部要求を一つだけ出す。未解決の結果（結果不明・未成立など）は取消の間も保持し、決着後に戻す。
    class Cancelling internal constructor(
        override val operation: OperationId,
        val reservation: ReservationId,
        override val draft: ConfirmedDraft,
        internal val trigger: CancelTicket,
        val resting: ActiveRequest?,
    ) : ActiveRequest()

    class Unknown internal constructor(val check: StatusCheckTicket) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val draft: ConfirmedDraft get() = check.draft
    }

    class NotCreated internal constructor(val retry: RetryTicket) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val draft: ConfirmedDraft get() = retry.draft
    }

    class SoldOut internal constructor(override val draft: ConfirmedDraft) : ActiveRequest() {
        override val operation: OperationId? get() = null
    }

    class RegistrationClosed internal constructor(override val draft: ConfirmedDraft) : ActiveRequest() {
        override val operation: OperationId? get() = null
    }
}

enum class CancelFailure { Declined, NoResponse }

sealed interface ReservationRecord {
    val reservation: ReservationId
    val draft: ConfirmedDraft

    class Reserved internal constructor(
        override val reservation: ReservationId,
        override val draft: ConfirmedDraft,
        val cancel: CancelTicket,
        val lastCancelFailure: CancelFailure?,
    ) : ReservationRecord

    class Cancelled internal constructor(
        override val reservation: ReservationId,
        override val draft: ConfirmedDraft,
    ) : ReservationRecord
}

data class CoreSnapshot(
    val draft: Draft,
    val request: ActiveRequest?,
    val reservations: List<ReservationRecord>,
)

enum class Rejection {
    UnknownWorkshop,
    UnknownParticipant,
    Stale,
    Busy,
    RegistrationClosed,
    CancellationClosed,
}

sealed interface Acceptance {
    data object Accepted : Acceptance
    data class Rejected(val reason: Rejection) : Acceptance
}

sealed interface ConfirmOutcome {
    class Confirmed(val draft: ConfirmedDraft) : ConfirmOutcome
    data object NoWorkshop : ConfirmOutcome
    data object EmptyParticipants : ConfirmOutcome
}
