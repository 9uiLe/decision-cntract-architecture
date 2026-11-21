import CheckInCore
import Kernel
import KernelTestSupport
import ReservationCore
import Testing

private let opensAt = start - 30 * minute
private let closesAt = start + 15 * minute

@Suite(.timeLimit(.minutes(1)))
struct CheckInCoreTests {
    @Test func 二人の予約でAだけ受け付けるとAだけ受付済みでBは未受付のまま() async {
        let w = World()
        let record = await w.reserve([alice, bob])
        w.clock.set(EpochMillis(opensAt))
        #expect(w.checkIn.checkIn(w.admission(record, alice)) == .accepted)
        await w.settled(record, alice, where: isCheckedIn)
        #expect(w.status(record, bob) == nil)
        #expect(w.checkIns.createCalls.count == 1)
    }

    @Test func 連打は最初の一回だけが受付作成要求になり受付済みの再操作も要求しない() async {
        let w = World()
        let record = await w.reserve([alice])
        let response = Deferred<CheckInCreateObservation>()
        w.checkIns.onCreate { _ in await response.wait() ?? .noResponse }
        w.clock.set(EpochMillis(opensAt))
        let admission = w.admission(record, alice)
        #expect(w.checkIn.checkIn(admission) == .accepted)
        #expect(w.checkIn.checkIn(admission) == .rejected(.busy))
        await w.checkIns.createCalls.waitUntil(count: 1)
        response.complete(.recorded)
        await w.settled(record, alice, where: isCheckedIn)
        #expect(w.checkIn.checkIn(admission) == .rejected(.alreadyCheckedIn))
        #expect(w.checkIns.createCalls.count == 1)
    }

    @Test func 予約の確認の応答待ちの間の再操作は予約確認を重ねない() async {
        let w = World()
        let record = await w.reserve([alice])
        let confirmation = Deferred<AdmissionLookup?>()
        w.source.setHook { _, _ in await confirmation.wait() ?? nil }
        w.clock.set(EpochMillis(opensAt))
        let admission = w.admission(record, alice)
        #expect(w.checkIn.checkIn(admission) == .accepted)
        #expect(w.checkIn.checkIn(admission) == .rejected(.busy))
        await w.source.calls.waitUntil(count: 1)
        guard case .processing(.confirmingReservation)? = w.status(record, alice) else {
            Issue.record("予約を確認している状態のはず")
            return
        }
        confirmation.complete(nil)
        await w.settled(record, alice, where: isCheckedIn)
        #expect(w.source.calls.count == 1)
    }

    @Test func 受付開始ちょうどは受け付けその直前は要求も予約の照会も行わない() async {
        let w = World()
        let record = await w.reserve([alice])
        let admission = w.admission(record, alice)
        w.clock.set(EpochMillis(opensAt - 1))
        #expect(w.checkIn.checkIn(admission) == .rejected(.notYetOpen))
        #expect(w.checkIns.createCalls.count == 0)
        #expect(w.source.calls.count == 0)
        w.clock.set(EpochMillis(opensAt))
        #expect(w.checkIn.checkIn(admission) == .accepted)
        await w.settled(record, alice, where: isCheckedIn)
    }

    @Test func 受付終了の直前は受け付け受付終了ちょうどは要求を送らない() async {
        let w = World()
        let record = await w.reserve([alice, bob])
        w.clock.set(EpochMillis(closesAt - 1))
        #expect(w.checkIn.checkIn(w.admission(record, alice)) == .accepted)
        await w.settled(record, alice, where: isCheckedIn)
        w.clock.set(EpochMillis(closesAt))
        #expect(w.checkIn.checkIn(w.admission(record, bob)) == .rejected(.closed))
        #expect(w.checkIns.createCalls.count == 1)
    }

    @Test func 予約の確認から戻った時点で受付時間を取り直し過ぎていれば要求を送らない() async {
        let w = World()
        let record = await w.reserve([alice])
        let confirmation = Deferred<AdmissionLookup?>()
        w.source.setHook { _, _ in await confirmation.wait() ?? nil }
        w.clock.set(EpochMillis(closesAt - 1))
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.source.calls.waitUntil(count: 1)
        w.clock.set(EpochMillis(closesAt))
        confirmation.complete(nil)
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.closed)? = w.status(record, alice) else { Issue.record("受付終了のはず"); return }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 応答だけを失った受付は結果不明で元の試行を照会し新しい受付は作らない() async {
        let w = World()
        let record = await w.reserve([alice])
        w.checkIns.onCreate { _ in .noResponse }
        w.clock.set(EpochMillis(opensAt))
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isUnknown)
        guard case .unknown(let ticket)? = w.status(record, alice) else { Issue.record("結果不明のはず"); return }
        #expect(w.checkIn.checkIn(w.admission(record, alice)) == .rejected(.unresolved))
        #expect(w.checkIn.checkStatus(ticket) == .accepted)
        await w.settled(record, alice, where: isCheckedIn)
        #expect(w.checkIns.createCalls.count == 1)
        #expect(w.checkIns.lookUpCalls.calls == w.checkIns.createCalls.calls)
    }

    @Test func 照会しても見つからない場合は結果不明のままで受付時間の終了後も照会できる() async {
        let w = World()
        let record = await w.reserve([alice])
        w.checkIns.onCreate { _ in .noResponse }
        w.checkIns.onLookUp { _ in .notFound }
        w.clock.set(EpochMillis(opensAt))
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isUnknown)
        w.clock.set(EpochMillis(closesAt + hour))
        guard case .unknown(let first)? = w.status(record, alice) else { Issue.record("結果不明のはず"); return }
        _ = w.checkIn.checkStatus(first)
        await w.settled(record, alice) { status in
            guard case .unknown(let renewed)? = status else { return false }
            return renewed !== first
        }
        guard case .unknown(let second)? = w.status(record, alice) else { Issue.record("結果不明のままのはず"); return }
        #expect(w.checkIn.checkStatus(first) == .rejected(.stale))
        w.checkIns.onLookUp { _ in .failed }
        _ = w.checkIn.checkStatus(second)
        await w.settled(record, alice) { status in
            guard case .unknown(let renewed)? = status else { return false }
            return renewed !== second
        }
        #expect(w.checkIns.createCalls.count == 1)
    }

    @Test func 未成立が確定した後の再試行は明示操作で新しい試行の識別子を使い古い券では再度行えない() async {
        let w = World()
        let record = await w.reserve([alice])
        w.checkIns.onCreate { _ in .notCreated }
        w.clock.set(EpochMillis(opensAt))
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isNotCreated)
        guard case .notCreated(let retry)? = w.status(record, alice) else { Issue.record("未成立のはず"); return }
        w.checkIns.onCreate { _ in .recorded }
        #expect(w.checkIn.retry(retry) == .accepted)
        await w.settled(record, alice, where: isCheckedIn)
        #expect(w.checkIns.createCalls.calls == [CheckInAttemptId("a-1"), CheckInAttemptId("a-2")])
        #expect(w.checkIn.retry(retry) == .rejected(.stale))
    }

    @Test func 時間切れの受付の応答は結果不明になり後から届いた応答は適用しない() async {
        let w = World()
        let record = await w.reserve([alice])
        let late = Deferred<CheckInCreateObservation>()
        w.checkIns.onCreate { _ in await late.wait() ?? .noResponse }
        w.clock.set(EpochMillis(opensAt))
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.checkIns.createCalls.waitUntil(count: 1)
        w.timeout.expire()
        await w.settled(record, alice, where: isUnknown)
        late.complete(.recorded)
        #expect(isUnknown(w.status(record, alice)))
    }
}

@Suite(.timeLimit(.minutes(1)))
struct CheckInAdmissionTests {
    @Test func T_72時間の予約情報を取消後に使っても現在の予約を確認し直して受付作成要求を送らない() async {
        let w = World()
        let record = await w.reserve([alice])
        let staleAdmission = w.admission(record, alice)
        w.clock.set(EpochMillis(start - 48 * hour))
        #expect(w.reservation.cancel(record.cancel) == .accepted)
        _ = await firstValue(of: w.reservation.snapshots()) { snapshot in
            if case .cancelled? = snapshot.reservations.first { return true }
            return false
        }
        w.clock.set(EpochMillis(start - 20 * minute))
        _ = w.checkIn.checkIn(staleAdmission)
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.reservationUnavailable(.cancelled))? = w.status(record, alice) else {
            Issue.record("取消済みの予約として拒否するはず")
            return
        }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 取消の応答が失われた予約はサービスの現在の状態で取消済みと判断し確認できなければ別の拒否になる() async {
        let w = World()
        let record = await w.reserve([alice])
        w.clock.set(EpochMillis(opensAt))
        w.reservations.setUsage(.cancelled)
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.reservationUnavailable(.cancelled))? = w.status(record, alice) else {
            Issue.record("取消済みのはず")
            return
        }
        w.reservations.setUsage(.failed)
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice) { status in
            guard case .notAdmitted(.reservationUnconfirmed)? = status else { return false }
            return true
        }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 予約の確認が別の参加者の前提を返しても対象が一致しなければ受付を要求しない() async {
        let w = World()
        let record = await w.reserve([alice, bob])
        w.clock.set(EpochMillis(opensAt))
        let other = await w.reservation.admission(reservation: record.reservation, participant: bob)
        w.source.setHook { _, participant in participant == alice ? other : nil }
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.wrongTarget)? = w.status(record, alice) else { Issue.record("対象の不一致のはず"); return }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 予約に含まれない参加者の組では受付作成要求を送らない() async {
        let w = World()
        let record = await w.reserve([alice])
        w.clock.set(EpochMillis(opensAt))
        let outsider = ReservationId("r-1")
        w.source.setHook { _, _ in await w.reservation.admission(reservation: outsider, participant: carol) }
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.reservationUnavailable(.notIncluded))? = w.status(record, alice) else {
            Issue.record("含まれない参加者のはず")
            return
        }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 過去に得た現時の確認を再生しても今回の照会の区間に入らないので受付作成要求を送らない() async {
        let w = World()
        let record = await w.reserve([alice])
        w.clock.set(EpochMillis(start - 60 * minute))
        let past = await w.reservation.admission(reservation: record.reservation, participant: alice)
        w.clock.set(EpochMillis(opensAt))
        w.source.setHook { _, _ in past }
        _ = w.checkIn.checkIn(w.admission(record, alice))
        await w.settled(record, alice, where: isNotAdmitted)
        guard case .notAdmitted(.reservationUnconfirmed)? = w.status(record, alice) else {
            Issue.record("今回の確認として使わないはず")
            return
        }
        #expect(w.checkIns.createCalls.count == 0)
    }

    @Test func 受付の時間の判断は受付の操作と同じ規則を公開する() async {
        let w = World()
        let record = await w.reserve([alice])
        let admission = w.admission(record, alice)
        w.clock.set(EpochMillis(opensAt - 1))
        guard case .notYetOpen = w.checkIn.window(for: admission) else { Issue.record("開始前のはず"); return }
        w.clock.set(EpochMillis(opensAt))
        guard case .open = w.checkIn.window(for: admission) else { Issue.record("開始ちょうどは受付中のはず"); return }
        w.clock.set(EpochMillis(closesAt))
        guard case .closed = w.checkIn.window(for: admission) else { Issue.record("終了ちょうどは終了のはず"); return }
    }
}
