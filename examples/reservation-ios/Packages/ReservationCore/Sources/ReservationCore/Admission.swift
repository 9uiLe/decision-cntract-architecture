public import Kernel

public enum AdmissionUnavailableReason: Sendable { case notIncluded, notReserved, cancelled }

public enum AdmissionLookup: Sendable {
    case valid(CurrentAdmission)
    case unavailable(AdmissionUnavailableReason)
    case unconfirmed
}

public protocol AdmissionSource: Sendable {
    func admission(reservation: ReservationId, participant: ParticipantId) async -> AdmissionLookup
}
