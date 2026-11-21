public import Kernel

public struct QualificationKind: Hashable, Sendable {
    public let id: String
    public let name: String

    public init(id: String, name: String? = nil) {
        self.id = id
        self.name = name ?? id
    }
}

public enum QualificationObservation: Equatable, Sendable {
    case held(expiresAt: EpochMillis)
    case notHeld
    case unavailable
}

public protocol EligibilityPort: Sendable {
    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation
}
