import EligibilityCore
import Kernel
import KernelTestSupport
import Testing

private let alice = ParticipantId("alice")
private let bob = ParticipantId("bob")
private let kind = QualificationKind(id: "pottery-advanced")

private final class Service: EligibilityPort {
    private struct State: Sendable {
        var observations: [ParticipantId: QualificationObservation] = [:]
        var fetches = 0
        var holding = false
        var held: [CheckedContinuation<Void, Never>] = []
        var onFetch: (@Sendable (ParticipantId) -> Void)?
    }

    private let state = LockedBox(State())

    func set(_ participant: ParticipantId, _ observation: QualificationObservation) {
        state.withLock { $0.observations[participant] = observation }
    }

    func hold() { state.withLock { $0.holding = true } }

    func onFetch(_ body: @escaping @Sendable (ParticipantId) -> Void) { state.withLock { $0.onFetch = body } }

    var fetches: Int { state.withLock { $0.fetches } }

    func release() {
        let waiting = state.withLock { s -> [CheckedContinuation<Void, Never>] in
            s.holding = false
            defer { s.held = [] }
            return s.held
        }
        for continuation in waiting { continuation.resume() }
    }

    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation {
        let hook = state.withLock { s -> (@Sendable (ParticipantId) -> Void)? in
            s.fetches += 1
            return s.onFetch
        }
        hook?(participant)
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow = state.withLock { s -> Bool in
                guard s.holding else { return true }
                s.held.append(continuation)
                return false
            }
            if resumeNow { continuation.resume() }
        }
        return state.withLock { $0.observations[participant] } ?? .notHeld
    }
}

private struct Fixture {
    let service = Service()
    let clock: ManualClock
    let core: EligibilityCore

    init(now: Int64 = 1_000) {
        clock = ManualClock(EpochMillis(now))
        core = makeEligibilityCore(port: service, clock: clock, timeout: ManualTimeout())
    }

    func settled(_ keys: ParticipantId...) async {
        _ = await firstValue(of: core.snapshots()) { snapshot in
            keys.allSatisfy { key in
                guard case .settled? = snapshot.statuses[EligibilityKey(participant: key, kind: kind)] else { return false }
                return true
            }
        }
    }

    func outcome(_ who: ParticipantId) -> EligibilityOutcome? {
        guard case .settled(let outcome) = core.status(participant: who, kind: kind) else { return nil }
        return outcome
    }
}

private func isExpired(_ outcome: EligibilityOutcome?) -> Bool {
    if case .expired? = outcome { return true }
    return false
}

private func certificate(_ outcome: EligibilityOutcome?) -> EligibilityCertificate? {
    if case .eligible(let certificate)? = outcome { return certificate }
    return nil
}

@Suite(.timeLimit(.minutes(1)))
struct EligibilityCoreTests {
    @Test func 期限の直前は有効で期限ちょうどから無効() async {
        let f = Fixture(now: 999)
        f.service.set(alice, .held(expiresAt: EpochMillis(1_000)))
        _ = f.core.request(participant: alice, kind: kind)
        await f.settled(alice)
        #expect(certificate(f.outcome(alice)) != nil)
        f.clock.set(EpochMillis(1_000))
        #expect(isExpired(f.outcome(alice)))
    }

    @Test func 期限切れ未取得結果不明を区別し読めない応答を有効と推測しない() async {
        let f = Fixture()
        let carol = ParticipantId("carol")
        f.service.set(alice, .held(expiresAt: EpochMillis(1_000)))
        f.service.set(bob, .unavailable)
        for who in [alice, bob, carol] { _ = f.core.request(participant: who, kind: kind) }
        await f.settled(alice, bob, carol)
        #expect(isExpired(f.outcome(alice)))
        guard case .unknown? = f.outcome(bob) else { Issue.record("bob は結果不明のはず"); return }
        guard case .notHeld? = f.outcome(carol) else { Issue.record("carol は未取得のはず"); return }
    }

    @Test func 確認中の同じ組の要求は重複して照会せず別の組は並行して照会できる() async {
        let f = Fixture()
        f.service.hold()
        #expect(f.core.request(participant: alice, kind: kind) == .accepted)
        #expect(f.core.request(participant: alice, kind: kind) == .rejected(.busy))
        #expect(f.core.request(participant: bob, kind: kind) == .accepted)
        guard case .checking = f.core.status(participant: alice, kind: kind) else { Issue.record("確認中のはず"); return }
        f.service.release()
        await f.settled(alice, bob)
        #expect(f.service.fetches == 2)
    }

    @Test func 発行した証明書は確認した時点を記録し再確認で再発行すると新しい時点になる() async {
        let f = Fixture(now: 100)
        f.service.set(alice, .held(expiresAt: EpochMillis(10_000)))
        _ = f.core.request(participant: alice, kind: kind)
        await f.settled(alice)
        let first = certificate(f.outcome(alice))
        #expect(first?.checkedAt == EpochMillis(100))
        f.clock.set(EpochMillis(500))
        guard let first, case .verified(let verified) = await f.core.verify([first]) else {
            Issue.record("再確認は成立するはず")
            return
        }
        #expect(verified.map(\.checkedAt) == [EpochMillis(500)])
    }

    @Test func 再確認は全観測の後の共通の時刻で全員の期限を判定する() async {
        let f = Fixture(now: 100)
        for who in [alice, bob] { f.service.set(who, .held(expiresAt: EpochMillis(1_000))) }
        for who in [alice, bob] { _ = f.core.request(participant: who, kind: kind) }
        await f.settled(alice, bob)
        let certificates = [alice, bob].compactMap { certificate(f.outcome($0)) }
        let clock = f.clock
        f.service.onFetch { if $0 == bob { clock.set(EpochMillis(1_000)) } }
        guard case .failed(.expired) = await f.core.verify(certificates) else {
            Issue.record("全観測の後の時刻で期限切れと判定するはず")
            return
        }
    }

    @Test func 再確認は現在の観測が未取得なら拒否し観測できなければ確認不能とする() async {
        let f = Fixture(now: 100)
        f.service.set(alice, .held(expiresAt: EpochMillis(1_000)))
        _ = f.core.request(participant: alice, kind: kind)
        await f.settled(alice)
        guard let issued = certificate(f.outcome(alice)) else { Issue.record("発行されるはず"); return }
        f.service.set(alice, .notHeld)
        guard case .failed(.notHeld) = await f.core.verify([issued]) else { Issue.record("未取得で拒否するはず"); return }
        f.service.set(alice, .unavailable)
        guard case .failed(.unconfirmed) = await f.core.verify([issued]) else { Issue.record("確認不能のはず"); return }
        guard case .failed(.targetMismatch) = await f.core.verify([issued, issued]) else {
            Issue.record("同じ対象の重複は対象の不一致のはず")
            return
        }
    }

    @Test func 再確認が返す証明書は全員が同じ判定時刻である() async {
        let f = Fixture()
        for who in [alice, bob] { f.service.set(who, .held(expiresAt: EpochMillis(5_000))) }
        for who in [alice, bob] { _ = f.core.request(participant: who, kind: kind) }
        await f.settled(alice, bob)
        let issued = [alice, bob].compactMap { certificate(f.outcome($0)) }
        f.clock.set(EpochMillis(2_000))
        guard case .verified(let verified) = await f.core.verify(issued) else { Issue.record("再確認は成立するはず"); return }
        #expect(Set(verified.map(\.checkedAt)) == [EpochMillis(2_000)])
        #expect(verified.count == 2)
    }

    @Test func 購読は現在の状態から始まり確認の進行を順に受け取る() async {
        let f = Fixture()
        f.service.set(alice, .held(expiresAt: EpochMillis(9_000)))
        var iterator = f.core.snapshots().makeAsyncIterator()
        #expect(await iterator.next()?.statuses.isEmpty == true)
        _ = f.core.request(participant: alice, kind: kind)
        await f.settled(alice)
    }
}
