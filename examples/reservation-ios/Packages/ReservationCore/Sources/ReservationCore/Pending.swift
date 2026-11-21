import EligibilityCore

struct OperationId: Equatable, Sendable {
    let value: UInt64
}

struct RestingNotCreated: Sendable {
    let retry: RetryTicket
    let lastDenial: CreateDenial?
}

struct CancelJob: Sendable {
    let operation: OperationId
    let record: ReservedRecord
    let trigger: ObjectIdentifier
    let resting: Pending?
}

struct CreateJob: Sendable {
    let operation: OperationId
    let request: RequestId
    let draft: ConfirmedDraft
    let trigger: ObjectIdentifier
    let resting: RestingNotCreated?
}

indirect enum Pending: Sendable {
    case creating(CreateJob)
    case checkingStatus(operation: OperationId, ticket: StatusCheckTicket)
    case cancelling(CancelJob)
    case unknown(StatusCheckTicket)
    case notCreated(RestingNotCreated)
    case soldOut(ConfirmedDraft)
    case registrationClosed(ConfirmedDraft)
    case eligibilityLost(ConfirmedDraft, ReverificationFailure)

    var operation: OperationId? {
        switch self {
        case .creating(let job): return job.operation
        case .checkingStatus(let operation, _): return operation
        case .cancelling(let job): return job.operation
        case .unknown, .notCreated, .soldOut, .registrationClosed, .eligibilityLost: return nil
        }
    }

    var view: ActiveRequest {
        switch self {
        case .creating(let job): return .creating(job.draft.booking)
        case .checkingStatus(_, let ticket): return .checkingStatus(ticket.draft.booking)
        case .cancelling(let job):
            return .cancelling(reservation: job.record.reservation, booking: job.record.booking, resting: job.resting?.view)
        case .unknown(let ticket): return .unknown(ticket)
        case .notCreated(let resting): return .notCreated(resting.retry, lastDenial: resting.lastDenial)
        case .soldOut(let draft): return .soldOut(draft.booking)
        case .registrationClosed(let draft): return .registrationClosed(draft.booking)
        case .eligibilityLost(let draft, let reason): return .eligibilityLost(draft.booking, reason)
        }
    }

    var blocksNewCreate: Bool {
        switch self {
        case .creating, .checkingStatus, .cancelling, .unknown: return true
        case .notCreated, .soldOut, .registrationClosed, .eligibilityLost: return false
        }
    }

    var blocksCancel: Bool {
        switch self {
        case .creating, .checkingStatus, .cancelling: return true
        case .unknown, .notCreated, .soldOut, .registrationClosed, .eligibilityLost: return false
        }
    }

    var withoutFinishedResult: Pending? {
        switch self {
        case .soldOut, .registrationClosed, .eligibilityLost: return nil
        case .creating, .checkingStatus, .cancelling, .unknown, .notCreated: return self
        }
    }

    func holdsTicket(_ ticket: ObjectIdentifier) -> Bool {
        switch self {
        case .unknown(let check): return ObjectIdentifier(check) == ticket
        case .notCreated(let resting): return ObjectIdentifier(resting.retry) == ticket
        case .creating, .checkingStatus, .cancelling, .soldOut, .registrationClosed, .eligibilityLost: return false
        }
    }
}

extension Optional where Wrapped == Pending {
    func withClosedResult(_ draft: ConfirmedDraft) -> Pending? {
        switch self {
        case nil, .soldOut?, .registrationClosed?, .eligibilityLost?: return .registrationClosed(draft)
        case .notCreated?, .creating?, .checkingStatus?, .cancelling?, .unknown?: return self
        }
    }
}
