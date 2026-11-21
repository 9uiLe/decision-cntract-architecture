import Combine
import Composition
import Kernel
import ReservationCore
import ReservationUI
import SimulatorAdapter
import SwiftUI

enum Scenario: String {
    case ticket, request, endpoint
}

enum Variant: String {
    case product, heldInState, removeDuplicates, heldByTicket
}

struct FixtureConfiguration {
    let scenario: Scenario
    let variant: Variant

    init(arguments: [String]) {
        func value(after flag: String) -> String? {
            arguments.firstIndex(of: flag).flatMap { index in arguments.indices.contains(index + 1) ? arguments[index + 1] : nil }
        }
        scenario = value(after: "-scenario").flatMap(Scenario.init) ?? .ticket
        variant = value(after: "-variant").flatMap(Variant.init) ?? .product
    }
}

// 画面に渡す値。更新の前後（T1・T2）を、中間の表示なしで直接差し替えるために、ViewModel の状態とは別に持つ。
struct Shown {
    let status: StatusState
    let endpoint: ReservationViewModel
}

private func sameDisplay(_ lhs: Shown, _ rhs: Shown) -> Bool {
    guard lhs.endpoint === rhs.endpoint, let left = lhs.status.request, let right = rhs.status.request else { return false }
    return left.summary.workshopName == right.summary.workshopName
        && left.summary.start == right.summary.start
        && left.summary.participantNames == right.summary.participantNames
        && left.kind == right.kind
}

extension RequestCard {
    var kind: String {
        switch self {
        case .sending: return "sending"
        case .checkingStatus: return "checkingStatus"
        case .unknown: return "unknown"
        case .notCreated: return "notCreated"
        case .soldOut: return "soldOut"
        case .registrationClosed: return "registrationClosed"
        case .eligibilityLost: return "eligibilityLost"
        }
    }

    var ticketIdentity: ObjectIdentifier? {
        switch self {
        case .unknown(_, let check): return ObjectIdentifier(check)
        case .notCreated(_, let retry, _, _): return ObjectIdentifier(retry)
        case .sending, .checkingStatus, .soldOut, .registrationClosed, .eligibilityLost: return nil
        }
    }
}

@MainActor
final class FixtureModel: ObservableObject {
    @Published private(set) var shown: Shown?
    @Published private(set) var offered: Shown?
    @Published private(set) var phase = "preparing"
    @Published private(set) var observed: SimulatorObservations?
    @Published private(set) var firstRequest: RequestId?
    @Published private(set) var firstTicket: ObjectIdentifier?
    @Published private(set) var secondTicket: ObjectIdentifier?
    @Published private(set) var secondRequest: RequestId?

    let configuration: FixtureConfiguration
    let endpointA: ReservationViewModel
    let endpointB: ReservationViewModel
    private let container = AppContainer()
    private let updates = PassthroughSubject<Shown, Never>()
    private var subscription: AnyCancellable?
    private var observing: Task<Void, Never>?
    private var first: Shown?
    private var second: Shown?

    init(configuration: FixtureConfiguration) {
        self.configuration = configuration
        let container = container
        func makeEndpoint() -> ReservationViewModel {
            ReservationViewModel(
                core: container.core, eligibility: container.eligibility, checkIn: container.checkIn,
                catalog: container.catalog, timeChanges: container.clock.changes()
            )
        }
        endpointA = makeEndpoint()
        endpointB = makeEndpoint()
        switch configuration.variant {
        case .removeDuplicates:
            subscription = updates.removeDuplicates(by: sameDisplay).sink { [weak self] in self?.shown = $0 }
        case .product, .heldInState, .heldByTicket:
            subscription = updates.sink { [weak self] in self?.shown = $0 }
        }
    }

    // 配信の入口。offered は流れへ渡した値で、shown（画面に届いた値）とは別に持つ。途中で更新が落ちたかどうかを区別するため。
    private func offer(_ next: Shown) {
        offered = next
        updates.send(next)
    }

    func showSecond() {
        if let second { offer(second) }
    }

    func showEndpointB() {
        if let first { offer(Shown(status: first.status, endpoint: endpointB)) }
    }

    func start() async {
        guard observing == nil else { return }
        endpointA.start()
        endpointB.start()
        let controls = container.simulatorControls
        observed = controls.observations
        let stream = controls.observationUpdates()
        observing = Task { [weak self] in
            for await value in stream {
                guard let self else { return }
                observed = value
            }
        }
        switch configuration.scenario {
        case .ticket: await prepareTicket(controls)
        case .request: await prepareRequest(controls)
        case .endpoint: await prepareEndpoint(controls)
        }
        firstTicket = first?.status.request?.ticketIdentity
        secondTicket = second?.status.request?.ticketIdentity
        if let first { offer(first) }
        phase = "ready"
    }

    private func reachUnknown(createdAs behavior: CreateBehavior, _ controls: any SimulatorControls) async -> Shown {
        controls.enqueueCreate(behavior)
        _ = endpointA.selectWorkshop(SessionId("pottery"))
        _ = endpointA.toggleParticipant(akari)
        endpointA.confirm()
        if case .confirmed(let draft, _) = endpointA.state.details.stage { _ = endpointA.submit(draft) }
        return await wait { $0.status.request?.kind == "unknown" }
    }

    private func wait(_ predicate: @escaping @MainActor (ReservationScreenState) -> Bool) async -> Shown {
        for await state in endpointA.$state.values where predicate(state) {
            return Shown(status: state.status, endpoint: endpointA)
        }
        return Shown(status: endpointA.state.status, endpoint: endpointA)
    }

    private func check(of shown: Shown) -> StatusCheckTicket? {
        if case .unknown(_, let check)? = shown.status.request { return check }
        return nil
    }

    private func request(of shown: Shown) -> RequestId? { check(of: shown)?.request }

    private func prepareTicket(_ controls: any SimulatorControls) async {
        let t1 = await reachUnknown(createdAs: .loseResponseAfterCreate, controls)
        controls.enqueueLookUp(.fail)
        if let ticket = check(of: t1) { _ = endpointA.checkStatus(ticket) }
        let t1Ticket = check(of: t1)
        second = await wait { state in
            if case .unknown(_, let check)? = state.status.request { return check !== t1Ticket } else { return false }
        }
        first = t1
        firstRequest = request(of: t1)
        secondRequest = second.flatMap(request)
    }

    private func prepareRequest(_ controls: any SimulatorControls) async {
        let t1 = await reachUnknown(createdAs: .loseResponseAfterReject, controls)
        if let ticket = check(of: t1) { _ = endpointA.checkStatus(ticket) }
        let rested = await wait { $0.status.request?.kind == "notCreated" }
        controls.enqueueCreate(.loseResponseAfterCreate)
        if case .notCreated(_, let retry, _, _)? = rested.status.request { _ = endpointA.retry(retry) }
        let t1Ticket = check(of: t1)
        second = await wait { state in
            if case .unknown(_, let check)? = state.status.request { return check !== t1Ticket } else { return false }
        }
        first = t1
        firstRequest = request(of: t1)
        secondRequest = second.flatMap(request)
    }

    private func prepareEndpoint(_ controls: any SimulatorControls) async {
        let t1 = await reachUnknown(createdAs: .loseResponseAfterCreate, controls)
        controls.enqueueLookUp(.hold)
        first = t1
        second = Shown(status: t1.status, endpoint: endpointB)
        firstRequest = request(of: t1)
        secondRequest = firstRequest
    }
}
