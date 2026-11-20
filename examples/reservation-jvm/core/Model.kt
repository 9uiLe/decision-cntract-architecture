package dca.core

@JvmInline value class SessionId(val value: String)
@JvmInline value class ParticipantId(val value: String)
@JvmInline value class RequestId(val value: String)
@JvmInline value class EpochMillis(val value: Long) : Comparable<EpochMillis> {
    override fun compareTo(other: EpochMillis): Int = value.compareTo(other.value)
}

fun interface Clock { fun now(): EpochMillis }

data class Workshop(val id: SessionId, val start: EpochMillis)
data class Reservation(val id: String)

// 証拠型は data class にしない。copy で別の内容へ付け替えられないようにするため。
// 構築は Core の確認処理だけが行い、同一性（===）で「いま有効な発行物」と照合される。
class ConfirmedDraft internal constructor(
    internal val workshop: Workshop,
    internal val participants: List<ParticipantId>,
)

class StatusCheckTicket internal constructor(
    val request: RequestId,
    internal val draft: ConfirmedDraft,
)

class RetryTicket internal constructor(
    internal val draft: ConfirmedDraft,
)

sealed interface ConfirmOutcome {
    data class Confirmed(val draft: ConfirmedDraft) : ConfirmOutcome
    data object EmptyParticipants : ConfirmOutcome
    data object DuplicateParticipants : ConfirmOutcome
    data object AttemptAlreadyStarted : ConfirmOutcome
}

sealed interface Outcome {
    data class Reserved(val reservation: Reservation) : Outcome
    data object SoldOut : Outcome
    data object RegistrationClosed : Outcome
    data class NotCreated(val retry: RetryTicket) : Outcome
    data class Unknown(val check: StatusCheckTicket) : Outcome
    data class RetryClosed(val kept: NotCreated) : Outcome
    // 型が有効でも、現在の業務状態に対応しない操作。状態は変えない。
    data object Stale : Outcome
}
