import CheckInCore
import EligibilityCore
import Kernel
import KernelTestSupport
import ReservationCore
import Testing

@testable import SimulatorAdapter

private let session = SessionId("s")
private let alice = ParticipantId("alice")

@Suite(.timeLimit(.minutes(1)))
struct SimulatorTests {
    @Test func 通常成立は予約を作り同じ要求の照会で見つかる() async {
        let simulator = makeSimulator(capacities: [session: 2])
        let result = await simulator.reservation.createReservation(request: RequestId("q1"), session: session, participants: [alice])
        #expect(result == .created(ReservationId("r-1")))
        #expect(await simulator.reservation.lookUpReservation(request: RequestId("q1")) == .found(ReservationId("r-1")))
        #expect(simulator.controls.observations.activeReservations == 1)
    }

    @Test func 応答喪失は予約が成立したまま応答だけを返さない() async {
        let simulator = makeSimulator(capacities: [session: 2])
        simulator.controls.enqueueCreate(.loseResponseAfterCreate)
        #expect(await simulator.reservation.createReservation(request: RequestId("q1"), session: session, participants: [alice]) == .noResponse)
        #expect(simulator.controls.observations.activeReservations == 1)
        #expect(await simulator.reservation.lookUpReservation(request: RequestId("q1")) == .found(ReservationId("r-1")))
    }

    @Test func 作成前の拒否と満席は予約を作らず照会では未成立を返す() async {
        let simulator = makeSimulator(capacities: [session: 2])
        simulator.controls.enqueueCreate(.rejectBeforeCreate)
        simulator.controls.enqueueCreate(.soldOut)
        #expect(await simulator.reservation.createReservation(request: RequestId("a"), session: session, participants: [alice]) == .notCreated)
        #expect(await simulator.reservation.createReservation(request: RequestId("b"), session: session, participants: [alice]) == .full)
        #expect(await simulator.reservation.lookUpReservation(request: RequestId("a")) == .notCreated)
        #expect(simulator.controls.observations.activeReservations == 0)
    }

    @Test func 定員を超える予約は満席になり取消で席が戻る() async {
        let simulator = makeSimulator(capacities: [session: 1])
        #expect(await simulator.reservation.createReservation(request: RequestId("a"), session: session, participants: [alice]) == .created(ReservationId("r-1")))
        #expect(await simulator.reservation.createReservation(request: RequestId("b"), session: session, participants: [alice]) == .full)
        #expect(await simulator.reservation.cancelReservation(ReservationId("r-1")) == .cancelled)
        #expect(await simulator.reservation.createReservation(request: RequestId("c"), session: session, participants: [alice]) == .created(ReservationId("r-2")))
    }

    @Test func 保留した応答は解放まで返らず解放後の現在の状態を読む() async {
        let simulator = makeSimulator(capacities: [session: 2])
        _ = await simulator.reservation.createReservation(request: RequestId("a"), session: session, participants: [alice])
        simulator.controls.enqueueUsage(.hold)
        async let usage = simulator.reservation.lookUpUsage(ReservationId("r-1"))
        _ = await firstValue(of: simulator.controls.observationUpdates()) { $0.heldCalls == 1 }
        #expect(await simulator.reservation.cancelReservation(ReservationId("r-1")) == .cancelled)
        simulator.controls.releaseHeld()
        #expect(await usage == .cancelled)
        #expect(simulator.controls.observations.heldCalls == 0)
    }

    @Test func 呼び出し元がキャンセルしても保留の記録を残さない() async {
        let simulator = makeSimulator(capacities: [session: 2])
        simulator.controls.enqueueCreate(.holdResponse)
        let task = Task {
            await simulator.reservation.createReservation(request: RequestId("a"), session: session, participants: [alice])
        }
        _ = await firstValue(of: simulator.controls.observationUpdates()) { $0.heldCalls == 1 }
        task.cancel()
        _ = await task.value
        #expect(simulator.controls.observations.heldCalls == 0)
    }

    @Test func リセットは記録と台本と回数を消し初期の資格に戻す() async {
        let kind = QualificationKind(id: "pottery-advanced")
        let holding = QualificationHolding(participant: alice, kind: kind, expiresAt: EpochMillis(9_000))
        let simulator = makeSimulator(capacities: [session: 2], holdings: [holding])
        simulator.controls.revokeQualification(participant: alice, kind: kind)
        simulator.controls.enqueueCreate(.holdResponse)
        _ = await simulator.reservation.lookUpReservation(request: RequestId("x"))
        simulator.controls.reset()
        #expect(simulator.controls.observations == SimulatorObservations())
        #expect(await simulator.eligibility.fetchQualification(participant: alice, kind: kind) == .held(expiresAt: EpochMillis(9_000)))
    }

    @Test func 受付サービスは同じ予約と参加者への別の試行を受付済みの別の観測として返し記録を増やさない() async {
        let simulator = makeSimulator(capacities: [session: 2])
        let reservation = ReservationId("r-1")
        #expect(await simulator.checkIn.createCheckIn(attempt: CheckInAttemptId("a"), reservation: reservation, participant: alice) == .recorded)
        #expect(await simulator.checkIn.createCheckIn(attempt: CheckInAttemptId("b"), reservation: reservation, participant: alice) == .alreadyRecorded)
        #expect(await simulator.checkIn.createCheckIn(attempt: CheckInAttemptId("a"), reservation: reservation, participant: alice) == .recorded)
        let observed = simulator.controls.observations
        #expect(observed.checkInRecords == [CheckInRecordCount(reservation: reservation, participant: alice, records: 1)])
        #expect(observed.checkInRepeatedForRecordedTarget == 1)
    }
}
