public import EligibilityCore
public import Kernel

public enum NewBookingRequirement: Sendable {
    case notRequired
    case required(QualificationKind)
}

public struct Workshop: Sendable {
    public let id: SessionId
    public let name: String
    public let start: EpochMillis
    public let seatsHint: Int
    public let requirement: NewBookingRequirement

    public init(id: SessionId, name: String, start: EpochMillis, seatsHint: Int, requirement: NewBookingRequirement) {
        self.id = id
        self.name = name
        self.start = start
        self.seatsHint = seatsHint
        self.requirement = requirement
    }
}

public struct Participant: Sendable {
    public let id: ParticipantId
    public let name: String

    public init(id: ParticipantId, name: String) {
        self.id = id
        self.name = name
    }
}

public struct Catalog: Sendable {
    public let workshops: [Workshop]
    public let participants: [Participant]

    public init(workshops: [Workshop], participants: [Participant]) {
        self.workshops = workshops
        self.participants = participants
    }
}
