package example.reservation.core

import example.eligibility.core.QualificationKind
import example.eligibility.core.ReverificationFailure
import example.kernel.ParticipantId
import kotlin.jvm.JvmInline

// 予約が成立した時点の条件。新しい予約の条件（NewBookingRequirement）が変わっても、成立済みの記録は書き換えない。
sealed interface BookedBasis {
    data object WithoutQualification : BookedBasis
    data class WithQualification(val kind: QualificationKind) : BookedBasis
}

data class Booking(val workshop: Workshop, val participants: List<ParticipantId>, val basis: BookedBasis)

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
    abstract val booking: Booking
    val workshop: Workshop get() = booking.workshop
    val participants: List<ParticipantId> get() = booking.participants

    // null は外部要求を出していない（結果の保持だけ）ことを表し、応答の適用対象にならない。
    internal abstract val operation: OperationId?

    class Creating internal constructor(
        override val operation: OperationId,
        internal val request: RequestId,
        internal val draft: ConfirmedDraft,
        internal val trigger: Any,
        // 再試行で始めた作成の元の未成立。作成を許されなかったときは、これを失わせない。
        internal val resting: NotCreated? = null,
    ) : ActiveRequest() {
        override val booking: Booking get() = draft.booking
    }

    class CheckingStatus internal constructor(
        override val operation: OperationId,
        internal val ticket: StatusCheckTicket,
    ) : ActiveRequest() {
        override val booking: Booking get() = ticket.draft.booking
    }

    // 取消は外部要求を一つだけ出す。未解決の結果（結果不明・未成立など）は取消の間も保持し、決着後に戻す。
    class Cancelling internal constructor(
        override val operation: OperationId,
        internal val record: ReservationRecord.Reserved,
        internal val trigger: CancelTicket,
        val resting: ActiveRequest?,
    ) : ActiveRequest() {
        val reservation: ReservationId get() = record.reservation
        override val booking: Booking get() = record.booking
    }

    class Unknown internal constructor(val check: StatusCheckTicket) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val booking: Booking get() = check.draft.booking
    }

    class NotCreated internal constructor(
        val retry: RetryTicket,
        val lastDenial: CreateDenial? = null,
    ) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val booking: Booking get() = retry.draft.booking
    }

    class SoldOut internal constructor(internal val draft: ConfirmedDraft) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val booking: Booking get() = draft.booking
    }

    class RegistrationClosed internal constructor(internal val draft: ConfirmedDraft) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val booking: Booking get() = draft.booking
    }

    // 作成直前の資格の再確認で条件を満たさなかった結果。作成要求は送っていない。
    class EligibilityLost internal constructor(
        internal val draft: ConfirmedDraft,
        val reason: ReverificationFailure,
    ) : ActiveRequest() {
        override val operation: OperationId? get() = null
        override val booking: Booking get() = draft.booking
    }
}

// 新規作成を許されなかった理由。再試行では、元の未成立と区別して保持する。
sealed interface CreateDenial {
    data object RegistrationClosed : CreateDenial
    class EligibilityLost(val reason: ReverificationFailure) : CreateDenial
}

enum class CancelFailure { Declined, NoResponse }

sealed interface ReservationRecord {
    val reservation: ReservationId
    val booking: Booking

    class Reserved internal constructor(
        override val reservation: ReservationId,
        override val booking: Booking,
        val cancel: CancelTicket,
        val lastCancelFailure: CancelFailure?,
        val admissions: List<ReservationAdmission>,
    ) : ReservationRecord

    class Cancelled internal constructor(
        override val reservation: ReservationId,
        override val booking: Booking,
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
    QualificationNotRequired,
    NoParticipants,
}

sealed interface Acceptance {
    data object Accepted : Acceptance
    data class Rejected(val reason: Rejection) : Acceptance
}

sealed interface ConfirmOutcome {
    class Confirmed(val draft: ConfirmedDraft) : ConfirmOutcome
    data object NoWorkshop : ConfirmOutcome
    data object EmptyParticipants : ConfirmOutcome
    data object QualificationRequired : ConfirmOutcome
    data object QualificationNotRequired : ConfirmOutcome
    class EligibilityNotMet(val participants: List<ParticipantId>) : ConfirmOutcome
}
