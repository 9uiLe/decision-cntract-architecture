import CheckInCore
import Kernel
import KernelTestSupport
import os
import ReservationCore
import ReservationCoreTestSupport

let minute: Int64 = 60_000
let hour: Int64 = 60 * minute
let start: Int64 = 100 * hour
let alice = ParticipantId("alice")
let bob = ParticipantId("bob")
let carol = ParticipantId("carol")
let workshop = Workshop(id: SessionId("open"), name: "通常回", start: EpochMillis(start), seatsHint: 4, requirement: .notRequired)
let catalog = Catalog(
    workshops: [workshop],
    participants: [
        Participant(id: alice, name: "A"), Participant(id: bob, name: "B"), Participant(id: carol, name: "C"),
    ]
)

final class LockedValue<Value: Sendable>: Sendable {
    private let lock: OSAllocatedUnfairLock<Value>

    init(_ value: Value) { lock = OSAllocatedUnfairLock(initialState: value) }

    func withLock<R: Sendable>(_ body: @Sendable (inout Value) -> R) -> R { lock.withLock(body) }
}

final class ReservationService: ReservationPort {
    private let usage = LockedValue(UsageObservation.active)
    private let cancel = LockedValue(CancelObservation.cancelled)
    private let creates = LockedValue(0)

    func setUsage(_ value: UsageObservation) { usage.withLock { $0 = value } }
    func setCancel(_ value: CancelObservation) { cancel.withLock { $0 = value } }

    func createReservation(request: RequestId, session: SessionId, participants: [ParticipantId]) async -> CreateObservation {
        .created(ReservationId("r-\(creates.withLock { value -> Int in value += 1; return value })"))
    }

    func lookUpReservation(request: RequestId) async -> LookupObservation { .notFound }

    func cancelReservation(_ reservation: ReservationId) async -> CancelObservation { cancel.withLock { $0 } }

    func lookUpUsage(_ reservation: ReservationId) async -> UsageObservation { usage.withLock { $0 } }
}

final class CheckInService: CheckInPort {
    typealias CreateHandler = @Sendable (CheckInAttemptId) async -> CheckInCreateObservation
    typealias LookupHandler = @Sendable (CheckInAttemptId) async -> CheckInLookupObservation

    private struct Handlers: Sendable {
        var create: CreateHandler = { _ in .recorded }
        var lookUp: LookupHandler = { _ in .found }
    }

    let createCalls = CallLog<CheckInAttemptId>()
    let lookUpCalls = CallLog<CheckInAttemptId>()
    private let handlers = LockedValue(Handlers())

    func onCreate(_ handler: @escaping CreateHandler) { handlers.withLock { $0.create = handler } }
    func onLookUp(_ handler: @escaping LookupHandler) { handlers.withLock { $0.lookUp = handler } }

    func createCheckIn(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId) async
        -> CheckInCreateObservation
    {
        createCalls.record(attempt)
        return await handlers.withLock { $0.create }(attempt)
    }

    func lookUpCheckIn(attempt: CheckInAttemptId) async -> CheckInLookupObservation {
        lookUpCalls.record(attempt)
        return await handlers.withLock { $0.lookUp }(attempt)
    }
}

final class HookedSource: AdmissionSource {
    typealias Hook = @Sendable (ReservationId, ParticipantId) async -> AdmissionLookup?

    private let base: ReservationCore
    private let hook = LockedValue<Hook?>(nil)
    let calls = CallLog<ParticipantId>()

    init(_ base: ReservationCore) { self.base = base }

    func setHook(_ value: @escaping Hook) { hook.withLock { $0 = value } }

    func admission(reservation: ReservationId, participant: ParticipantId) async -> AdmissionLookup {
        calls.record(participant)
        if let hook = hook.withLock({ $0 }), let replaced = await hook(reservation, participant) { return replaced }
        return await base.admission(reservation: reservation, participant: participant)
    }
}

struct World {
    let reservations = ReservationService()
    let checkIns = CheckInService()
    let clock: ManualClock
    let timeout = ManualTimeout()
    let reservation: ReservationCore
    let source: HookedSource
    let checkIn: CheckInCore

    init(now: Int64 = start - 72 * hour) {
        let clock = ManualClock(EpochMillis(now))
        let reservation = makeTestReservationCore(
            catalog: catalog, port: reservations, clock: clock, newRequestId: { RequestId("q") }, timeout: timeout
        )
        let source = HookedSource(reservation)
        let attempts = LockedValue(0)
        self.clock = clock
        self.reservation = reservation
        self.source = source
        checkIn = makeCheckInCore(
            source: source,
            port: checkIns,
            clock: clock,
            newAttemptId: { CheckInAttemptId("a-\(attempts.withLock { value -> Int in value += 1; return value })") },
            timeout: timeout
        )
    }

    func reserve(_ who: [ParticipantId]) async -> ReservedRecord {
        _ = reservation.selectWorkshop(workshop.id)
        for participant in who { _ = reservation.toggleParticipant(participant) }
        guard case .confirmed(let draft) = reservation.confirmWithoutQualification() else { fatalError("確認できるはず") }
        _ = reservation.submit(draft)
        _ = await firstValue(of: reservation.snapshots()) { $0.request == nil && !$0.reservations.isEmpty }
        guard case .reserved(let record)? = reservation.snapshot.reservations.last else { fatalError("成立するはず") }
        return record
    }

    func admission(_ record: ReservedRecord, _ participant: ParticipantId) -> ReservationAdmission {
        guard let admission = record.admissions.first(where: { $0.participant == participant }) else {
            fatalError("予約に含まれるはず")
        }
        return admission
    }

    func status(_ record: ReservedRecord, _ participant: ParticipantId) -> ParticipantCheckIn? {
        checkIn.snapshot.entries[CheckInKey(reservation: record.reservation, participant: participant)]
    }

    @discardableResult
    func settled(
        _ record: ReservedRecord, _ participant: ParticipantId,
        where predicate: @escaping @Sendable (ParticipantCheckIn?) -> Bool
    ) async -> CheckInSnapshot? {
        let key = CheckInKey(reservation: record.reservation, participant: participant)
        return await firstValue(of: checkIn.snapshots()) { predicate($0.entries[key]) }
    }
}

func isCheckedIn(_ status: ParticipantCheckIn?) -> Bool {
    if case .checkedIn? = status { return true }
    return false
}

func isUnknown(_ status: ParticipantCheckIn?) -> Bool {
    if case .unknown? = status { return true }
    return false
}

func isNotCreated(_ status: ParticipantCheckIn?) -> Bool {
    if case .notCreated? = status { return true }
    return false
}

func isNotAdmitted(_ status: ParticipantCheckIn?) -> Bool {
    if case .notAdmitted? = status { return true }
    return false
}
