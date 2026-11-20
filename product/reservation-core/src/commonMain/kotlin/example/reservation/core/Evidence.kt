package example.reservation.core

import example.eligibility.core.EligibilityCertificate
import example.eligibility.core.QualificationKind
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId

// 証拠型は data class にしない。copy で別の内容へ付け替えられないようにするため。
// 構築は Core だけが行い、操作の受付時に同一性（===）で「いま有効な発行物」と照合される。
sealed class ConfirmedDraft {
    abstract val workshop: Workshop
    abstract val participants: List<ParticipantId>
    abstract val booking: Booking

    // 作成直前の再確認で資格を確かめる対象。資格を要さない下書きは空で、送信処理は下書きの種類を分岐しない。
    internal abstract val certificates: List<EligibilityCertificate>

    class Open internal constructor(
        override val workshop: Workshop,
        override val participants: List<ParticipantId>,
    ) : ConfirmedDraft() {
        override val booking: Booking get() = Booking(workshop, participants, BookedBasis.WithoutQualification)
        internal override val certificates: List<EligibilityCertificate> get() = emptyList()
    }

    class Qualified internal constructor(
        override val workshop: Workshop,
        override val participants: List<ParticipantId>,
        val kind: QualificationKind,
        internal override val certificates: List<EligibilityCertificate>,
    ) : ConfirmedDraft() {
        override val booking: Booking get() = Booking(workshop, participants, BookedBasis.WithQualification(kind))
    }
}

class StatusCheckTicket internal constructor(
    val request: RequestId,
    val draft: ConfirmedDraft,
)

class RetryTicket internal constructor(
    val draft: ConfirmedDraft,
)

class CancelTicket internal constructor(
    val reservation: ReservationId,
)

// 来場受付が前提にする、成立が確認された予約と参加者の組。構築は予約 Core だけが行う。
// 現在も有効かどうかは、受け取った側が AdmissionSource に問い合わせて確かめる。
sealed class ReservationAdmission {
    abstract val reservation: ReservationId
    abstract val participant: ParticipantId
    abstract val session: SessionId
    abstract val start: EpochMillis

    class Open internal constructor(
        override val reservation: ReservationId,
        override val participant: ParticipantId,
        override val session: SessionId,
        override val start: EpochMillis,
    ) : ReservationAdmission()

    class Qualified internal constructor(
        override val reservation: ReservationId,
        override val participant: ParticipantId,
        override val session: SessionId,
        override val start: EpochMillis,
        val kind: QualificationKind,
    ) : ReservationAdmission()
}

enum class AdmissionUnavailableReason { NotIncluded, NotReserved, Cancelled }

sealed interface AdmissionLookup {
    class Valid(val admission: ReservationAdmission) : AdmissionLookup
    class Unavailable(val reason: AdmissionUnavailableReason) : AdmissionLookup
    data object Unconfirmed : AdmissionLookup
}

interface AdmissionSource {
    suspend fun admission(reservation: ReservationId, participant: ParticipantId): AdmissionLookup
}
