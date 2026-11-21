import EligibilityCore
import Kernel
import KernelTestSupport
import ReservationCore
import Testing

@Suite(.timeLimit(.minutes(1)))
struct ReservationCoreTests {
    @Test func 開始時刻ちょうどは作成要求を送らず直前は送る() async {
        let atStart = Fixture(now: start)
        #expect(rejection(atStart.core.submit(atStart.confirmed())) == .registrationClosed)
        guard case .registrationClosed(let booking)? = atStart.snapshot.request else {
            Issue.record("受付終了の結果が残るはず")
            return
        }
        #expect(booking.workshop.id == pottery.id)
        #expect(atStart.port.createCalls.count == 0)

        let before = Fixture(now: start - 1)
        #expect(before.core.submit(before.confirmed()) == .accepted)
        await before.port.createCalls.waitUntil(count: 1)
        #expect(before.port.createCalls.count == 1)
    }

    @Test func 確認を変更すると古い確認では送れない() async {
        let f = Fixture()
        let old = f.confirmed()
        _ = f.core.toggleParticipant(bob)
        #expect(rejection(f.core.submit(old)) == .stale)
        guard case .confirmed(let latest) = f.core.confirmWithoutQualification() else { Issue.record("確認できるはず"); return }
        #expect(f.core.submit(latest) == .accepted)
        await f.settled { $0.request == nil && $0.reservations.count == 1 }
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 参加者が空では確認できず確認だけでは要求を送らない() {
        let f = Fixture()
        _ = f.core.selectWorkshop(pottery.id)
        guard case .emptyParticipants = f.core.confirmWithoutQualification() else { Issue.record("空では確認できないはず"); return }
        _ = f.core.toggleParticipant(alice)
        guard case .confirmed = f.core.confirmWithoutQualification() else { Issue.record("確認できるはず"); return }
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 送信の連打は作成要求を一度だけ送り二回目はBusyで拒否する() async {
        let f = Fixture()
        let response = Deferred<CreateObservation>()
        f.port.onCreate { _ in await response.wait() ?? .noResponse }
        let draft = f.confirmed()
        #expect(f.core.submit(draft) == .accepted)
        #expect(rejection(f.core.submit(draft)) == .busy)
        await f.port.createCalls.waitUntil(count: 1)
        response.complete(.created(ReservationId("r-1")))
        await f.settled { $0.reservations.count == 1 }
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 応答喪失は結果不明を保ち開始時刻を過ぎても元のRequestIdで照会する() async {
        let f = Fixture()
        f.port.onCreate { _ in .noResponse }
        f.port.onLookUp { _ in .found(ReservationId("r-1")) }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        guard case .unknown(let ticket)? = f.snapshot.request else { Issue.record("結果不明のはず"); return }
        #expect(ticket.request == RequestId("req-1"))
        f.clock.set(EpochMillis(start + hour))
        #expect(f.core.checkStatus(ticket) == .accepted)
        await f.settled { $0.request == nil && $0.reservations.count == 1 }
        #expect(f.port.lookUpCalls.calls == [RequestId("req-1")])
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 結果不明の間は新しい送信を受け付けず別の開催回の下書きは作れる() async {
        let f = Fixture()
        f.port.onCreate { _ in .noResponse }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        let another = f.confirmed(cooking)
        #expect(rejection(f.core.submit(another)) == .busy)
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 未成立確定後の再試行は新しいRequestIdを使う() async {
        let f = Fixture()
        f.port.onCreate { _ in .notCreated }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .notCreated? = $0.request { return true } else { return false } }
        guard case .notCreated(let retry, _)? = f.snapshot.request else { Issue.record("未成立のはず"); return }
        f.port.onCreate { _ in .created(ReservationId("r-2")) }
        #expect(f.core.retry(retry) == .accepted)
        await f.settled { $0.reservations.count == 1 }
        #expect(f.port.createCalls.calls == [RequestId("req-1"), RequestId("req-2")])
        #expect(rejection(f.core.retry(retry)) == .stale)
    }

    @Test func 開始時刻以降の再試行は拒否し未成立の事実を保持する() async {
        let f = Fixture()
        f.port.onCreate { _ in .notCreated }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .notCreated? = $0.request { return true } else { return false } }
        guard case .notCreated(let retry, _)? = f.snapshot.request else { Issue.record("未成立のはず"); return }
        f.clock.set(EpochMillis(start))
        #expect(rejection(f.core.retry(retry)) == .registrationClosed)
        guard case .notCreated? = f.snapshot.request else { Issue.record("未成立が残るはず"); return }
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 照会で未成立が確定すると再試行できる() async {
        let f = Fixture()
        f.port.onCreate { _ in .noResponse }
        f.port.onLookUp { _ in .notCreated }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        guard case .unknown(let ticket)? = f.snapshot.request else { Issue.record("結果不明のはず"); return }
        _ = f.core.checkStatus(ticket)
        await f.settled { if case .notCreated? = $0.request { return true } else { return false } }
        guard case .notCreated(let retry, _)? = f.snapshot.request else { Issue.record("未成立のはず"); return }
        #expect(f.core.retry(retry) == .accepted)
    }

    @Test func 時間切れの応答は結果不明になり後から届いた作成の応答は適用しない() async {
        let f = Fixture()
        let late = Deferred<CreateObservation>()
        f.port.onCreate { _ in await late.wait() ?? .noResponse }
        _ = f.core.submit(f.confirmed())
        await f.port.createCalls.waitUntil(count: 1)
        f.timeout.expire()
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        late.complete(.created(ReservationId("late")))
        #expect(f.snapshot.reservations.isEmpty)
    }

    @Test func 古い照会が時間切れの後に届いても確定した予約を結果不明へ戻さない() async {
        let f = Fixture()
        f.port.onCreate { _ in .noResponse }
        let firstLookUp = Deferred<LookupObservation>()
        f.port.onLookUp { _ in await firstLookUp.wait() ?? .failed }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        guard case .unknown(let first)? = f.snapshot.request else { Issue.record("結果不明のはず"); return }
        _ = f.core.checkStatus(first)
        await f.port.lookUpCalls.waitUntil(count: 1)
        f.timeout.expire()
        await f.settled { if case .unknown(let renewed)? = $0.request { return renewed !== first } else { return false } }
        guard case .unknown(let second)? = f.snapshot.request else { Issue.record("再度結果不明のはず"); return }
        f.port.onLookUp { _ in .found(ReservationId("r-1")) }
        _ = f.core.checkStatus(second)
        await f.settled { $0.reservations.count == 1 }
        firstLookUp.complete(.notCreated)
        #expect(f.snapshot.reservations.count == 1)
        #expect(f.snapshot.request == nil)
    }

    @Test func 満席は予約を作らず開催回や参加者を選び直すと結果を消す() async {
        let f = Fixture()
        f.port.onCreate { _ in .full }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .soldOut? = $0.request { return true } else { return false } }
        #expect(f.snapshot.reservations.isEmpty)
        _ = f.core.toggleParticipant(bob)
        #expect(f.snapshot.request == nil)
    }

    @Test func 受付終了で拒否された送信は結果を残し別の開催回を選ぶと消える() {
        let f = Fixture(now: start)
        _ = f.core.submit(f.confirmed())
        guard case .registrationClosed? = f.snapshot.request else { Issue.record("結果が残るはず"); return }
        _ = f.core.selectWorkshop(cooking.id)
        #expect(f.snapshot.request == nil)
    }

    @Test func 取消期限ちょうどは取消要求を送らず予約を維持し直前は送る() async {
        let f = Fixture()
        let record = await f.reserve()
        f.clock.set(EpochMillis(start - 24 * hour))
        #expect(rejection(f.core.cancel(record.cancel)) == .cancellationClosed)
        #expect(f.port.cancelCalls.count == 0)
        f.clock.set(EpochMillis(start - 24 * hour - 1))
        #expect(f.core.cancel(record.cancel) == .accepted)
        await f.settled { if case .cancelled? = $0.reservations.first { return true } else { return false } }
        #expect(f.port.cancelCalls.count == 1)
    }

    @Test func 取消の保留中に連打しても取消要求は一度で期限を越えても送信済みの結果を採用する() async {
        let f = Fixture()
        let record = await f.reserve()
        let response = Deferred<CancelObservation>()
        f.port.onCancel { _ in await response.wait() ?? .noResponse }
        f.clock.set(EpochMillis(start - 25 * hour))
        #expect(f.core.cancel(record.cancel) == .accepted)
        #expect(rejection(f.core.cancel(record.cancel)) == .busy)
        await f.port.cancelCalls.waitUntil(count: 1)
        f.clock.set(EpochMillis(start))
        response.complete(.cancelled)
        await f.settled { if case .cancelled? = $0.reservations.first { return true } else { return false } }
        #expect(f.port.cancelCalls.count == 1)
    }

    @Test func 取消が拒否や喪失されても予約の事実を保ち新しい券で再度取り消せる() async {
        let f = Fixture(now: cancellable)
        let record = await f.reserve()
        f.port.onCancel { _ in .declined }
        _ = f.core.cancel(record.cancel)
        await f.settled { snapshot in
            guard case .reserved(let reserved)? = snapshot.reservations.first else { return false }
            return reserved.lastCancelFailure != nil
        }
        guard case .reserved(let declined)? = f.snapshot.reservations.first else { Issue.record("予約は維持されるはず"); return }
        #expect(declined.cancel !== record.cancel)
        #expect(rejection(f.core.cancel(record.cancel)) == .stale)
        f.port.onCancel { _ in .cancelled }
        #expect(f.core.cancel(declined.cancel) == .accepted)
        await f.settled { if case .cancelled? = $0.reservations.first { return true } else { return false } }
    }

    @Test func 外部要求の実行中は取消を受け付けない() async {
        let f = Fixture()
        let record = await f.reserve()
        let response = Deferred<CreateObservation>()
        f.port.onCreate { _ in await response.wait() ?? .noResponse }
        _ = f.core.submit(f.confirmed(cooking))
        #expect(rejection(f.core.cancel(record.cancel)) == .busy)
        await f.port.createCalls.waitUntil(count: 2)
        response.complete(.full)
        await f.settled { if case .soldOut? = $0.request { return true } else { return false } }
    }

    @Test func 結果不明を保持したまま別の確定予約を取り消し決着後に結果不明へ戻る() async {
        let f = Fixture(now: cancellable)
        let record = await f.reserve()
        f.port.onCreate { _ in .noResponse }
        _ = f.core.submit(f.confirmed(cooking))
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        let response = Deferred<CancelObservation>()
        f.port.onCancel { _ in await response.wait() ?? .noResponse }
        #expect(f.core.cancel(record.cancel) == .accepted)
        guard case .cancelling(_, _, let resting?)? = f.snapshot.request, case .unknown = resting else {
            Issue.record("結果不明は取消の間も保持されるはず")
            return
        }
        await f.port.cancelCalls.waitUntil(count: 1)
        response.complete(.cancelled)
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
    }

    @Test func 未知の開催回と参加者は拒否する() {
        let f = Fixture()
        #expect(rejection(f.core.selectWorkshop(SessionId("none"))) == .unknownWorkshop)
        #expect(rejection(f.core.toggleParticipant(alice)) == .unknownWorkshop)
        _ = f.core.selectWorkshop(pottery.id)
        #expect(rejection(f.core.toggleParticipant(ParticipantId("none"))) == .unknownParticipant)
    }

    @Test func 画面の購読が無くても送信の結果はCoreに残りあとから読める() async {
        let f = Fixture()
        let response = Deferred<CreateObservation>()
        f.port.onCreate { _ in await response.wait() ?? .noResponse }
        _ = f.core.submit(f.confirmed())
        await f.port.createCalls.waitUntil(count: 1)
        response.complete(.created(ReservationId("r-1")))
        await f.settled { $0.reservations.count == 1 }
        #expect(f.snapshot.reservations.count == 1)
    }

    @Test func 購読は現在の状態から始まり更新を順に受け取る() async {
        let f = Fixture()
        var iterator = f.core.snapshots().makeAsyncIterator()
        #expect(await iterator.next()?.draft.workshop == nil)
        _ = f.core.selectWorkshop(pottery.id)
        #expect(await iterator.next()?.draft.workshop?.id == pottery.id)
    }
}

@Suite(.timeLimit(.minutes(1)))
struct AdmissionTests {
    @Test func 成立した予約に含まれる参加者だけが有効で含まれない参加者は拒否する() async {
        let f = Fixture()
        let record = await f.reserve()
        guard case .valid(let current) = await f.core.admission(reservation: record.reservation, participant: alice) else {
            Issue.record("有効のはず")
            return
        }
        #expect(current.record.participant == alice)
        guard case .unavailable(.notIncluded) = await f.core.admission(reservation: record.reservation, participant: bob) else {
            Issue.record("含まれないはず")
            return
        }
    }

    @Test func 結果不明の予約は有効にならない() async {
        let f = Fixture()
        f.port.onCreate { _ in .noResponse }
        _ = f.core.submit(f.confirmed())
        await f.settled { if case .unknown? = $0.request { return true } else { return false } }
        guard case .unavailable(.notReserved) = await f.core.admission(reservation: ReservationId("r-1"), participant: alice) else {
            Issue.record("成立が確認できていない予約は有効にならないはず")
            return
        }
    }

    @Test func 取消の応答が失われてもサービスの現在の状態で取消済みと判断する() async {
        let f = Fixture()
        let record = await f.reserve()
        f.port.onUsage { _ in .cancelled }
        guard case .unavailable(.cancelled) = await f.core.admission(reservation: record.reservation, participant: alice) else {
            Issue.record("取消済みのはず")
            return
        }
    }

    @Test func 現在の状態を確認できなければ結果不明とは別の確認不能にする() async {
        let f = Fixture()
        let record = await f.reserve()
        f.port.onUsage { _ in .failed }
        guard case .unconfirmed = await f.core.admission(reservation: record.reservation, participant: alice) else {
            Issue.record("確認不能のはず")
            return
        }
    }

    @Test func 照会の応答を待つ間に取消が成立したら有効な確認を発行しない() async {
        let f = Fixture(now: cancellable)
        let record = await f.reserve()
        let usage = Deferred<UsageObservation>()
        defer { usage.complete(.failed) }
        f.port.onUsage { _ in await usage.wait() ?? .failed }
        async let lookup = f.core.admission(reservation: record.reservation, participant: alice)
        _ = f.core.cancel(record.cancel)
        await f.settled { if case .cancelled? = $0.reservations.first { return true } else { return false } }
        usage.complete(.active)
        guard case .unavailable(.cancelled) = await lookup else {
            Issue.record("応答が有効でも、取消済みの予約には発行しないはず")
            return
        }
    }

    @Test func 照会の応答を待つ間に取消の結果待ちになったら有効な確認を発行しない() async {
        let f = Fixture(now: cancellable)
        let record = await f.reserve()
        let usage = Deferred<UsageObservation>()
        let cancelResponse = Deferred<CancelObservation>()
        defer {
            usage.complete(.failed)
            cancelResponse.complete(.noResponse)
        }
        f.port.onUsage { _ in await usage.wait() ?? .failed }
        f.port.onCancel { _ in await cancelResponse.wait() ?? .noResponse }
        async let lookup = f.core.admission(reservation: record.reservation, participant: alice)
        _ = f.core.cancel(record.cancel)
        await f.port.cancelCalls.waitUntil(count: 1)
        usage.complete(.active)
        guard case .unconfirmed = await lookup else {
            Issue.record("取消の結果待ちの間は確認不能のはず")
            return
        }
        cancelResponse.complete(.cancelled)
    }
}
