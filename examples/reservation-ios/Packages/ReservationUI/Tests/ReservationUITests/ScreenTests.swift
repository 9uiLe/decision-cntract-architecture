import CheckInCore
import EligibilityCore
import Kernel
import KernelTestSupport
import ReservationCore
import Testing

@testable import ReservationUI

private func badge(_ state: ReservationScreenState, _ participant: ParticipantId) -> QualificationBadge? {
    state.details.participants.first { $0.id == participant }?.qualification
}

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ScreenTests {
    @Test func 要資格回は参加者ごとの資格の状況を表し全員がそろうまで確認へ進めない() async {
        let s = Screen()
        s.qualification.observations.withLock {
            $0[alice] = .held(expiresAt: EpochMillis(start + hour))
            $0[bob] = .notHeld
        }
        _ = s.viewModel.selectWorkshop(advanced.id)
        _ = s.viewModel.toggleParticipant(alice)
        _ = s.viewModel.toggleParticipant(bob)
        #expect(s.viewModel.state.details.canConfirm == false)
        #expect(s.viewModel.state.details.confirmHint != nil)
        _ = s.viewModel.requestEligibility()
        let settled = await s.state { badge($0, alice) == .valid && badge($0, bob) == .notHeld }
        #expect(settled.details.canConfirm == false)
        s.qualification.observations.withLock { $0[bob] = .held(expiresAt: EpochMillis(start + hour)) }
        _ = s.viewModel.requestEligibility()
        let ready = await s.state { $0.details.canConfirm }
        #expect(ready.details.confirmHint == nil)
    }

    @Test func 通常回は資格の表示も確認の導線も出さない() {
        let s = Screen()
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        let details = s.viewModel.state.details
        #expect(details.participants.allSatisfy { $0.qualification == nil })
        #expect(details.canRequestEligibility == false)
        #expect(details.canConfirm)
    }

    @Test func 画面を経由せず確認を押しても条件を満たさなければCoreが確認済みにせず通知を出す() {
        let s = Screen()
        _ = s.viewModel.selectWorkshop(advanced.id)
        _ = s.viewModel.toggleParticipant(alice)
        guard case .eligibilityNotMet = s.viewModel.confirm() else { Issue.record("確認済みにならないはず"); return }
        #expect(s.viewModel.notice?.message == confirmFailureMessage(.eligibilityNotMet([alice])))
        guard case .editing = s.viewModel.state.details.stage else { Issue.record("編集中のままのはず"); return }
    }

    @Test func 予約詳細は参加者ごとの受付状態を表し受付後も他の参加者は未受付のまま() async {
        let s = Screen()
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        _ = s.viewModel.toggleParticipant(bob)
        s.viewModel.confirm()
        guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { Issue.record("確認済みのはず"); return }
        _ = s.viewModel.submit(draft)
        let reserved = await s.state { !$0.status.reservations.isEmpty }
        guard case .reserved(let card) = reserved.status.reservations[0] else { Issue.record("予約確定のはず"); return }
        s.advance(to: start - 10 * minute)
        let open = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            return card.participants.allSatisfy { $0.window == .open }
        }
        guard case .reserved(let current) = open.status.reservations[0] else { return }
        _ = s.viewModel.checkInParticipant(current.participants[0].admission)
        let done = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            if case .checkedIn = card.participants[0].view { return true }
            return false
        }
        guard case .reserved(let after) = done.status.reservations[0] else { return }
        guard case .notCheckedIn = after.participants[1].view else { Issue.record("もう一人は未受付のはず"); return }
        #expect(card.participants.count == 2)
    }

    @Test func 受付できない操作は通知として伝え画面の再操作でも受付作成要求は重ならない() async {
        let s = Screen()
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        s.viewModel.confirm()
        guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { return }
        _ = s.viewModel.submit(draft)
        let reserved = await s.state { !$0.status.reservations.isEmpty }
        guard case .reserved(let card) = reserved.status.reservations[0] else { return }
        let before = s.viewModel.checkInParticipant(card.participants[0].admission)
        #expect(before == .rejected(.notYetOpen))
        #expect(s.viewModel.notice?.message == checkInRejectionMessage(.notYetOpen))
    }
}

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct TimeDependentScreenTests {
    private func reserve(_ s: Screen) async -> ReservationScreenState {
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        s.viewModel.confirm()
        guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { fatalError("確認済みのはず") }
        _ = s.viewModel.submit(draft)
        return await s.state { !$0.status.reservations.isEmpty }
    }

    @Test func 更新が無くても時刻が進めば取消と受付と送信の可否が現在時刻で変わる() async {
        let s = Screen(now: start - 30 * hour)
        _ = await reserve(s)
        let beforeDeadline = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            return card.cancellation == .open
        }
        guard case .reserved(let open) = beforeDeadline.status.reservations[0] else { return }
        #expect(open.participants[0].window == .notYetOpen)

        s.advance(to: start - 24 * hour)
        let closed = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            return card.cancellation == .closed
        }
        guard case .reserved(let afterDeadline) = closed.status.reservations[0] else { return }
        #expect(afterDeadline.participants[0].window == .notYetOpen)

        s.advance(to: start - 30 * minute)
        let windowOpen = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            return card.participants[0].window == .open
        }
        #expect(windowOpen.status.reservations.count == 1)

        s.advance(to: start + 15 * minute)
        let windowClosed = await s.state { state in
            guard case .reserved(let card) = state.status.reservations[0] else { return false }
            return card.participants[0].window == .closed
        }
        #expect(windowClosed.status.reservations.count == 1)
    }

    @Test func 確認済みの下書きは開始時刻を迎えると送信できない旨に変わる() async {
        let s = Screen(now: start - hour)
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        s.viewModel.confirm()
        let open = await s.state { state in
            if case .confirmed(_, .open) = state.details.stage { return true }
            return false
        }
        #expect(open.details.workshop != nil)
        s.advance(to: start)
        let closed = await s.state { state in
            if case .confirmed(_, .closed) = state.details.stage { return true }
            return false
        }
        #expect(closed.details.workshop != nil)
    }

    @Test func 確認した資格は更新が無くても期限を過ぎると期限切れの表示になる() async {
        let s = Screen(now: start - 10 * hour)
        s.qualification.observations.withLock { $0[alice] = .held(expiresAt: EpochMillis(start - 5 * hour)) }
        _ = s.viewModel.selectWorkshop(advanced.id)
        _ = s.viewModel.toggleParticipant(alice)
        _ = s.viewModel.requestEligibility()
        await s.state { badge($0, alice) == .valid }
        s.advance(to: start - 5 * hour)
        let expired = await s.state { badge($0, alice) == .expired }
        #expect(expired.details.canConfirm == false)
    }

    @Test func 結果不明の予約は受付が終わっても元の要求を照会する操作が残る() async {
        let s = Screen(now: start - hour)
        s.reservationPort.create.withLock { $0 = .noResponse }
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        s.viewModel.confirm()
        guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { return }
        _ = s.viewModel.submit(draft)
        await s.state { state in
            if case .unknown? = state.status.request { return true }
            return false
        }
        s.advance(to: start + hour)
        let later = await s.state { state in
            if case .unknown? = state.status.request { return true }
            return false
        }
        guard case .unknown(_, let check)? = later.status.request else { return }
        #expect(s.viewModel.checkStatus(check) == .accepted)
    }

    @Test func 取消の最中は予約を送信中とは示さず取消中の予約だけが取消を待つ() async {
        let s = Screen(now: start - 30 * hour)
        let reserved = await reserve(s)
        guard case .reserved(let card) = reserved.status.reservations[0] else { return }
        let gate = Deferred<CancelObservation>()
        s.reservationPort.release.withLock { $0 = gate }
        _ = s.viewModel.cancel(card.cancel)
        let cancelling = await s.state { state in
            if case .cancelling? = state.status.reservations.first { return true }
            return false
        }
        #expect(cancelling.status.request == nil)
        gate.complete(.cancelled)
        await s.state { state in
            if case .cancelled? = state.status.reservations.first { return true }
            return false
        }
    }
}

extension RequestCard {
    var isUnknown: Bool {
        if case .unknown = self { return true }
        return false
    }
}
