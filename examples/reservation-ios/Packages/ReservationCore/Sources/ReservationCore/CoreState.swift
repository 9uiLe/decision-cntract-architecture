import Kernel

struct CoreState: Sendable {
    var lastOperation: UInt64 = 0
    var draft = Draft(workshop: nil, participants: [], confirmation: .unconfirmed)
    var request: Pending?
    var reservations: [ReservationRecord] = []

    mutating func nextOperation() -> OperationId {
        lastOperation += 1
        return OperationId(value: lastOperation)
    }
}

enum CoreStep: Sendable {
    case rejected(Rejection)
    case create(CreateJob)
    case lookUp(operation: OperationId, ticket: StatusCheckTicket)
    case cancel(CancelJob)
}
