public import Kernel
public import ReservationCore

public struct CheckInKey: Hashable, Sendable {
    public let reservation: ReservationId
    public let participant: ParticipantId

    public init(reservation: ReservationId, participant: ParticipantId) {
        self.reservation = reservation
        self.participant = participant
    }
}

public enum Denial: Sendable {
    case reservationUnavailable(AdmissionUnavailableReason)
    case reservationUnconfirmed
    case notYetOpen
    case closed
    case wrongTarget
}

public enum ProcessingStage: Sendable { case confirmingReservation, recording, lookingUp }

public enum ParticipantCheckIn: Sendable {
    case processing(ProcessingStage)
    case checkedIn
    case unknown(CheckInStatusTicket)
    case notCreated(CheckInRetryTicket)
    case notAdmitted(Denial)
}

public struct CheckInSnapshot: Sendable {
    public let entries: [CheckInKey: ParticipantCheckIn]
}

public enum CheckInRejection: Sendable { case busy, alreadyCheckedIn, unresolved, stale, notYetOpen, closed }

public enum CheckInAcceptance: Equatable, Sendable {
    case accepted
    case rejected(CheckInRejection)
}

public enum CheckInWindow: Sendable { case notYetOpen, open, closed }
