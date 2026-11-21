public import EligibilityCore
public import Kernel

public enum BookedBasis: Equatable, Sendable {
    case withoutQualification
    case withQualification(QualificationKind)
}

public struct Booking: Sendable {
    public let workshop: Workshop
    public let participants: [ParticipantId]
    public let basis: BookedBasis
}

public enum Confirmation: Sendable {
    case unconfirmed
    case confirmed(ConfirmedDraft)
}

public struct Draft: Sendable {
    public let workshop: Workshop?
    public let participants: [ParticipantId]
    public let confirmation: Confirmation
}

public enum CreateDenial: Sendable {
    case registrationClosed
    case eligibilityLost(ReverificationFailure)
}

public enum CancelFailure: Sendable { case declined, noResponse }

// 時刻で決まる操作の可否は Core が現在時刻で判断する。画面は期限を持たず、この結果をそのまま使う。
public enum TimeGate: Sendable { case open, closed }

public indirect enum ActiveRequest: Sendable {
    case creating(Booking)
    case checkingStatus(Booking)
    case cancelling(reservation: ReservationId, booking: Booking, resting: ActiveRequest?)
    case unknown(StatusCheckTicket)
    case notCreated(RetryTicket, lastDenial: CreateDenial?)
    case soldOut(Booking)
    case registrationClosed(Booking)
    case eligibilityLost(Booking, ReverificationFailure)

    public var booking: Booking {
        switch self {
        case .creating(let booking), .checkingStatus(let booking), .soldOut(let booking), .registrationClosed(let booking):
            return booking
        case .cancelling(_, let booking, _), .eligibilityLost(let booking, _):
            return booking
        case .unknown(let ticket):
            return ticket.draft.booking
        case .notCreated(let ticket, _):
            return ticket.draft.booking
        }
    }
}

public struct ReservedRecord: Sendable {
    public let reservation: ReservationId
    public let booking: Booking
    public let cancel: CancelTicket
    public let lastCancelFailure: CancelFailure?
    public let admissions: [ReservationAdmission]
}

public enum ReservationRecord: Sendable {
    case reserved(ReservedRecord)
    case cancelled(reservation: ReservationId, booking: Booking)

    public var reservation: ReservationId {
        switch self {
        case .reserved(let record): return record.reservation
        case .cancelled(let reservation, _): return reservation
        }
    }

    public var booking: Booking {
        switch self {
        case .reserved(let record): return record.booking
        case .cancelled(_, let booking): return booking
        }
    }
}

public struct CoreSnapshot: Sendable {
    public let draft: Draft
    public let request: ActiveRequest?
    public let reservations: [ReservationRecord]
}

public enum Rejection: Sendable {
    case unknownWorkshop
    case unknownParticipant
    case stale
    case busy
    case registrationClosed
    case cancellationClosed
    case qualificationNotRequired
    case noParticipants
}

public enum Acceptance: Equatable, Sendable {
    case accepted
    case rejected(Rejection)
}

public enum ConfirmOutcome: Sendable {
    case confirmed(ConfirmedDraft)
    case noWorkshop
    case emptyParticipants
    case qualificationRequired
    case qualificationNotRequired
    case eligibilityNotMet([ParticipantId])
}
