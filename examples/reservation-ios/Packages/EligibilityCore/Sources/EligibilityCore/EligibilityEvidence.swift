public import Kernel

// 構築は EligibilityCore だけが行う。期限は公開せず、判定を EligibilityCore に留める。
public final class EligibilityCertificate: IssuedEvidence {
    public let participant: ParticipantId
    public let kind: QualificationKind
    public let checkedAt: EpochMillis
    let expiresAt: EpochMillis

    init(participant: ParticipantId, kind: QualificationKind, checkedAt: EpochMillis, expiresAt: EpochMillis) {
        self.participant = participant
        self.kind = kind
        self.checkedAt = checkedAt
        self.expiresAt = expiresAt
    }
}
