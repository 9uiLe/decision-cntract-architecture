public import CheckInCore
public import Combine
public import EligibilityCore
public import Kernel
public import ReservationCore

public struct Notice: Equatable, Sendable {
    public let message: String
    public let sequence: Int
}

// 外部要求は起動せず、操作を Core へ中継して状態を投影するだけにする。画面が破棄されても Core の要求は続く。
// Core の更新と時刻の変化は再投影の合図にとどめ、表示する値は投影の時点の Core から読む。
// 更新が無いまま時刻だけが進んでも、資格の期限や送信・取消・受付の可否が古い値に固定されないようにするため。
@MainActor
public final class ReservationViewModel: ObservableObject {
    @Published public private(set) var state: ReservationScreenState
    @Published public private(set) var notice: Notice?

    private let core: ReservationCore
    private let eligibility: any EligibilityAuthority
    private let checkIn: CheckInCore
    private let catalog: Catalog
    private let timeChanges: AsyncStream<Void>
    private let gates: TimeGates
    private var observing: [Task<Void, Never>] = []
    private var noticeSequence = 0

    public init(
        core: ReservationCore,
        eligibility: any EligibilityAuthority,
        checkIn: CheckInCore,
        catalog: Catalog,
        timeChanges: AsyncStream<Void>
    ) {
        self.core = core
        self.eligibility = eligibility
        self.checkIn = checkIn
        self.catalog = catalog
        self.timeChanges = timeChanges
        gates = TimeGates(
            registration: { core.registrationGate(for: $0) },
            cancellation: { core.cancellationGate(for: $0) },
            checkIn: { checkIn.window(for: $0) }
        )
        state = project(
            snapshot: core.snapshot, eligibility: eligibility.snapshot, checkIn: checkIn.snapshot, catalog: catalog,
            gates: gates
        )
    }

    public func start() {
        guard observing.isEmpty else { return }
        observing = [
            observe(core.snapshots()),
            observe(eligibility.snapshots()),
            observe(checkIn.snapshots()),
            observe(timeChanges),
        ]
    }

    public func selectWorkshop(_ session: SessionId) -> Acceptance { relay(core.selectWorkshop(session)) }

    public func toggleParticipant(_ participant: ParticipantId) -> Acceptance { relay(core.toggleParticipant(participant)) }

    public func requestEligibility() -> Acceptance { relay(core.requestEligibility()) }

    @discardableResult
    public func confirm() -> ConfirmOutcome {
        let outcome: ConfirmOutcome
        switch core.snapshot.draft.workshop?.requirement {
        case nil, .notRequired?: outcome = core.confirmWithoutQualification()
        case .required?: outcome = core.confirmQualified()
        }
        if let message = confirmFailureMessage(outcome) { notify(message) }
        reproject()
        return outcome
    }

    public func submit(_ draft: ConfirmedDraft) -> Acceptance { relay(core.submit(draft)) }

    public func checkStatus(_ ticket: StatusCheckTicket) -> Acceptance { relay(core.checkStatus(ticket)) }

    public func retry(_ ticket: RetryTicket) -> Acceptance { relay(core.retry(ticket)) }

    public func cancel(_ ticket: CancelTicket) -> Acceptance { relay(core.cancel(ticket)) }

    @discardableResult
    public func checkInParticipant(_ admission: ReservationAdmission) -> CheckInAcceptance {
        relayCheckIn(checkIn.checkIn(admission))
    }

    @discardableResult
    public func checkParticipantStatus(_ ticket: CheckInStatusTicket) -> CheckInAcceptance {
        relayCheckIn(checkIn.checkStatus(ticket))
    }

    @discardableResult
    public func retryCheckIn(_ ticket: CheckInRetryTicket) -> CheckInAcceptance { relayCheckIn(checkIn.retry(ticket)) }

    public func consumeNotice(_ notice: Notice) {
        if self.notice == notice { self.notice = nil }
    }

    private func observe<Element: Sendable>(_ stream: AsyncStream<Element>) -> Task<Void, Never> {
        Task { [weak self] in
            for await _ in stream {
                guard let self else { return }
                reproject()
            }
        }
    }

    private func reproject() {
        state = project(
            snapshot: core.snapshot, eligibility: eligibility.snapshot, checkIn: checkIn.snapshot, catalog: catalog,
            gates: gates
        )
    }

    @discardableResult
    private func relay(_ acceptance: Acceptance) -> Acceptance {
        if case .rejected(let reason) = acceptance { notify(rejectionMessage(reason)) }
        reproject()
        return acceptance
    }

    private func relayCheckIn(_ acceptance: CheckInAcceptance) -> CheckInAcceptance {
        if case .rejected(let reason) = acceptance { notify(checkInRejectionMessage(reason)) }
        reproject()
        return acceptance
    }

    private func notify(_ message: String) {
        noticeSequence += 1
        notice = Notice(message: message, sequence: noticeSequence)
    }
}
