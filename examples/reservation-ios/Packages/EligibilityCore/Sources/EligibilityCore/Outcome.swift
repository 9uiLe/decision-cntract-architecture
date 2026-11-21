public import Kernel

public struct EligibilityKey: Hashable, Sendable {
    public let participant: ParticipantId
    public let kind: QualificationKind

    public init(participant: ParticipantId, kind: QualificationKind) {
        self.participant = participant
        self.kind = kind
    }
}

public enum EligibilityOutcome: Sendable {
    case eligible(EligibilityCertificate)
    case notHeld
    case expired
    case unknown
}

public enum EligibilityStatus: Sendable {
    case notChecked
    case checking
    case settled(EligibilityOutcome)
}

public enum EligibilityRequestRejection: Sendable { case busy }

public enum EligibilityRequestAcceptance: Equatable, Sendable {
    case accepted
    case rejected(EligibilityRequestRejection)
}

public enum ReverificationFailure: Sendable { case notHeld, expired, unconfirmed, targetMismatch }

public enum Reverification: Sendable {
    case verified([EligibilityCertificate])
    case failed(ReverificationFailure)
}

public struct EligibilitySnapshot: Sendable {
    public let statuses: [EligibilityKey: EligibilityStatus]

    public init(statuses: [EligibilityKey: EligibilityStatus]) { self.statuses = statuses }
}
