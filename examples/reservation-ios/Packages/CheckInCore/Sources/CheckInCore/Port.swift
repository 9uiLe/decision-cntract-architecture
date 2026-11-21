public import Kernel
public import ReservationCore

public struct CheckInAttemptId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) { self.value = value }
}

public enum CheckInCreateObservation: Equatable, Sendable {
    case recorded
    case alreadyRecorded
    case notCreated
    case noResponse
}

public enum CheckInLookupObservation: Equatable, Sendable {
    case found
    case notCreated
    case notFound
    case failed
}

public protocol CheckInPort: Sendable {
    func createCheckIn(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId) async
        -> CheckInCreateObservation
    func lookUpCheckIn(attempt: CheckInAttemptId) async -> CheckInLookupObservation
}
