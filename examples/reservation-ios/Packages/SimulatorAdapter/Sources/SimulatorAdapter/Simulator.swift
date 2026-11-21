public import CheckInCore
public import EligibilityCore
public import Kernel
public import ReservationCore

public struct Simulator: Sendable {
    public let reservation: any ReservationPort
    public let eligibility: any EligibilityPort
    public let checkIn: any CheckInPort
    public let controls: any SimulatorControls
}

public func makeSimulator(capacities: [SessionId: Int], holdings: [QualificationHolding] = []) -> Simulator {
    let service = SimulatedService(capacities: capacities, holdings: holdings)
    return Simulator(
        reservation: ReservationAdapter(service: service),
        eligibility: EligibilityAdapter(service: service),
        checkIn: CheckInAdapter(service: service),
        controls: ServiceControls(service: service)
    )
}

// 三つのサービス役は一つの世界を共有するが、互いの判断を代行しない。
final class SimulatedService: Sendable {
    let store: Store<ServiceState, SimulatorObservations>

    init(capacities: [SessionId: Int], holdings: [QualificationHolding]) {
        store = Store(ServiceState(capacities: capacities, initialHoldings: holdings)) { $0.observations }
    }

    // キャンセルの旗は onCancel より先に立つ。旗の確認と登録を同じ排他区間で行い、登録前に走った onCancel を取りこぼさない。
    func awaitRelease() async {
        let id = store.mutate { $0.allocateHold() }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let resumeNow = store.mutate { state -> Bool in
                    if Task.isCancelled { return true }
                    state.held[id] = continuation
                    return false
                }
                if resumeNow { continuation.resume() }
            }
        } onCancel: {
            store.mutate { $0.held.removeValue(forKey: id) }?.resume()
        }
    }

    func releaseHeld() {
        let releasing = store.mutate { state -> [CheckedContinuation<Void, Never>] in
            defer { state.held = [:] }
            return Array(state.held.values)
        }
        for continuation in releasing { continuation.resume() }
    }
}

struct ReservationAdapter: ReservationPort {
    let service: SimulatedService

    func createReservation(request: RequestId, session: SessionId, participants: [ParticipantId]) async -> CreateObservation {
        let (behavior, response) = service.store.mutate { state -> (CreateBehavior, CreateObservation) in
            state.createRequests += 1
            let behavior = state.createScript.isEmpty ? CreateBehavior.succeed : state.createScript.removeFirst()
            return (behavior, state.applyCreate(request, session, seats: participants.count, effect: behavior.effect))
        }
        switch behavior.delivery {
        case .respond: return response
        case .lose: return .noResponse
        case .hold:
            await service.awaitRelease()
            return response
        }
    }

    func lookUpReservation(request: RequestId) async -> LookupObservation {
        let behavior = service.store.mutate { state -> LookupBehavior in
            state.lookUpRequests += 1
            state.lastLookedUpRequest = request
            return state.lookUpScript.isEmpty ? .normal : state.lookUpScript.removeFirst()
        }
        switch behavior {
        case .normal: return service.store.read { $0.observeRequest(request) }
        case .notFound: return .notFound
        case .fail: return .failed
        case .hold:
            await service.awaitRelease()
            return service.store.read { $0.observeRequest(request) }
        }
    }

    func cancelReservation(_ reservation: ReservationId) async -> CancelObservation {
        let behavior = service.store.mutate { state -> CancelBehavior in
            state.cancelRequests += 1
            return state.cancelScript.isEmpty ? .normal : state.cancelScript.removeFirst()
        }
        switch behavior {
        case .normal: return service.store.mutate { $0.applyCancel(reservation) }
        case .decline: return .declined
        case .lose:
            _ = service.store.mutate { $0.applyCancel(reservation) }
            return .noResponse
        case .hold:
            await service.awaitRelease()
            return service.store.mutate { $0.applyCancel(reservation) }
        }
    }

    func lookUpUsage(_ reservation: ReservationId) async -> UsageObservation {
        let behavior = service.store.mutate { state -> UsageBehavior in
            state.usageRequests += 1
            return state.usageScript.isEmpty ? .normal : state.usageScript.removeFirst()
        }
        switch behavior {
        case .normal: return service.store.read { $0.observeUsage(reservation) }
        case .fail: return .failed
        case .hold:
            await service.awaitRelease()
            return service.store.read { $0.observeUsage(reservation) }
        }
    }
}

struct EligibilityAdapter: EligibilityPort {
    let service: SimulatedService

    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation {
        let behavior = service.store.mutate { state -> QualificationBehavior in
            state.qualificationRequests += 1
            return state.qualificationScript.isEmpty ? .normal : state.qualificationScript.removeFirst()
        }
        switch behavior {
        case .normal: return decode(participant, kind)
        case .noResponse: return .unavailable
        case .unrecognized: return QualificationDecoder.decode("unrecognized-response", requested: kind)
        case .hold:
            await service.awaitRelease()
            return decode(participant, kind)
        }
    }

    private func decode(_ participant: ParticipantId, _ kind: QualificationKind) -> QualificationObservation {
        let text = service.store.read { $0.wireText(participant, kind) }
        return QualificationDecoder.decode(text, requested: kind)
    }
}

struct CheckInAdapter: CheckInPort {
    let service: SimulatedService

    func createCheckIn(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId) async
        -> CheckInCreateObservation
    {
        let target = CheckInKey(reservation: reservation, participant: participant)
        let (behavior, response) = service.store.mutate { state -> (CheckInBehavior, CheckInCreateObservation) in
            state.checkInCreateRequests += 1
            let behavior = state.checkInCreateScript.isEmpty ? CheckInBehavior.succeed : state.checkInCreateScript.removeFirst()
            return (behavior, state.applyCheckIn(attempt, target, effect: behavior.effect))
        }
        switch behavior.delivery {
        case .respond: return response
        case .lose: return .noResponse
        case .hold:
            await service.awaitRelease()
            return response
        }
    }

    func lookUpCheckIn(attempt: CheckInAttemptId) async -> CheckInLookupObservation {
        let behavior = service.store.mutate { state -> LookupBehavior in
            state.checkInLookUpRequests += 1
            return state.checkInLookUpScript.isEmpty ? .normal : state.checkInLookUpScript.removeFirst()
        }
        switch behavior {
        case .normal: return service.store.read { $0.observeAttempt(attempt) }
        case .notFound: return .notFound
        case .fail: return .failed
        case .hold:
            await service.awaitRelease()
            return service.store.read { $0.observeAttempt(attempt) }
        }
    }
}

struct ServiceControls: SimulatorControls {
    let service: SimulatedService

    var observations: SimulatorObservations { service.store.snapshot }

    func observationUpdates() -> AsyncStream<SimulatorObservations> { service.store.updates() }

    func enqueueCreate(_ behavior: CreateBehavior) { service.store.mutate { $0.createScript.append(behavior) } }

    func enqueueLookUp(_ behavior: LookupBehavior) { service.store.mutate { $0.lookUpScript.append(behavior) } }

    func enqueueCancel(_ behavior: CancelBehavior) { service.store.mutate { $0.cancelScript.append(behavior) } }

    func enqueueUsage(_ behavior: UsageBehavior) { service.store.mutate { $0.usageScript.append(behavior) } }

    func enqueueQualification(_ behavior: QualificationBehavior) {
        service.store.mutate { $0.qualificationScript.append(behavior) }
    }

    func enqueueCheckInCreate(_ behavior: CheckInBehavior) { service.store.mutate { $0.checkInCreateScript.append(behavior) } }

    func enqueueCheckInLookUp(_ behavior: LookupBehavior) { service.store.mutate { $0.checkInLookUpScript.append(behavior) } }

    func grantQualification(participant: ParticipantId, kind: QualificationKind, expiresAt: EpochMillis) {
        precondition(ServiceCodes.code(for: kind) != nil, "資格サービスの対象にない種別です: \(kind.id)")
        service.store.mutate { $0.holdings[HoldingKey(participant: participant, kindId: kind.id)] = expiresAt }
    }

    func revokeQualification(participant: ParticipantId, kind: QualificationKind) {
        service.store.mutate { _ = $0.holdings.removeValue(forKey: HoldingKey(participant: participant, kindId: kind.id)) }
    }

    func releaseHeld() { service.releaseHeld() }

    func reset() {
        let releasing = service.store.mutate { state -> [CheckedContinuation<Void, Never>] in
            let held = Array(state.held.values)
            state.reset()
            return held
        }
        for continuation in releasing { continuation.resume() }
    }
}
