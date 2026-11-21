public import CheckInCore
public import EligibilityCore
public import Kernel
public import ReservationCore

public enum RequirementView: Sendable {
    case notRequired
    case required(qualificationName: String)
}

public struct WorkshopItem: Sendable {
    public let id: SessionId
    public let name: String
    public let start: EpochMillis
    public let seatsHint: Int
    public let requirement: RequirementView
}

public struct BookingSummary: Sendable {
    public let workshopName: String
    public let start: EpochMillis
    public let participantNames: [String]
}

public enum QualificationBadge: Sendable { case notChecked, checking, valid, notHeld, expired, unknown }

public struct ParticipantRow: Sendable {
    public let id: ParticipantId
    public let name: String
    public let selected: Bool
    public let qualification: QualificationBadge?
}

public enum DetailsStage: Sendable {
    case editing
    case confirmed(ConfirmedDraft, submit: TimeGate)
}

public struct DetailsState: Sendable {
    public let workshop: WorkshopItem?
    public let participants: [ParticipantRow]
    public let stage: DetailsStage
    public let canConfirm: Bool
    public let confirmHint: String?
    public let canRequestEligibility: Bool
}

public enum RequestCard: Sendable {
    case sending(BookingSummary)
    case checkingStatus(BookingSummary)
    case unknown(BookingSummary, check: StatusCheckTicket)
    case notCreated(BookingSummary, retry: RetryTicket, denial: CreateDenial?, gate: TimeGate)
    case soldOut(BookingSummary)
    case registrationClosed(BookingSummary)
    case eligibilityLost(BookingSummary, reason: ReverificationFailure)

    public var summary: BookingSummary {
        switch self {
        case .sending(let summary), .checkingStatus(let summary), .soldOut(let summary), .registrationClosed(let summary):
            return summary
        case .unknown(let summary, _), .notCreated(let summary, _, _, _), .eligibilityLost(let summary, _):
            return summary
        }
    }
}

public enum CheckInView: Sendable {
    case notCheckedIn
    case processing(ProcessingStage)
    case checkedIn
    case unknown(CheckInStatusTicket)
    case notCreated(CheckInRetryTicket)
    case notAdmitted(Denial)
}

public struct ParticipantCheckInRow: Sendable {
    public let name: String
    public let admission: ReservationAdmission
    public let view: CheckInView
    public let window: CheckInWindow
}

public enum ReservationCard: Sendable {
    case reserved(ReservedCard)
    case cancelling(summary: BookingSummary, reservationId: String)
    case cancelled(summary: BookingSummary, reservationId: String)
}

public struct ReservedCard: Sendable {
    public let summary: BookingSummary
    public let reservationId: String
    public let cancel: CancelTicket
    public let cancellation: TimeGate
    public let lastCancelFailure: CancelFailure?
    public let participants: [ParticipantCheckInRow]
}

public struct StatusState: Sendable {
    public let request: RequestCard?
    public let reservations: [ReservationCard]
}

public struct ListState: Sendable {
    public let workshops: [WorkshopItem]
    public let ongoing: StatusState
}

public struct ReservationScreenState: Sendable {
    public let list: ListState
    public let details: DetailsState
    public let status: StatusState
}
