public import EligibilityCore
public import Kernel

private let cancellationWindowMillis: Int64 = 24 * 60 * 60 * 1000

// 外部要求は呼び出し元ではなく Core が起動する Task で実行する。画面や ViewModel が破棄されても要求を捨てず、
// 結果は snapshot にだけ反映する。
public final class ReservationCore: AdmissionSource {
    private enum Readiness {
        case ready(EligibilityCertificate)
        case notReady
    }

    private enum CreatePermission: Sendable {
        case granted
        case denied(CreateDenial)
    }

    private let catalog: Catalog
    private let port: any ReservationPort
    private let eligibility: any EligibilityAuthority
    private let clock: any Clock
    private let newRequestId: @Sendable () -> RequestId
    private let timeout: any OperationTimeout
    private let store: Store<CoreState, CoreSnapshot>

    init(
        catalog: Catalog,
        port: any ReservationPort,
        eligibility: any EligibilityAuthority,
        clock: any Clock,
        newRequestId: @escaping @Sendable () -> RequestId,
        timeout: any OperationTimeout
    ) {
        self.catalog = catalog
        self.port = port
        self.eligibility = eligibility
        self.clock = clock
        self.newRequestId = newRequestId
        self.timeout = timeout
        store = Store(CoreState()) { state in
            CoreSnapshot(draft: state.draft, request: state.request?.view, reservations: state.reservations)
        }
    }

    public var snapshot: CoreSnapshot { store.snapshot }

    public func snapshots() -> AsyncStream<CoreSnapshot> { store.updates() }

    public func registrationGate(for workshop: Workshop) -> TimeGate { isRegistrationClosed(workshop) ? .closed : .open }

    public func cancellationGate(for workshop: Workshop) -> TimeGate { isCancellationClosed(workshop) ? .closed : .open }

    public func selectWorkshop(_ session: SessionId) -> Acceptance {
        guard let workshop = catalog.workshops.first(where: { $0.id == session }) else {
            return .rejected(.unknownWorkshop)
        }
        store.mutate { state in
            if state.draft.workshop?.id == session { return }
            state.draft = Draft(workshop: workshop, participants: [], confirmation: .unconfirmed)
            state.request = state.request?.withoutFinishedResult
        }
        return .accepted
    }

    public func toggleParticipant(_ participant: ParticipantId) -> Acceptance {
        guard catalog.participants.contains(where: { $0.id == participant }) else { return .rejected(.unknownParticipant) }
        return store.mutate { state in
            guard let workshop = state.draft.workshop else { return .rejected(.unknownWorkshop) }
            var toggled = state.draft.participants
            if let index = toggled.firstIndex(of: participant) {
                toggled.remove(at: index)
            } else {
                toggled.append(participant)
            }
            state.draft = Draft(workshop: workshop, participants: toggled, confirmation: .unconfirmed)
            state.request = state.request?.withoutFinishedResult
            return .accepted
        }
    }

    public func requestEligibility() -> Acceptance {
        let draft = store.read { $0.draft }
        guard let workshop = draft.workshop else { return .rejected(.unknownWorkshop) }
        let kind: QualificationKind
        switch workshop.requirement {
        case .notRequired: return .rejected(.qualificationNotRequired)
        case .required(let required): kind = required
        }
        if draft.participants.isEmpty { return .rejected(.noParticipants) }
        for participant in draft.participants {
            guard case .notReady = readiness(of: eligibility.status(participant: participant, kind: kind), participant, kind) else {
                continue
            }
            _ = eligibility.request(participant: participant, kind: kind)
        }
        return .accepted
    }

    public func confirmWithoutQualification() -> ConfirmOutcome {
        store.mutate { state in
            guard let workshop = state.draft.workshop else { return .noWorkshop }
            switch workshop.requirement {
            case .required: return .qualificationRequired
            case .notRequired: break
            }
            if state.draft.participants.isEmpty { return .emptyParticipants }
            let confirmed = ConfirmedDraft(
                workshop: workshop, participants: state.draft.participants, basis: .withoutQualification, certificates: []
            )
            state.draft = Draft(workshop: workshop, participants: state.draft.participants, confirmation: .confirmed(confirmed))
            return .confirmed(confirmed)
        }
    }

    public func confirmQualified() -> ConfirmOutcome {
        store.mutate { [self] state in
            guard let workshop = state.draft.workshop else { return .noWorkshop }
            let kind: QualificationKind
            switch workshop.requirement {
            case .notRequired: return .qualificationNotRequired
            case .required(let required): kind = required
            }
            let participants = state.draft.participants
            if participants.isEmpty { return .emptyParticipants }
            var certificates: [EligibilityCertificate] = []
            var notReady: [ParticipantId] = []
            for participant in participants {
                switch readiness(of: eligibility.status(participant: participant, kind: kind), participant, kind) {
                case .ready(let certificate): certificates.append(certificate)
                case .notReady: notReady.append(participant)
                }
            }
            if !notReady.isEmpty { return .eligibilityNotMet(notReady) }
            let confirmed = ConfirmedDraft(
                workshop: workshop, participants: participants, basis: .withQualification(kind), certificates: certificates
            )
            state.draft = Draft(workshop: workshop, participants: participants, confirmation: .confirmed(confirmed))
            return .confirmed(confirmed)
        }
    }

    public func submit(_ confirmed: ConfirmedDraft) -> Acceptance {
        transact { [self] state in
            guard case .confirmed(let current) = state.draft.confirmation, current === confirmed else {
                return .rejected(state.rejection(forUnmatched: ObjectIdentifier(confirmed)))
            }
            if state.request?.blocksNewCreate == true { return .rejected(.busy) }
            if isRegistrationClosed(confirmed.workshop) {
                state.request = state.request.withClosedResult(confirmed)
                return .rejected(.registrationClosed)
            }
            let job = CreateJob(
                operation: state.nextOperation(), request: newRequestId(), draft: confirmed,
                trigger: ObjectIdentifier(confirmed), resting: nil
            )
            state.draft = Draft(workshop: state.draft.workshop, participants: state.draft.participants, confirmation: .unconfirmed)
            state.request = .creating(job)
            return .create(job)
        }
    }

    public func checkStatus(_ ticket: StatusCheckTicket) -> Acceptance {
        transact { state in
            guard case .unknown(let held)? = state.request, held === ticket else {
                return .rejected(state.rejection(forUnmatched: ObjectIdentifier(ticket)))
            }
            let operation = state.nextOperation()
            state.request = .checkingStatus(operation: operation, ticket: ticket)
            return .lookUp(operation: operation, ticket: ticket)
        }
    }

    public func retry(_ ticket: RetryTicket) -> Acceptance {
        transact { [self] state in
            guard case .notCreated(let resting)? = state.request, resting.retry === ticket else {
                return .rejected(state.rejection(forUnmatched: ObjectIdentifier(ticket)))
            }
            if isRegistrationClosed(ticket.draft.workshop) { return .rejected(.registrationClosed) }
            let job = CreateJob(
                operation: state.nextOperation(), request: newRequestId(), draft: ticket.draft,
                trigger: ObjectIdentifier(ticket), resting: resting
            )
            state.request = .creating(job)
            return .create(job)
        }
    }

    public func cancel(_ ticket: CancelTicket) -> Acceptance {
        transact { [self] state in
            let record = state.reservations.lazy.compactMap { record -> ReservedRecord? in
                guard case .reserved(let reserved) = record, reserved.cancel === ticket else { return nil }
                return reserved
            }.first
            guard let record else { return .rejected(state.rejection(forUnmatched: ObjectIdentifier(ticket))) }
            if state.request?.blocksCancel == true { return .rejected(.busy) }
            if isCancellationClosed(record.booking.workshop) { return .rejected(.cancellationClosed) }
            let job = CancelJob(
                operation: state.nextOperation(), record: record, trigger: ObjectIdentifier(ticket), resting: state.request
            )
            state.request = .cancelling(job)
            return .cancel(job)
        }
    }

    public func admission(reservation: ReservationId, participant: ParticipantId) async -> AdmissionLookup {
        let record = store.read { state in state.reservations.first { $0.reservation == reservation } }
        guard let record else { return .unavailable(.notReserved) }
        let reserved: ReservedRecord
        switch record {
        case .cancelled: return .unavailable(.cancelled)
        case .reserved(let value): reserved = value
        }
        guard let admission = reserved.admissions.first(where: { $0.participant == participant }) else {
            return .unavailable(.notIncluded)
        }
        let port = port
        switch await timeout.observe(unconfirmed: .failed, { await port.lookUpUsage(reservation) }) {
        case .active: return recheckedAdmission(admission)
        case .cancelled: return .unavailable(.cancelled)
        case .notFound: return .unavailable(.notReserved)
        case .failed: return .unconfirmed
        }
    }

    // サービスの応答を待つ間に、同じ予約が取消済みや取消の結果待ちになることがある。応答が有効でも、発行する直前の
    // Core の状態と照合する。取消の結果待ちは、サービス側で取消済みかもしれないため確認不能にする。
    private func recheckedAdmission(_ admission: ReservationAdmission) -> AdmissionLookup {
        let reservation = admission.reservation
        let present = store.read { state -> AdmissionLookup? in
            guard let record = state.reservations.first(where: { $0.reservation == reservation }) else {
                return .unavailable(.notReserved)
            }
            switch record {
            case .cancelled:
                return .unavailable(.cancelled)
            case .reserved:
                if case .cancelling(let job)? = state.request, job.record.reservation == reservation { return .unconfirmed }
                return nil
            }
        }
        return present ?? .valid(CurrentAdmission(record: admission, observedAt: clock.now()))
    }

    private func transact(_ decide: @Sendable (inout CoreState) -> CoreStep) -> Acceptance {
        switch store.mutate(decide) {
        case .rejected(let reason):
            return .rejected(reason)
        case .create(let job):
            launchCreate(job)
        case .lookUp(let operation, let ticket):
            launchLookUp(operation: operation, ticket: ticket)
        case .cancel(let job):
            launchCancel(job)
        }
        return .accepted
    }

    // 送信と再試行は同じ判断を通る。資格の再確認から戻った後で、受付終了を判定し直す。
    private func launchCreate(_ job: CreateJob) {
        Task {
            switch await permitCreate(job.draft) {
            case .denied(let denial):
                settle(job.operation) { $0.request = job.deniedBy(denial) }
            case .granted:
                let port = port
                let observation = await timeout.observe(unconfirmed: .noResponse) {
                    await port.createReservation(
                        request: job.request, session: job.draft.workshop.id, participants: job.draft.participants
                    )
                }
                settle(job.operation) { $0.apply(observation, to: job) }
            }
        }
    }

    private func permitCreate(_ draft: ConfirmedDraft) async -> CreatePermission {
        var checkedAt: EpochMillis?
        if !draft.certificates.isEmpty {
            let startedAt = clock.now()
            switch await eligibility.verify(draft.certificates) {
            case .verified(let certificates):
                let finishedAt = clock.now()
                if !certificates.coverExactly(draft) { return .denied(.eligibilityLost(.targetMismatch)) }
                guard let common = certificates.commonCheckedAt(from: startedAt, to: finishedAt) else {
                    return .denied(.eligibilityLost(.unconfirmed))
                }
                checkedAt = common
            case .failed(let reason):
                return .denied(.eligibilityLost(reason))
            }
        }
        let closedAtCheck = checkedAt.map { $0 >= draft.workshop.start } ?? false
        return closedAtCheck || isRegistrationClosed(draft.workshop) ? .denied(.registrationClosed) : .granted
    }

    private func launchLookUp(operation: OperationId, ticket: StatusCheckTicket) {
        Task {
            let port = port
            let observation = await timeout.observe(unconfirmed: .failed) {
                await port.lookUpReservation(request: ticket.request)
            }
            settle(operation) { $0.apply(observation, to: ticket) }
        }
    }

    private func launchCancel(_ job: CancelJob) {
        Task {
            let port = port
            let observation = await timeout.observe(unconfirmed: .noResponse) {
                await port.cancelReservation(job.record.reservation)
            }
            settle(job.operation) { $0.apply(observation, to: job) }
        }
    }

    private func settle(_ operation: OperationId, _ transform: @Sendable (inout CoreState) -> Void) {
        store.mutate { state in
            guard state.request?.operation == operation else { return }
            transform(&state)
        }
    }

    private func readiness(of status: EligibilityStatus, _ participant: ParticipantId, _ kind: QualificationKind) -> Readiness {
        switch status {
        case .notChecked, .checking: return .notReady
        case .settled(let outcome): return readiness(ofOutcome: outcome, participant, kind)
        }
    }

    private func readiness(ofOutcome outcome: EligibilityOutcome, _ participant: ParticipantId, _ kind: QualificationKind)
        -> Readiness
    {
        switch outcome {
        case .eligible(let certificate):
            return certificate.participant == participant && certificate.kind == kind ? .ready(certificate) : .notReady
        case .notHeld, .expired, .unknown:
            return .notReady
        }
    }

    private func isRegistrationClosed(_ workshop: Workshop) -> Bool { clock.now() >= workshop.start }

    private func isCancellationClosed(_ workshop: Workshop) -> Bool {
        clock.now().value >= workshop.start.value - cancellationWindowMillis
    }
}

extension CreateJob {
    fileprivate func deniedBy(_ denial: CreateDenial) -> Pending {
        if let resting { return .notCreated(RestingNotCreated(retry: resting.retry, lastDenial: denial)) }
        switch denial {
        case .registrationClosed: return .registrationClosed(draft)
        case .eligibilityLost(let reason): return .eligibilityLost(draft, reason)
        }
    }
}

extension Array where Element == EligibilityCertificate {
    fileprivate func commonCheckedAt(from startedAt: EpochMillis, to finishedAt: EpochMillis) -> EpochMillis? {
        let times = Set(map(\.checkedAt))
        guard times.count == 1, let checkedAt = times.first else { return nil }
        return checkedAt >= startedAt && checkedAt <= finishedAt ? checkedAt : nil
    }

    fileprivate func coverExactly(_ draft: ConfirmedDraft) -> Bool {
        var kind: QualificationKind?
        if case .required(let required) = draft.workshop.requirement { kind = required }
        return count == draft.participants.count
            && Set(map(\.participant)) == Set(draft.participants)
            && allSatisfy { $0.kind == kind }
    }
}
