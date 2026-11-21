import CheckInCore
import EligibilityCore
import Foundation
import Kernel
import KernelTestSupport
import ReservationCore
import SimulatorAdapter
import Testing

@testable import Composition

@Suite(.timeLimit(.minutes(1)))
struct CompositionTests {
    @Test func デモの時計は日本時間ではなくUTCの2026年12月1日から始まる() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let date = Date(timeIntervalSince1970: Double(demoStart.value) / 1000)
        let parts = calendar.dateComponents([.year, .month, .day, .hour], from: date)
        #expect(parts.year == 2026 && parts.month == 12 && parts.day == 1 && parts.hour == 0)
    }

    @Test func 操作できる時計は変更を購読へ伝え初期時刻へ戻せる() async {
        let clock = ControllableClock(demoStart)
        var iterator = clock.changes().makeAsyncIterator()
        _ = await iterator.next()
        clock.advance(millis: 1_000)
        _ = await iterator.next()
        #expect(clock.now() == demoStart.advanced(byMillis: 1_000))
        clock.reset()
        #expect(clock.now() == demoStart)
    }

    @Test func 組み立てた予約アプリで通常回を予約すると検証用の観測に作成要求が一件残る() async {
        let container = AppContainer()
        let session = demoCatalog.workshops[0].id
        _ = container.core.selectWorkshop(session)
        _ = container.core.toggleParticipant(akari)
        guard case .confirmed(let draft) = container.core.confirmWithoutQualification() else {
            Issue.record("確認済みになるはず")
            return
        }
        _ = container.core.submit(draft)
        _ = await firstValue(of: container.core.snapshots()) { $0.request == nil && !$0.reservations.isEmpty }
        let observed = container.simulatorControls.observations
        #expect(observed.createRequests == 1)
        #expect(observed.activeReservations == 1)
    }

    @Test func 組み立てた予約アプリで上級回は資格を持つ参加者だけが確認済みになる() async {
        let container = AppContainer()
        _ = container.core.selectWorkshop(SessionId("advanced-pottery"))
        _ = container.core.toggleParticipant(akari)
        _ = container.core.toggleParticipant(minami)
        _ = container.core.requestEligibility()
        _ = await firstValue(of: container.eligibility.snapshots()) { snapshot in
            snapshot.statuses.count == 2 && snapshot.statuses.values.allSatisfy {
                if case .settled = $0 { return true }
                return false
            }
        }
        guard case .eligibilityNotMet(let missing) = container.core.confirmQualified() else {
            Issue.record("資格の無い参加者がいれば確認済みにならないはず")
            return
        }
        #expect(missing == [minami])
    }
}
