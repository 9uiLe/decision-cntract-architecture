import EligibilityCore
import Kernel
import KernelTestSupport
import os
import ReservationCore

let hour: Int64 = 3_600_000
let start: Int64 = 100 * hour

let pottery = Workshop(
    id: SessionId("pottery"), name: "陶芸", start: EpochMillis(start), seatsHint: 4, requirement: .notRequired
)
let cooking = Workshop(
    id: SessionId("cooking"), name: "料理", start: EpochMillis(start + 10 * hour), seatsHint: 4, requirement: .notRequired
)
let cancellable = start - 30 * hour
let alice = ParticipantId("alice")
let bob = ParticipantId("bob")
let advancedKind = QualificationKind(id: "pottery-advanced", name: "上級ろくろ修了")
let advanced = Workshop(
    id: SessionId("advanced"), name: "上級陶芸", start: EpochMillis(start), seatsHint: 4, requirement: .required(advancedKind)
)
let catalog = Catalog(
    workshops: [pottery, cooking, advanced],
    participants: [Participant(id: alice, name: "アリス"), Participant(id: bob, name: "ボブ")]
)

final class LockedValue<Value: Sendable>: Sendable {
    private let lock: OSAllocatedUnfairLock<Value>

    init(_ value: Value) { lock = OSAllocatedUnfairLock(initialState: value) }

    func withLock<R: Sendable>(_ body: @Sendable (inout Value) -> R) -> R { lock.withLock(body) }
}

final class ScriptedPort: ReservationPort {
    typealias CreateHandler = @Sendable (RequestId) async -> CreateObservation
    typealias LookupHandler = @Sendable (RequestId) async -> LookupObservation
    typealias CancelHandler = @Sendable (ReservationId) async -> CancelObservation
    typealias UsageHandler = @Sendable (ReservationId) async -> UsageObservation

    private struct Handlers: Sendable {
        var create: CreateHandler = { _ in .created(ReservationId("r-1")) }
        var lookUp: LookupHandler = { _ in .notFound }
        var cancel: CancelHandler = { _ in .cancelled }
        var usage: UsageHandler = { _ in .active }
    }

    let createCalls = CallLog<RequestId>()
    let lookUpCalls = CallLog<RequestId>()
    let cancelCalls = CallLog<ReservationId>()
    private let handlers = LockedValue(Handlers())

    func onCreate(_ handler: @escaping CreateHandler) { handlers.withLock { $0.create = handler } }
    func onLookUp(_ handler: @escaping LookupHandler) { handlers.withLock { $0.lookUp = handler } }
    func onCancel(_ handler: @escaping CancelHandler) { handlers.withLock { $0.cancel = handler } }
    func onUsage(_ handler: @escaping UsageHandler) { handlers.withLock { $0.usage = handler } }

    func createReservation(request: RequestId, session: SessionId, participants: [ParticipantId]) async -> CreateObservation {
        createCalls.record(request)
        return await handlers.withLock { $0.create }(request)
    }

    func lookUpReservation(request: RequestId) async -> LookupObservation {
        lookUpCalls.record(request)
        return await handlers.withLock { $0.lookUp }(request)
    }

    func cancelReservation(_ reservation: ReservationId) async -> CancelObservation {
        cancelCalls.record(reservation)
        return await handlers.withLock { $0.cancel }(reservation)
    }

    func lookUpUsage(_ reservation: ReservationId) async -> UsageObservation {
        await handlers.withLock { $0.usage }(reservation)
    }
}

final class QualificationService: EligibilityPort {
    private let observations = LockedValue<[ParticipantId: QualificationObservation]>([:])
    let fetchCalls = CallLog<ParticipantId>()

    func set(_ participant: ParticipantId, _ observation: QualificationObservation) {
        observations.withLock { $0[participant] = observation }
    }

    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation {
        fetchCalls.record(participant)
        return observations.withLock { $0[participant] } ?? .notHeld
    }
}

struct Fixture {
    let port = ScriptedPort()
    let service = QualificationService()
    let clock: ManualClock
    let timeout = ManualTimeout()
    let eligibility: EligibilityCore
    let core: ReservationCore

    init(now: Int64 = start - hour, authority: ((EligibilityCore) -> any EligibilityAuthority)? = nil) {
        let clock = ManualClock(EpochMillis(now))
        let eligibility = makeEligibilityCore(port: service, clock: clock, timeout: timeout)
        let counter = LockedValue(0)
        self.clock = clock
        self.eligibility = eligibility
        core = makeReservationCore(
            catalog: catalog,
            port: port,
            eligibility: authority?(eligibility) ?? eligibility,
            clock: clock,
            newRequestId: { RequestId("req-\(counter.withLock { value -> Int in value += 1; return value })") },
            timeout: timeout
        )
    }

    var snapshot: CoreSnapshot { core.snapshot }

    @discardableResult
    func settled(where predicate: @escaping @Sendable (CoreSnapshot) -> Bool) async -> CoreSnapshot? {
        await firstValue(of: core.snapshots(), where: predicate)
    }

    func confirmed(_ workshop: Workshop = pottery, who: [ParticipantId] = [alice]) -> ConfirmedDraft {
        _ = core.selectWorkshop(workshop.id)
        for participant in who { _ = core.toggleParticipant(participant) }
        guard case .confirmed(let draft) = core.confirmWithoutQualification() else { fatalError("確認済みになるはず") }
        return draft
    }

    func reserve(_ workshop: Workshop = pottery) async -> ReservedRecord {
        _ = core.submit(confirmed(workshop))
        await settled { $0.request == nil && !$0.reservations.isEmpty }
        guard case .reserved(let record)? = snapshot.reservations.last else { fatalError("成立するはず") }
        return record
    }

    func qualifiedDraft(who: [ParticipantId] = [alice]) async -> ConfirmOutcome {
        _ = core.selectWorkshop(advanced.id)
        for participant in who { _ = core.toggleParticipant(participant) }
        _ = core.requestEligibility()
        _ = await firstValue(of: eligibility.snapshots()) { snapshot in
            who.allSatisfy { participant in
                guard case .settled? = snapshot.statuses[EligibilityKey(participant: participant, kind: advancedKind)] else {
                    return false
                }
                return true
            }
        }
        return core.confirmQualified()
    }
}

func rejection(_ acceptance: Acceptance) -> Rejection? {
    guard case .rejected(let reason) = acceptance else { return nil }
    return reason
}
