import Kernel
public import ReservationCore

private let opensBeforeStartMillis: Int64 = 30 * 60 * 1000
private let closesAfterStartMillis: Int64 = 15 * 60 * 1000

public final class CheckInCore: Sendable {
    private struct Entry: Sendable {
        let operation: UInt64
        let status: ParticipantCheckIn
    }

    private struct State: Sendable {
        var lastOperation: UInt64 = 0
        var entries: [CheckInKey: Entry] = [:]
    }

    private enum Begin: Sendable {
        case rejected(CheckInRejection)
        case started(UInt64)
    }

    private let source: any AdmissionSource
    private let port: any CheckInPort
    private let clock: any Clock
    private let newAttemptId: @Sendable () -> CheckInAttemptId
    private let timeout: any OperationTimeout
    private let store: Store<State, CheckInSnapshot>

    init(
        source: any AdmissionSource,
        port: any CheckInPort,
        clock: any Clock,
        newAttemptId: @escaping @Sendable () -> CheckInAttemptId,
        timeout: any OperationTimeout
    ) {
        self.source = source
        self.port = port
        self.clock = clock
        self.newAttemptId = newAttemptId
        self.timeout = timeout
        store = Store(State()) { state in CheckInSnapshot(entries: state.entries.mapValues(\.status)) }
    }

    public var snapshot: CheckInSnapshot { store.snapshot }

    public func snapshots() -> AsyncStream<CheckInSnapshot> { store.updates() }

    public func window(for admission: ReservationAdmission) -> CheckInWindow { window(at: admission.start) }

    public func checkIn(_ admission: ReservationAdmission) -> CheckInAcceptance {
        begin(CheckInKey(reservation: admission.reservation, participant: admission.participant), admission) { current in
            switch current {
            case nil, .notAdmitted?: return nil
            case .processing?: return .busy
            case .checkedIn?: return .alreadyCheckedIn
            case .unknown?, .notCreated?: return .unresolved
            }
        }
    }

    public func retry(_ ticket: CheckInRetryTicket) -> CheckInAcceptance {
        let admission = ticket.admission
        return begin(CheckInKey(reservation: admission.reservation, participant: admission.participant), admission) { current in
            switch current {
            case .notCreated(let held)?: return held === ticket ? nil : .stale
            case .processing?: return .busy
            case nil, .checkedIn?, .unknown?, .notAdmitted?: return .stale
            }
        }
    }

    public func checkStatus(_ ticket: CheckInStatusTicket) -> CheckInAcceptance {
        let admission = ticket.admission
        let key = CheckInKey(reservation: admission.reservation, participant: admission.participant)
        let begun = store.mutate { state -> Begin in
            guard case .unknown(let held)? = state.entries[key]?.status, held === ticket else {
                if case .processing? = state.entries[key]?.status { return .rejected(.busy) }
                return .rejected(.stale)
            }
            state.lastOperation += 1
            state.entries[key] = Entry(operation: state.lastOperation, status: .processing(.lookingUp))
            return .started(state.lastOperation)
        }
        guard case .started(let operation) = begun else {
            if case .rejected(let reason) = begun { return .rejected(reason) }
            return .accepted
        }
        Task {
            let port = port
            let observation = await timeout.observe(unconfirmed: .failed) { await port.lookUpCheckIn(attempt: ticket.attempt) }
            let next: ParticipantCheckIn
            switch observation {
            case .found: next = .checkedIn
            case .notCreated: next = .notCreated(CheckInRetryTicket(admission: admission))
            case .notFound, .failed: next = .unknown(CheckInStatusTicket(attempt: ticket.attempt, admission: admission))
            }
            settle(key, operation: operation, next)
        }
        return .accepted
    }

    private func begin(
        _ key: CheckInKey,
        _ admission: ReservationAdmission,
        _ precondition: @Sendable (ParticipantCheckIn?) -> CheckInRejection?
    ) -> CheckInAcceptance {
        let begun = store.mutate { [self] state -> Begin in
            if let rejection = precondition(state.entries[key]?.status) { return .rejected(rejection) }
            switch window(at: admission.start) {
            case .notYetOpen: return .rejected(.notYetOpen)
            case .closed: return .rejected(.closed)
            case .open: break
            }
            state.lastOperation += 1
            state.entries[key] = Entry(operation: state.lastOperation, status: .processing(.confirmingReservation))
            return .started(state.lastOperation)
        }
        switch begun {
        case .rejected(let reason):
            return .rejected(reason)
        case .started(let operation):
            Task { await proceed(key, operation: operation, requested: admission) }
            return .accepted
        }
    }

    // 予約の照会を待つ間に受付時間が終わることがある。照会の後で、受付を作る前に時刻を判定し直す。
    private func proceed(_ key: CheckInKey, operation: UInt64, requested: ReservationAdmission) async {
        let askedAt = clock.now()
        let lookup = await source.admission(reservation: key.reservation, participant: key.participant)
        let answeredAt = clock.now()
        let current: CurrentAdmission
        switch lookup {
        case .valid(let value): current = value
        case .unavailable(let reason): return deny(key, operation, .reservationUnavailable(reason))
        case .unconfirmed: return deny(key, operation, .reservationUnconfirmed)
        }
        let admission = current.record
        guard admission.isFor(key, requested) else { return deny(key, operation, .wrongTarget) }
        guard current.observedAt >= askedAt, current.observedAt <= answeredAt else {
            return deny(key, operation, .reservationUnconfirmed)
        }
        switch window(at: admission.start) {
        case .notYetOpen: return deny(key, operation, .notYetOpen)
        case .closed: return deny(key, operation, .closed)
        case .open: break
        }
        guard advance(key, operation, to: .recording) else { return }
        let attempt = newAttemptId()
        let port = port
        let observation = await timeout.observe(unconfirmed: .noResponse) {
            await port.createCheckIn(attempt: attempt, reservation: key.reservation, participant: key.participant)
        }
        let next: ParticipantCheckIn
        switch observation {
        case .recorded, .alreadyRecorded: next = .checkedIn
        case .notCreated: next = .notCreated(CheckInRetryTicket(admission: admission))
        case .noResponse: next = .unknown(CheckInStatusTicket(attempt: attempt, admission: admission))
        }
        settle(key, operation: operation, next)
    }

    private func deny(_ key: CheckInKey, _ operation: UInt64, _ denial: Denial) {
        settle(key, operation: operation, .notAdmitted(denial))
    }

    private func window(at start: EpochMillis) -> CheckInWindow {
        let now = clock.now().value
        if now < start.value - opensBeforeStartMillis { return .notYetOpen }
        if now >= start.value + closesAfterStartMillis { return .closed }
        return .open
    }

    private func advance(_ key: CheckInKey, _ operation: UInt64, to stage: ProcessingStage) -> Bool {
        store.mutate { state in
            guard state.entries[key]?.operation == operation else { return false }
            state.entries[key] = Entry(operation: operation, status: .processing(stage))
            return true
        }
    }

    private func settle(_ key: CheckInKey, operation: UInt64, _ next: ParticipantCheckIn) {
        store.mutate { state in
            guard let current = state.entries[key], current.operation == operation, case .processing = current.status else {
                return
            }
            state.entries[key] = Entry(operation: operation, status: next)
        }
    }
}

extension ReservationAdmission {
    fileprivate func isFor(_ key: CheckInKey, _ requested: ReservationAdmission) -> Bool {
        reservation == key.reservation && participant == key.participant && session == requested.session
    }
}
