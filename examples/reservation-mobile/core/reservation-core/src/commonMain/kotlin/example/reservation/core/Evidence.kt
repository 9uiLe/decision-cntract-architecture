package example.reservation.core

import example.eligibility.core.EligibilityCertificate
import example.eligibility.core.QualificationKind
import example.kernel.DecisionResult
import example.kernel.EpochMillis
import example.kernel.IssuedEvidence
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.kernel.frozen

// 証拠型は data class にしない。copy で別の内容へ付け替えられないようにするため。
// 構築は Core だけが行い、操作の受付時に同一性（===）で「いま有効な発行物」と照合される。
@IssuedEvidence
sealed class ConfirmedDraft {
    abstract val workshop: Workshop
    abstract val participants: List<ParticipantId>
    abstract val booking: Booking

    // 作成直前の再確認で資格を確かめる対象。資格を要さない下書きは空で、送信処理は下書きの種類を分岐しない。
    internal abstract val certificates: List<EligibilityCertificate>

    @IssuedEvidence
    class Open internal constructor(
        override val workshop: Workshop,
        participants: List<ParticipantId>,
    ) : ConfirmedDraft() {
        override val participants: List<ParticipantId> = participants.frozen()
        override val booking: Booking get() = Booking(workshop, participants, BookedBasis.WithoutQualification)
        internal override val certificates: List<EligibilityCertificate> get() = emptyList()
    }

    @IssuedEvidence
    class Qualified internal constructor(
        override val workshop: Workshop,
        participants: List<ParticipantId>,
        val kind: QualificationKind,
        certificates: List<EligibilityCertificate>,
    ) : ConfirmedDraft() {
        override val participants: List<ParticipantId> = participants.frozen()
        internal override val certificates: List<EligibilityCertificate> = certificates.frozen()
        override val booking: Booking get() = Booking(workshop, participants, BookedBasis.WithQualification(kind))
    }
}

@IssuedEvidence
class StatusCheckTicket internal constructor(
    val request: RequestId,
    val draft: ConfirmedDraft,
)

@IssuedEvidence
class RetryTicket internal constructor(
    val draft: ConfirmedDraft,
)

@IssuedEvidence
class CancelTicket internal constructor(
    val reservation: ReservationId,
)

// 予約が成立した時点の予約と参加者の組。構築は予約 Core だけが行う。成立後に取消や利用停止があっても変わらず、
// 現在も有効であることは示さない。現時の確認は AdmissionSource が返す CurrentAdmission だけが担う。
@DecisionResult
@IssuedEvidence
sealed class ReservationAdmission {
    abstract val reservation: ReservationId
    abstract val participant: ParticipantId
    abstract val session: SessionId
    abstract val start: EpochMillis

    @IssuedEvidence
    class Open internal constructor(
        override val reservation: ReservationId,
        override val participant: ParticipantId,
        override val session: SessionId,
        override val start: EpochMillis,
    ) : ReservationAdmission()

    @IssuedEvidence
    class Qualified internal constructor(
        override val reservation: ReservationId,
        override val participant: ParticipantId,
        override val session: SessionId,
        override val start: EpochMillis,
        val kind: QualificationKind,
    ) : ReservationAdmission()
}

// 予約 Core が照会した時点で、予約がまだ有効だと確認した結果。observedAt は照会の応答を得た時刻で、
// 受け取った側は、自分が照会した区間に入る値だけを今回の結果として使う。過去の結果の再生は型では防げない。
@IssuedEvidence
class CurrentAdmission internal constructor(
    val record: ReservationAdmission,
    val observedAt: EpochMillis,
)

@DecisionResult
enum class AdmissionUnavailableReason { NotIncluded, NotReserved, Cancelled }

@DecisionResult
sealed interface AdmissionLookup {
    class Valid(val admission: CurrentAdmission) : AdmissionLookup
    class Unavailable(val reason: AdmissionUnavailableReason) : AdmissionLookup
    data object Unconfirmed : AdmissionLookup
}

interface AdmissionSource {
    suspend fun admission(reservation: ReservationId, participant: ParticipantId): AdmissionLookup
}
