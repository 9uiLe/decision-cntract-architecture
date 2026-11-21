import CheckInCore
import EligibilityCore
import Kernel
import ReservationCore

struct HoldingKey: Hashable, Sendable {
    let participant: ParticipantId
    let kindId: String
}

enum RequestRecord: Sendable {
    case created(reservation: ReservationId, session: SessionId, seats: Int)
    case notCreated
}

enum AttemptRecord: Sendable {
    case recorded
    case notCreated
}

struct ServiceState: Sendable {
    let capacities: [SessionId: Int]
    let initialHoldings: [QualificationHolding]

    var requests: [RequestId: RequestRecord] = [:]
    var cancelled: Set<ReservationId> = []
    var holdings: [HoldingKey: EpochMillis] = [:]
    var attempts: [CheckInAttemptId: AttemptRecord] = [:]
    var recordedOrder: [CheckInKey] = []
    var recordedCounts: [CheckInKey: Int] = [:]

    var createScript: [CreateBehavior] = []
    var lookUpScript: [LookupBehavior] = []
    var cancelScript: [CancelBehavior] = []
    var usageScript: [UsageBehavior] = []
    var qualificationScript: [QualificationBehavior] = []
    var checkInCreateScript: [CheckInBehavior] = []
    var checkInLookUpScript: [LookupBehavior] = []

    var held: [UInt64: CheckedContinuation<Void, Never>] = [:]
    var lastHold: UInt64 = 0
    var nextReservation = 0

    var createRequests = 0
    var lookUpRequests = 0
    var cancelRequests = 0
    var usageRequests = 0
    var qualificationRequests = 0
    var checkInCreateRequests = 0
    var checkInLookUpRequests = 0
    var checkInRepeated = 0

    init(capacities: [SessionId: Int], initialHoldings: [QualificationHolding]) {
        self.capacities = capacities
        self.initialHoldings = initialHoldings
        resetHoldings()
    }

    mutating func allocateHold() -> UInt64 {
        lastHold += 1
        return lastHold
    }

    mutating func reset() {
        self = ServiceState(capacities: capacities, initialHoldings: initialHoldings)
    }

    private mutating func resetHoldings() {
        holdings = [:]
        for holding in initialHoldings {
            holdings[HoldingKey(participant: holding.participant, kindId: holding.kind.id)] = holding.expiresAt
        }
    }

    var observations: SimulatorObservations {
        var observed = SimulatorObservations()
        observed.createRequests = createRequests
        observed.lookUpRequests = lookUpRequests
        observed.cancelRequests = cancelRequests
        observed.usageRequests = usageRequests
        observed.qualificationRequests = qualificationRequests
        observed.checkInCreateRequests = checkInCreateRequests
        observed.checkInLookUpRequests = checkInLookUpRequests
        observed.checkInRepeatedForRecordedTarget = checkInRepeated
        observed.activeReservations = createdReservations.filter { !cancelled.contains($0.reservation) }.count
        observed.checkInRecords = recordedOrder.map {
            CheckInRecordCount(reservation: $0.reservation, participant: $0.participant, records: recordedCounts[$0] ?? 0)
        }
        observed.heldCalls = held.count
        observed.queuedCreate = createScript.count
        observed.queuedLookUp = lookUpScript.count
        observed.queuedCancel = cancelScript.count
        observed.queuedUsage = usageScript.count
        observed.queuedQualification = qualificationScript.count
        observed.queuedCheckInCreate = checkInCreateScript.count
        observed.queuedCheckInLookUp = checkInLookUpScript.count
        return observed
    }

    private var createdReservations: [(reservation: ReservationId, session: SessionId, seats: Int)] {
        requests.values.compactMap { record in
            guard case .created(let reservation, let session, let seats) = record else { return nil }
            return (reservation, session, seats)
        }
    }

    mutating func applyCreate(_ request: RequestId, _ session: SessionId, seats: Int, effect: CreateEffect) -> CreateObservation {
        switch effect {
        case .reject:
            requests[request] = .notCreated
            return .notCreated
        case .soldOut:
            requests[request] = .notCreated
            return .full
        case .create:
            guard remainingSeats(session) >= seats else {
                requests[request] = .notCreated
                return .full
            }
            nextReservation += 1
            let id = ReservationId("r-\(nextReservation)")
            requests[request] = .created(reservation: id, session: session, seats: seats)
            return .created(id)
        }
    }

    private func remainingSeats(_ session: SessionId) -> Int {
        let used = createdReservations
            .filter { $0.session == session && !cancelled.contains($0.reservation) }
            .reduce(0) { $0 + $1.seats }
        return (capacities[session] ?? 0) - used
    }

    func observeRequest(_ request: RequestId) -> LookupObservation {
        switch requests[request] {
        case .created(let reservation, _, _)?: return .found(reservation)
        case .notCreated?: return .notCreated
        case nil: return .notFound
        }
    }

    func observeUsage(_ reservation: ReservationId) -> UsageObservation {
        if cancelled.contains(reservation) { return .cancelled }
        return createdReservations.contains { $0.reservation == reservation } ? .active : .notFound
    }

    mutating func applyCancel(_ reservation: ReservationId) -> CancelObservation {
        let exists = createdReservations.contains { $0.reservation == reservation }
        guard exists, cancelled.insert(reservation).inserted else { return .declined }
        return .cancelled
    }

    mutating func applyCheckIn(_ attempt: CheckInAttemptId, _ target: CheckInKey, effect: CheckInEffect) -> CheckInCreateObservation {
        if let known = attempts[attempt] {
            switch known {
            case .recorded: return .recorded
            case .notCreated: return .notCreated
            }
        }
        if effect == .reject {
            attempts[attempt] = .notCreated
            return .notCreated
        }
        if recordedCounts[target] != nil {
            checkInRepeated += 1
            return .alreadyRecorded
        }
        attempts[attempt] = .recorded
        recordedOrder.append(target)
        recordedCounts[target] = 1
        return .recorded
    }

    func observeAttempt(_ attempt: CheckInAttemptId) -> CheckInLookupObservation {
        switch attempts[attempt] {
        case .recorded?: return .found
        case .notCreated?: return .notCreated
        case nil: return .notFound
        }
    }

    func wireText(_ participant: ParticipantId, _ kind: QualificationKind) -> String {
        let wire: QualificationWire
        if let expiresAt = holdings[HoldingKey(participant: participant, kindId: kind.id)] {
            wire = heldWire(kind: kind, expiresAt: expiresAt)
        } else {
            wire = QualificationWire(held: false, code: nil, validUntil: nil)
        }
        return QualificationWireFormat.render(wire)
    }
}
