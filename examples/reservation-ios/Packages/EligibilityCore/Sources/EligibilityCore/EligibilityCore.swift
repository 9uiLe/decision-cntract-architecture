public import Kernel

// 期限の判定は status と verify の二か所に限り、利用する Core には複製させない。
public final class EligibilityCore: EligibilityAuthority {
    private struct Entry: Sendable {
        let operation: UInt64
        let phase: Phase

        var isChecking: Bool {
            if case .checking = phase { return true }
            return false
        }
    }

    private enum Phase: Sendable {
        case checking
        case settled(EligibilityOutcome)
    }

    private struct State: Sendable {
        var lastOperation: UInt64 = 0
        var entries: [EligibilityKey: Entry] = [:]
    }

    private let port: any EligibilityPort
    private let clock: any Clock
    private let timeout: any OperationTimeout
    private let store: Store<State, EligibilitySnapshot>

    init(port: any EligibilityPort, clock: any Clock, timeout: any OperationTimeout) {
        self.port = port
        self.clock = clock
        self.timeout = timeout
        store = Store(State()) { state in EligibilityCore.snapshot(of: state.entries, at: clock.now()) }
    }

    // 期限は読み取る時刻で判定する。購読で受けた値は再読込の合図にとどめ、表示する値はこの読み取りから得る。
    public var snapshot: EligibilitySnapshot {
        EligibilityCore.snapshot(of: store.read { $0.entries }, at: clock.now())
    }

    private static func snapshot(of entries: [EligibilityKey: Entry], at now: EpochMillis) -> EligibilitySnapshot {
        EligibilitySnapshot(statuses: entries.mapValues { status(of: $0, at: now) })
    }

    public func snapshots() -> AsyncStream<EligibilitySnapshot> { store.updates() }

    public func status(participant: ParticipantId, kind: QualificationKind) -> EligibilityStatus {
        let entry = store.read { $0.entries[EligibilityKey(participant: participant, kind: kind)] }
        return EligibilityCore.status(of: entry, at: clock.now())
    }

    public func request(participant: ParticipantId, kind: QualificationKind) -> EligibilityRequestAcceptance {
        let key = EligibilityKey(participant: participant, kind: kind)
        let operation = store.mutate { state -> UInt64? in
            if state.entries[key]?.isChecking == true { return nil }
            state.lastOperation += 1
            state.entries[key] = Entry(operation: state.lastOperation, phase: .checking)
            return state.lastOperation
        }
        guard let operation else { return .rejected(.busy) }
        Task {
            let observation = await fetch(key)
            settle(key, operation: operation, observation: observation)
        }
        return .accepted
    }

    public func verify(_ certificates: [EligibilityCertificate]) async -> Reverification {
        let keys = certificates.map { EligibilityKey(participant: $0.participant, kind: $0.kind) }
        if Set(keys).count != keys.count { return .failed(.targetMismatch) }
        let previous = store.read { state in keys.map { state.entries[$0]?.operation } }
        var observations: [QualificationObservation] = []
        for key in keys { observations.append(await fetch(key)) }
        let decidedAt = clock.now()
        let observed = observations
        store.mutate { state in
            for (index, key) in keys.enumerated() {
                let current = state.entries[key]
                guard current?.operation == previous[index], current?.isChecking != true else { continue }
                state.lastOperation += 1
                state.entries[key] = Entry(
                    operation: state.lastOperation,
                    phase: .settled(EligibilityCore.outcome(of: key, observed[index], at: decidedAt))
                )
            }
        }
        var refreshed: [EligibilityCertificate] = []
        for (index, certificate) in certificates.enumerated() {
            if decidedAt >= certificate.expiresAt { return .failed(.expired) }
            switch observations[index] {
            case .held(let expiresAt):
                if decidedAt >= expiresAt { return .failed(.expired) }
                refreshed.append(EligibilityCertificate(
                    participant: certificate.participant, kind: certificate.kind, checkedAt: decidedAt, expiresAt: expiresAt
                ))
            case .notHeld:
                return .failed(.notHeld)
            case .unavailable:
                return .failed(.unconfirmed)
            }
        }
        return .verified(refreshed)
    }

    private func fetch(_ key: EligibilityKey) async -> QualificationObservation {
        let port = port
        return await timeout.observe(unconfirmed: .unavailable) {
            await port.fetchQualification(participant: key.participant, kind: key.kind)
        }
    }

    private func settle(_ key: EligibilityKey, operation: UInt64, observation: QualificationObservation) {
        let now = clock.now()
        store.mutate { state in
            guard let current = state.entries[key], current.isChecking, current.operation == operation else { return }
            state.entries[key] = Entry(operation: operation, phase: .settled(EligibilityCore.outcome(of: key, observation, at: now)))
        }
    }

    private static func outcome(of key: EligibilityKey, _ observation: QualificationObservation, at checkedAt: EpochMillis)
        -> EligibilityOutcome
    {
        switch observation {
        case .held(let expiresAt):
            guard checkedAt < expiresAt else { return .expired }
            return .eligible(EligibilityCertificate(
                participant: key.participant, kind: key.kind, checkedAt: checkedAt, expiresAt: expiresAt
            ))
        case .notHeld: return .notHeld
        case .unavailable: return .unknown
        }
    }

    private static func status(of entry: Entry?, at now: EpochMillis) -> EligibilityStatus {
        guard let entry else { return .notChecked }
        switch entry.phase {
        case .checking:
            return .checking
        case .settled(let outcome):
            if case .eligible(let certificate) = outcome, now >= certificate.expiresAt { return .settled(.expired) }
            return .settled(outcome)
        }
    }
}
