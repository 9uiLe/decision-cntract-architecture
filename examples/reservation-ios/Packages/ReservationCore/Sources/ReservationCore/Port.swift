public import Kernel

public enum CreateObservation: Equatable, Sendable {
    case created(ReservationId)
    case full
    case notCreated
    case noResponse
}

public enum LookupObservation: Equatable, Sendable {
    case found(ReservationId)
    case notCreated
    case notFound
    case failed
}

public enum CancelObservation: Equatable, Sendable {
    case cancelled
    case declined
    case noResponse
}

public enum UsageObservation: Equatable, Sendable {
    case active
    case cancelled
    case notFound
    case failed
}

public protocol ReservationPort: Sendable {
    func createReservation(request: RequestId, session: SessionId, participants: [ParticipantId]) async -> CreateObservation
    func lookUpReservation(request: RequestId) async -> LookupObservation
    func cancelReservation(_ reservation: ReservationId) async -> CancelObservation
    func lookUpUsage(_ reservation: ReservationId) async -> UsageObservation
}
