import CheckInCore
import Combine
import EligibilityCore
import Kernel
import KernelTestSupport
import os
import ReservationCore
import ReservationUI

let hour: Int64 = 3_600_000
let minute: Int64 = 60_000
let start: Int64 = 100 * hour
let alice = ParticipantId("alice")
let bob = ParticipantId("bob")
let advancedKind = QualificationKind(id: "pottery-advanced", name: "上級ろくろ修了")
let pottery = Workshop(id: SessionId("pottery"), name: "陶芸", start: EpochMillis(start), seatsHint: 4, requirement: .notRequired)
let advanced = Workshop(
    id: SessionId("advanced"), name: "上級陶芸", start: EpochMillis(start), seatsHint: 4, requirement: .required(advancedKind)
)
let catalog = Catalog(
    workshops: [pottery, advanced],
    participants: [Participant(id: alice, name: "アリス"), Participant(id: bob, name: "ボブ")]
)

final class LockedValue<Value: Sendable>: Sendable {
    private let lock: OSAllocatedUnfairLock<Value>

    init(_ value: Value) { lock = OSAllocatedUnfairLock(initialState: value) }

    func withLock<R: Sendable>(_ body: @Sendable (inout Value) -> R) -> R { lock.withLock(body) }
}

final class StubReservationPort: ReservationPort {
    let create = LockedValue<CreateObservation>(.created(ReservationId("r-1")))
    let cancel = LockedValue<CancelObservation>(.cancelled)
    let release = LockedValue<KernelTestSupport.Deferred<CancelObservation>?>(nil)

    func createReservation(request: RequestId, session: SessionId, participants: [ParticipantId]) async -> CreateObservation {
        create.withLock { $0 }
    }

    func lookUpReservation(request: RequestId) async -> LookupObservation { .notFound }

    func cancelReservation(_ reservation: ReservationId) async -> CancelObservation {
        if let gate = release.withLock({ $0 }) { return await gate.wait() ?? .noResponse }
        return cancel.withLock { $0 }
    }

    func lookUpUsage(_ reservation: ReservationId) async -> UsageObservation { .active }
}

final class StubQualificationPort: EligibilityPort {
    let observations = LockedValue<[ParticipantId: QualificationObservation]>([:])

    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation {
        observations.withLock { $0[participant] } ?? .notHeld
    }
}

final class StubCheckInPort: CheckInPort {
    func createCheckIn(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId) async
        -> CheckInCreateObservation
    {
        .recorded
    }

    func lookUpCheckIn(attempt: CheckInAttemptId) async -> CheckInLookupObservation { .found }
}

@MainActor
struct Screen {
    let clock: ManualClock
    let timeout = ManualTimeout()
    let reservationPort = StubReservationPort()
    let qualification = StubQualificationPort()
    let eligibility: EligibilityCore
    let core: ReservationCore
    let checkIn: CheckInCore
    let viewModel: ReservationViewModel
    let timeChanged: AsyncStream<Void>.Continuation

    init(now: Int64 = start - 30 * hour) {
        let clock = ManualClock(EpochMillis(now))
        let timeout = timeout
        let qualification = qualification
        let eligibility = makeEligibilityCore(port: qualification, clock: clock, timeout: timeout)
        let core = makeReservationCore(
            catalog: catalog, port: reservationPort, eligibility: eligibility, clock: clock,
            newRequestId: { RequestId("q") }, timeout: timeout
        )
        let checkIn = makeCheckInCore(
            source: core, port: StubCheckInPort(), clock: clock, newAttemptId: { CheckInAttemptId("a") }, timeout: timeout
        )
        let (changes, continuation) = AsyncStream.makeStream(of: Void.self)
        self.clock = clock
        self.eligibility = eligibility
        self.core = core
        self.checkIn = checkIn
        timeChanged = continuation
        viewModel = ReservationViewModel(core: core, eligibility: eligibility, checkIn: checkIn, catalog: catalog, timeChanges: changes)
        viewModel.start()
    }

    func advance(to time: Int64) {
        clock.set(EpochMillis(time))
        timeChanged.yield()
    }

    @discardableResult
    func state(where predicate: @escaping @MainActor (ReservationScreenState) -> Bool) async -> ReservationScreenState {
        for await value in viewModel.$state.values where predicate(value) { return value }
        return viewModel.state
    }
}
