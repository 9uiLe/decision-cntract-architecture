import CheckInCore
import EligibilityCore
import Kernel
import KernelTestSupport
import os
import ReservationCore
import Testing

@testable import SimulatorAdapter

private let minute: Int64 = 60_000
private let hour: Int64 = 60 * minute
private let origin: Int64 = 1_000 * hour
private let advancedKind = QualificationKind(id: "pottery-advanced", name: "上級ろくろ修了")
private let alice = ParticipantId("alice")
private let bob = ParticipantId("bob")
private let open = Workshop(
    id: SessionId("open"), name: "通常回", start: EpochMillis(origin + 72 * hour), seatsHint: 4, requirement: .notRequired
)
private let advanced = Workshop(
    id: SessionId("advanced"), name: "上級回", start: EpochMillis(origin + 96 * hour), seatsHint: 4,
    requirement: .required(advancedKind)
)
private let catalog = Catalog(
    workshops: [open, advanced],
    participants: [Participant(id: alice, name: "A"), Participant(id: bob, name: "B")]
)

private struct World {
    let simulator: Simulator
    let clock = ManualClock(EpochMillis(origin))
    let timeout = ManualTimeout()
    let eligibility: EligibilityCore
    let reservation: ReservationCore
    let checkIn: CheckInCore

    var controls: any SimulatorControls { simulator.controls }
    var observed: SimulatorObservations { simulator.controls.observations }

    init(holdings: [QualificationHolding] = []) {
        let simulator = makeSimulator(
            capacities: [open.id: 4, advanced.id: 4],
            holdings: holdings
        )
        let requests = OSAllocatedUnfairLock(initialState: 0)
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        let clock = clock
        let timeout = timeout
        let eligibility = makeEligibilityCore(port: simulator.eligibility, clock: clock, timeout: timeout)
        let reservation = makeReservationCore(
            catalog: catalog, port: simulator.reservation, eligibility: eligibility, clock: clock,
            newRequestId: { RequestId("q-\(requests.withLock { value -> Int in value += 1; return value })") },
            timeout: timeout
        )
        self.simulator = simulator
        self.eligibility = eligibility
        self.reservation = reservation
        checkIn = makeCheckInCore(
            source: reservation, port: simulator.checkIn, clock: clock,
            newAttemptId: { CheckInAttemptId("a-\(attempts.withLock { value -> Int in value += 1; return value })") },
            timeout: timeout
        )
    }

    func reserveOpen(_ who: [ParticipantId]) async -> ReservedRecord {
        _ = reservation.selectWorkshop(open.id)
        for participant in who { _ = reservation.toggleParticipant(participant) }
        guard case .confirmed(let draft) = reservation.confirmWithoutQualification() else { fatalError("確認できるはず") }
        _ = reservation.submit(draft)
        return await reserved()
    }

    func reserved() async -> ReservedRecord {
        _ = await firstValue(of: reservation.snapshots()) { $0.request == nil && !$0.reservations.isEmpty }
        guard case .reserved(let record)? = reservation.snapshot.reservations.last else { fatalError("成立するはず") }
        return record
    }

    func admission(_ record: ReservedRecord, _ participant: ParticipantId) -> ReservationAdmission {
        guard let admission = record.admissions.first(where: { $0.participant == participant }) else { fatalError("含まれるはず") }
        return admission
    }

    func qualifiedConfirmation(_ who: [ParticipantId]) async -> ConfirmOutcome {
        _ = reservation.selectWorkshop(advanced.id)
        for participant in who { _ = reservation.toggleParticipant(participant) }
        _ = reservation.requestEligibility()
        _ = await firstValue(of: eligibility.snapshots()) { snapshot in
            who.allSatisfy { participant in
                guard case .settled? = snapshot.statuses[EligibilityKey(participant: participant, kind: advancedKind)] else {
                    return false
                }
                return true
            }
        }
        return reservation.confirmQualified()
    }

    func settledCheckIn(_ record: ReservedRecord, _ participant: ParticipantId, where predicate: @escaping @Sendable (ParticipantCheckIn?) -> Bool) async {
        let key = CheckInKey(reservation: record.reservation, participant: participant)
        _ = await firstValue(of: checkIn.snapshots()) { predicate($0.entries[key]) }
    }

    func status(_ record: ReservedRecord, _ participant: ParticipantId) -> ParticipantCheckIn? {
        checkIn.snapshot.entries[CheckInKey(reservation: record.reservation, participant: participant)]
    }
}

private func holdings(_ who: [ParticipantId], until expiresAt: Int64) -> [QualificationHolding] {
    who.map { QualificationHolding(participant: $0, kind: advancedKind, expiresAt: EpochMillis(expiresAt)) }
}

private func isCheckedIn(_ status: ParticipantCheckIn?) -> Bool {
    if case .checkedIn? = status { return true }
    return false
}

private func isUnknown(_ status: ParticipantCheckIn?) -> Bool {
    if case .unknown? = status { return true }
    return false
}

private func isNotAdmitted(_ status: ParticipantCheckIn?) -> Bool {
    if case .notAdmitted? = status { return true }
    return false
}

@Suite(.timeLimit(.minutes(1)))
struct ReservationAcceptanceTests {
    @Test func MC_01_通常回は資格を確認せず既存の条件で予約でき資格サービスを呼ばない() async {
        let w = World()
        _ = await w.reserveOpen([alice])
        #expect(w.observed.qualificationRequests == 0)
        #expect(w.observed.createRequests == 1)
    }

    @Test func MC_02_要資格回で全員の資格が有効なら予約でき作成要求は一度() async {
        let w = World(holdings: holdings([alice, bob], until: origin + 500 * hour))
        guard case .confirmed(let draft) = await w.qualifiedConfirmation([alice, bob]) else { Issue.record("確認済みのはず"); return }
        _ = w.reservation.submit(draft)
        _ = await w.reserved()
        #expect(w.observed.createRequests == 1)
        #expect(w.observed.activeReservations == 1)
    }

    @Test func MC_03_一人でも未取得や期限切れなら予約作成要求を送らない() async {
        let notHeld = World(holdings: holdings([alice], until: origin + 500 * hour))
        guard case .eligibilityNotMet(let missing) = await notHeld.qualifiedConfirmation([alice, bob]) else {
            Issue.record("未取得なら確認済みにならないはず")
            return
        }
        #expect(missing == [bob])
        let expired = World(holdings: holdings([alice, bob], until: origin + 1))
        expired.clock.set(EpochMillis(origin + 1))
        guard case .eligibilityNotMet = await expired.qualifiedConfirmation([alice, bob]) else {
            Issue.record("期限切れなら確認済みにならないはず")
            return
        }
        #expect(notHeld.observed.createRequests == 0)
        #expect(expired.observed.createRequests == 0)
    }

    @Test func MC_04_資格の確認結果が不明なら有効と推測せず予約作成要求を送らない() async {
        let w = World(holdings: holdings([alice], until: origin + 500 * hour))
        w.controls.enqueueQualification(.unrecognized)
        guard case .eligibilityNotMet = await w.qualifiedConfirmation([alice]) else { Issue.record("確認済みにならないはず"); return }
        #expect(w.observed.createRequests == 0)
    }

    @Test func MC_06_資格確認後に参加者を変更すると現在の条件の確認なしには予約できない() async {
        let w = World(holdings: holdings([alice, bob], until: origin + 500 * hour))
        guard case .confirmed(let draft) = await w.qualifiedConfirmation([alice]) else { Issue.record("確認済みのはず"); return }
        _ = w.reservation.toggleParticipant(bob)
        #expect(w.reservation.submit(draft) == .rejected(.stale))
        #expect(w.observed.createRequests == 0)
    }

    @Test func MC_07_確認後で送信前に有効期限を迎えると再確認で無効を判定して作成要求を送らない() async {
        let w = World(holdings: holdings([alice], until: origin + 10 * hour))
        guard case .confirmed(let draft) = await w.qualifiedConfirmation([alice]) else { Issue.record("確認済みのはず"); return }
        w.clock.set(EpochMillis(origin + 10 * hour))
        _ = w.reservation.submit(draft)
        _ = await firstValue(of: w.reservation.snapshots()) { snapshot in
            if case .eligibilityLost(_, .expired)? = snapshot.request { return true }
            return false
        }
        #expect(w.observed.createRequests == 0)
    }

    @Test func 応答喪失を元の要求IDで照会して成立を確認する() async {
        let w = World()
        w.controls.enqueueCreate(.loseResponseAfterCreate)
        _ = w.reservation.selectWorkshop(open.id)
        _ = w.reservation.toggleParticipant(alice)
        guard case .confirmed(let draft) = w.reservation.confirmWithoutQualification() else { Issue.record("確認済みのはず"); return }
        _ = w.reservation.submit(draft)
        _ = await firstValue(of: w.reservation.snapshots()) { if case .unknown? = $0.request { return true } else { return false } }
        guard case .unknown(let ticket)? = w.reservation.snapshot.request else { Issue.record("結果不明のはず"); return }
        _ = w.reservation.checkStatus(ticket)
        _ = await w.reserved()
        #expect(w.observed.createRequests == 1)
        #expect(w.observed.lookUpRequests == 1)
        #expect(w.observed.activeReservations == 1)
    }
}

@Suite(.timeLimit(.minutes(1)))
struct CheckInAcceptanceTests {
    private func openForCheckIn(_ w: World) {
        w.clock.set(EpochMillis(open.start.value - 30 * minute))
    }

    @Test func MC_09_二人の予約でAだけ受け付けるとAだけ受付済みでBは未受付のまま記録は一件() async {
        let w = World()
        let record = await w.reserveOpen([alice, bob])
        openForCheckIn(w)
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settledCheckIn(record, alice, where: isCheckedIn)
        #expect(w.status(record, bob) == nil)
        #expect(w.observed.checkInRecords == [CheckInRecordCount(reservation: record.reservation, participant: alice, records: 1)])
    }

    @Test func MC_10_同じ参加者の連打は受付作成要求も受付記録も重複しない() async {
        let w = World()
        let record = await w.reserveOpen([alice])
        openForCheckIn(w)
        w.controls.enqueueCheckInCreate(.holdResponse)
        let admission = w.admission(record, alice)
        #expect(w.checkIn.checkIn(admission) == .accepted)
        #expect(w.checkIn.checkIn(admission) == .rejected(.busy))
        _ = await firstValue(of: w.controls.observationUpdates()) { $0.heldCalls == 1 }
        w.controls.releaseHeld()
        await w.settledCheckIn(record, alice, where: isCheckedIn)
        #expect(w.checkIn.checkIn(admission) == .rejected(.alreadyCheckedIn))
        #expect(w.observed.checkInCreateRequests == 1)
        #expect(w.observed.checkInRecords.map(\.records) == [1])
    }

    @Test func MC_12_MC_13_受付開始ちょうどは受け付け受付終了ちょうどは受付作成要求を送らない() async {
        let w = World()
        let record = await w.reserveOpen([alice, bob])
        let startTime = open.start.value
        w.clock.set(EpochMillis(startTime - 30 * minute - 1))
        #expect(w.checkIn.checkIn(w.admission(record, alice)) == .rejected(.notYetOpen))
        w.clock.set(EpochMillis(startTime - 30 * minute))
        #expect(w.checkIn.checkIn(w.admission(record, alice)) == .accepted)
        await w.settledCheckIn(record, alice, where: isCheckedIn)
        w.clock.set(EpochMillis(startTime + 15 * minute))
        #expect(w.checkIn.checkIn(w.admission(record, bob)) == .rejected(.closed))
        #expect(w.observed.checkInCreateRequests == 1)
    }

    @Test func MC_14_受付が成立して応答だけを失うと元の受付要求を照会し記録は一件作成要求は一回のまま() async {
        let w = World()
        let record = await w.reserveOpen([alice, bob])
        openForCheckIn(w)
        w.controls.enqueueCheckInCreate(.loseResponseAfterRecord)
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settledCheckIn(record, alice, where: isUnknown)
        #expect(w.status(record, bob) == nil)
        guard case .unknown(let ticket)? = w.status(record, alice) else { Issue.record("結果不明のはず"); return }
        _ = w.checkIn.checkStatus(ticket)
        await w.settledCheckIn(record, alice, where: isCheckedIn)
        #expect(w.observed.checkInCreateRequests == 1)
        #expect(w.observed.checkInLookUpRequests == 1)
        #expect(w.observed.checkInRecords.map(\.records) == [1])
    }

    @Test func MC_15_予約自体が結果不明なら有効な予約として受付を成立させない() async {
        let w = World()
        w.controls.enqueueCreate(.loseResponseAfterCreate)
        _ = w.reservation.selectWorkshop(open.id)
        _ = w.reservation.toggleParticipant(alice)
        guard case .confirmed(let draft) = w.reservation.confirmWithoutQualification() else { Issue.record("確認済みのはず"); return }
        _ = w.reservation.submit(draft)
        _ = await firstValue(of: w.reservation.snapshots()) { if case .unknown? = $0.request { return true } else { return false } }
        #expect(w.reservation.snapshot.reservations.isEmpty)
        #expect(w.observed.activeReservations == 1)
        #expect(w.observed.checkInCreateRequests == 0)
    }

    @Test func MC_17_予約後に資格だけが失効しても予約を維持して二人とも時間内に受付でき来場時に資格を再確認しない() async {
        let w = World(holdings: holdings([alice, bob], until: advanced.start.value - 20 * minute))
        guard case .confirmed(let draft) = await w.qualifiedConfirmation([alice, bob]) else { Issue.record("確認済みのはず"); return }
        _ = w.reservation.submit(draft)
        let record = await w.reserved()
        let before = w.observed.qualificationRequests
        w.clock.set(EpochMillis(advanced.start.value - 10 * minute))
        for participant in [alice, bob] {
            _ = w.checkIn.checkIn(w.admission(record, participant))
            await w.settledCheckIn(record, participant, where: isCheckedIn)
        }
        #expect(w.observed.qualificationRequests == before)
        #expect(w.observed.checkInRecords.map(\.records) == [1, 1])
    }

    @Test func 九の一_T_72時間に成立した予約を取り消した後の古い情報での受付は要求も記録も増えない() async {
        let w = World()
        let record = await w.reserveOpen([alice])
        let stale = w.admission(record, alice)
        w.clock.set(EpochMillis(open.start.value - 48 * hour))
        _ = w.reservation.cancel(record.cancel)
        _ = await firstValue(of: w.reservation.snapshots()) { if case .cancelled? = $0.reservations.first { return true } else { return false } }
        w.clock.set(EpochMillis(open.start.value - 20 * minute))
        _ = w.checkIn.checkIn(stale)
        await w.settledCheckIn(record, alice, where: isNotAdmitted)
        #expect(w.observed.checkInCreateRequests == 0)
        #expect(w.observed.checkInRecords.isEmpty)
    }

    @Test func 取消が成立して応答だけを失った予約でもサービスの現在の状態で取消済みと判断し受付作成要求を送らない() async {
        let w = World()
        let record = await w.reserveOpen([alice])
        w.clock.set(EpochMillis(open.start.value - 48 * hour))
        w.controls.enqueueCancel(.lose)
        _ = w.reservation.cancel(record.cancel)
        _ = await firstValue(of: w.reservation.snapshots()) { snapshot in
            guard case .reserved(let reserved)? = snapshot.reservations.first else { return false }
            return reserved.lastCancelFailure != nil
        }
        openForCheckIn(w)
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settledCheckIn(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.reservationUnavailable(.cancelled))? = w.status(record, alice) else {
            Issue.record("サービスの現在の状態で取消済みと判断するはず")
            return
        }
        #expect(w.observed.checkInCreateRequests == 0)
    }

    @Test func 照会の応答を保留している間に取消が成立しても有効な確認を発行せず受付を要求しない() async {
        let w = World()
        let record = await w.reserveOpen([alice])
        w.clock.set(EpochMillis(open.start.value - 48 * hour))
        w.controls.enqueueUsage(.hold)
        let admission = w.admission(record, alice)
        w.clock.set(EpochMillis(open.start.value - 30 * minute))
        _ = w.checkIn.checkIn(admission)
        _ = await firstValue(of: w.controls.observationUpdates()) { $0.heldCalls == 1 }
        w.clock.set(EpochMillis(open.start.value - 40 * hour))
        _ = w.reservation.cancel(record.cancel)
        _ = await firstValue(of: w.reservation.snapshots()) { if case .cancelled? = $0.reservations.first { return true } else { return false } }
        w.clock.set(EpochMillis(open.start.value - 20 * minute))
        w.controls.releaseHeld()
        await w.settledCheckIn(record, alice, where: isNotAdmitted)
        #expect(w.observed.checkInCreateRequests == 0)
    }

    @Test func 受付サービスは予約の有効性や受付時間を判断しない() async {
        let simulator = makeSimulator(capacities: [:])
        let result = await simulator.checkIn.createCheckIn(
            attempt: CheckInAttemptId("x"), reservation: ReservationId("none"), participant: alice
        )
        #expect(result == .recorded)
    }
}
