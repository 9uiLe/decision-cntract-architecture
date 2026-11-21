import EligibilityCore
import Kernel
import ReservationCore
import Testing

@testable import ReservationUI

private let summary = BookingSummary(workshopName: "陶芸", start: EpochMillis(start), participantNames: ["アリス"])

@MainActor
private func reachUnknown(_ s: Screen) async -> StatusCheckTicket {
    s.reservationPort.create.withLock { $0 = .noResponse }
    _ = s.viewModel.selectWorkshop(pottery.id)
    _ = s.viewModel.toggleParticipant(alice)
    s.viewModel.confirm()
    guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { fatalError("確認済みのはず") }
    _ = s.viewModel.submit(draft)
    let shown = await s.state { if case .unknown? = $0.status.request { return true } else { return false } }
    guard case .unknown(_, let check)? = shown.status.request else { fatalError("結果不明のはず") }
    return check
}

@MainActor
private func lookUpAgain(_ s: Screen, after previous: StatusCheckTicket) async -> StatusCheckTicket {
    _ = s.viewModel.checkStatus(previous)
    let shown = await s.state {
        if case .unknown(_, let check)? = $0.status.request { return check !== previous } else { return false }
    }
    guard case .unknown(_, let check)? = shown.status.request else { fatalError("結果不明のはず") }
    return check
}

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct RequestActionTests {
    @Test func 照会と再試行の案内だけが操作になりほかのカードは操作を持たない() async {
        let s = Screen()
        let ticket = await reachUnknown(s)
        let other = Screen()
        other.reservationPort.create.withLock { $0 = .notCreated }
        _ = other.viewModel.selectWorkshop(pottery.id)
        _ = other.viewModel.toggleParticipant(alice)
        other.viewModel.confirm()
        guard case .confirmed(let draft, _) = other.viewModel.state.details.stage else { return }
        _ = other.viewModel.submit(draft)
        let rested = await other.state { if case .notCreated? = $0.status.request { return true } else { return false } }
        guard case .notCreated(_, let retry, _, _)? = rested.status.request else { Issue.record("未成立のはず"); return }
        let cards: [RequestCard] = [
            .sending(summary), .checkingStatus(summary), .unknown(summary, check: ticket),
            .notCreated(summary, retry: retry, denial: nil, gate: .open),
            .soldOut(summary), .registrationClosed(summary), .eligibilityLost(summary, reason: .expired),
        ]
        var actions: [RequestAction] = []
        for card in cards {
            switch card {
            case .unknown(_, let check): actions.append(.lookingUp(check, on: s.viewModel))
            case .notCreated(_, let retry, _, _): actions.append(.retrying(retry, on: s.viewModel))
            case .sending, .checkingStatus, .soldOut, .registrationClosed, .eligibilityLost: break
            }
        }
        #expect(actions == [.lookingUp(ticket, on: s.viewModel), .retrying(retry, on: s.viewModel)])
    }

    @Test func 操作は実行先と券を保持し同じ実行先と同じ券だけが等しい() async {
        let s = Screen()
        let first = await reachUnknown(s)
        let second = await lookUpAgain(s, after: first)
        let action = RequestAction.lookingUp(first, on: s.viewModel)

        #expect(action == .lookingUp(first, on: s.viewModel))
        #expect(action != .lookingUp(second, on: s.viewModel), "表示が同じでも、券が違えば別の操作")
        #expect(action != .lookingUp(first, on: s.makeEndpoint()), "券が同じでも、実行先が違えば別の操作")
        guard case .lookUp(let endpoint, let ticket) = action else { return }
        #expect(endpoint.object === s.viewModel)
        #expect(ticket.object === first)
    }

    @Test func 実行は保持した券で照会を一度だけ出し古い操作は拒否されて要求を出さない() async {
        let s = Screen()
        let first = await reachUnknown(s)
        let old = RequestAction.lookingUp(first, on: s.viewModel)
        let second = await lookUpAgain(s, after: first)
        let current = RequestAction.lookingUp(second, on: s.viewModel)
        #expect(s.reservationPort.lookUps.withLock { $0 } == 1)

        #expect(old.execute() == .rejected(.stale))
        #expect(s.reservationPort.lookUps.withLock { $0 } == 1)

        #expect(current.execute() == .accepted)
        _ = await s.state {
            if case .unknown(_, let check)? = $0.status.request { return check !== second } else { return false }
        }
        #expect(s.reservationPort.lookUps.withLock { $0 } == 2)
    }

    @Test func 未成立の再試行も保持した券で一度だけ作成を出し古い操作は拒否される() async {
        let s = Screen()
        s.reservationPort.create.withLock { $0 = .notCreated }
        _ = s.viewModel.selectWorkshop(pottery.id)
        _ = s.viewModel.toggleParticipant(alice)
        s.viewModel.confirm()
        guard case .confirmed(let draft, _) = s.viewModel.state.details.stage else { return }
        _ = s.viewModel.submit(draft)
        let isNotCreated: @MainActor (ReservationScreenState) -> Bool = { state in
            if case .notCreated? = state.status.request { return true } else { return false }
        }
        let rested = await s.state(where: isNotCreated)
        guard case .notCreated(_, let first, _, _)? = rested.status.request else { return }
        let old = RequestAction.retrying(first, on: s.viewModel)
        #expect(old.execute() == .accepted)
        let again = await s.state {
            if case .notCreated(_, let retry, _, _)? = $0.status.request { return retry !== first } else { return false }
        }
        guard case .notCreated(_, let second, _, _)? = again.status.request else { return }
        #expect(old.execute() == .rejected(.stale))
        #expect(RequestAction.retrying(second, on: s.viewModel).execute() == .accepted)
    }
}
