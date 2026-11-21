import EligibilityCore
public import Kernel

// 構築は ReservationCore だけが行う。構造体にしないのは、コピーで内容を付け替えられないようにし、
// 操作の受付時に同一性（===）で「いま有効な発行物」と照合するため。
public final class ConfirmedDraft: IssuedEvidence {
    public let workshop: Workshop
    public let participants: [ParticipantId]
    public let basis: BookedBasis
    let certificates: [EligibilityCertificate]

    init(workshop: Workshop, participants: [ParticipantId], basis: BookedBasis, certificates: [EligibilityCertificate]) {
        self.workshop = workshop
        self.participants = participants
        self.basis = basis
        self.certificates = certificates
    }

    public var booking: Booking { Booking(workshop: workshop, participants: participants, basis: basis) }
}

public final class StatusCheckTicket: IssuedEvidence {
    public let request: RequestId
    public let draft: ConfirmedDraft

    init(request: RequestId, draft: ConfirmedDraft) {
        self.request = request
        self.draft = draft
    }
}

public final class RetryTicket: IssuedEvidence {
    public let draft: ConfirmedDraft

    init(draft: ConfirmedDraft) { self.draft = draft }
}

public final class CancelTicket: IssuedEvidence {
    public let reservation: ReservationId

    init(reservation: ReservationId) { self.reservation = reservation }
}

// 成立時の事実だけを示し、現在も有効であることは示さない。資格の観測や証明書は持たない。
public final class ReservationAdmission: IssuedEvidence {
    public let reservation: ReservationId
    public let participant: ParticipantId
    public let session: SessionId
    public let start: EpochMillis
    public let basis: BookedBasis

    init(reservation: ReservationId, participant: ParticipantId, session: SessionId, start: EpochMillis, basis: BookedBasis) {
        self.reservation = reservation
        self.participant = participant
        self.session = session
        self.start = start
        self.basis = basis
    }
}

// 受け取った側は observedAt が自分の照会の区間に入る値だけを使う。過去の結果の再生は型では防げない。
public final class CurrentAdmission: IssuedEvidence {
    public let record: ReservationAdmission
    public let observedAt: EpochMillis

    init(record: ReservationAdmission, observedAt: EpochMillis) {
        self.record = record
        self.observedAt = observedAt
    }
}
