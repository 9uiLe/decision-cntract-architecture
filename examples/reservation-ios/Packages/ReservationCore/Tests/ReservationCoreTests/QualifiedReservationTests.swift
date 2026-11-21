import EligibilityCore
import Kernel
import KernelTestSupport
import ReservationCore
import Testing

private let farFuture = EpochMillis(start + 1_000 * hour)

private final class TransformingAuthority: EligibilityAuthority {
    private let base: EligibilityCore
    private let transform: @Sendable (Reverification) async -> Reverification

    init(_ base: EligibilityCore, transform: @escaping @Sendable (Reverification) async -> Reverification) {
        self.base = base
        self.transform = transform
    }

    var snapshot: EligibilitySnapshot { base.snapshot }

    func snapshots() -> AsyncStream<EligibilitySnapshot> { base.snapshots() }

    func status(participant: ParticipantId, kind: QualificationKind) -> EligibilityStatus {
        base.status(participant: participant, kind: kind)
    }

    func request(participant: ParticipantId, kind: QualificationKind) -> EligibilityRequestAcceptance {
        base.request(participant: participant, kind: kind)
    }

    func verify(_ certificates: [EligibilityCertificate]) async -> Reverification {
        await transform(base.verify(certificates))
    }
}

private func confirmedDraft(_ outcome: ConfirmOutcome) -> ConfirmedDraft? {
    guard case .confirmed(let draft) = outcome else { return nil }
    return draft
}

private func isEligibilityLost(_ reason: ReverificationFailure, _ request: ActiveRequest?) -> Bool {
    guard case .eligibilityLost(_, let actual)? = request else { return false }
    return actual == reason
}

@Suite(.timeLimit(.minutes(1)))
struct QualifiedReservationTests {
    @Test func 通常回は資格を確認せずに予約でき資格の照会は行わない() async {
        let f = Fixture()
        _ = await f.reserve(pottery)
        #expect(f.service.fetchCalls.count == 0)
    }

    @Test func 通常回の入口を要資格回に使うことも要資格の入口を通常回に使うこともできない() {
        let f = Fixture()
        _ = f.core.selectWorkshop(advanced.id)
        _ = f.core.toggleParticipant(alice)
        guard case .qualificationRequired = f.core.confirmWithoutQualification() else {
            Issue.record("要資格回は通常の確認を受け付けないはず")
            return
        }
        _ = f.core.selectWorkshop(pottery.id)
        _ = f.core.toggleParticipant(alice)
        guard case .qualificationNotRequired = f.core.confirmQualified() else {
            Issue.record("通常回は資格の確認を受け付けないはず")
            return
        }
        #expect(rejection(f.core.requestEligibility()) == .qualificationNotRequired)
    }

    @Test func 要資格回は全参加者の資格が有効なら予約でき作成要求は一度だけ送る() async {
        let f = Fixture()
        for who in [alice, bob] { f.service.set(who, .held(expiresAt: farFuture)) }
        guard let draft = confirmedDraft(await f.qualifiedDraft(who: [alice, bob])) else {
            Issue.record("全員の資格が有効なら確認済みになるはず")
            return
        }
        #expect(f.core.submit(draft) == .accepted)
        await f.settled { $0.reservations.count == 1 }
        #expect(f.port.createCalls.count == 1)
        guard case .reserved(let record)? = f.snapshot.reservations.first else { Issue.record("成立するはず"); return }
        #expect(record.booking.basis == .withQualification(advancedKind))
    }

    @Test func 一人でも未取得や期限切れや結果不明なら確認済みにならず作成要求を送らない() async {
        let observations: [QualificationObservation] = [.notHeld, .held(expiresAt: EpochMillis(start - hour)), .unavailable]
        for observation in observations {
            let f = Fixture()
            f.service.set(alice, .held(expiresAt: farFuture))
            f.service.set(bob, observation)
            guard case .eligibilityNotMet(let missing) = await f.qualifiedDraft(who: [alice, bob]) else {
                Issue.record("確認済みにならないはず: \(observation)")
                continue
            }
            #expect(missing == [bob])
            #expect(f.port.createCalls.count == 0)
        }
    }

    @Test func 結果不明は資格有効と推測せず再照会でそろっても予約作成は自動で始まらない() async {
        let f = Fixture()
        f.service.set(alice, .unavailable)
        guard case .eligibilityNotMet = await f.qualifiedDraft() else { Issue.record("確認済みにならないはず"); return }
        f.service.set(alice, .held(expiresAt: farFuture))
        guard case .accepted = f.core.requestEligibility() else { Issue.record("再照会は受け付けるはず"); return }
        _ = await firstValue(of: f.eligibility.snapshots()) { snapshot in
            guard case .settled(.eligible)? = snapshot.statuses[EligibilityKey(participant: alice, kind: advancedKind)] else {
                return false
            }
            return true
        }
        #expect(f.port.createCalls.count == 0)
        #expect(f.snapshot.request == nil)
    }

    @Test func 別の参加者の確認の流用や同じ人の確認の重複は人数分とみなさない() async {
        let f = Fixture(authority: { eligibility in
            TransformingAuthority(eligibility) { result in
                guard case .verified(let certificates) = result, let first = certificates.first else { return result }
                return .verified([first, first])
            }
        })
        for who in [alice, bob] { f.service.set(who, .held(expiresAt: farFuture)) }
        guard let draft = confirmedDraft(await f.qualifiedDraft(who: [alice, bob])) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(draft)
        await f.settled { isEligibilityLost(.targetMismatch, $0.request) }
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 確認後に参加者や開催回を変えると古い確認では予約できず現在の条件の確認が必要() async {
        let f = Fixture()
        for who in [alice, bob] { f.service.set(who, .held(expiresAt: farFuture)) }
        guard let draft = confirmedDraft(await f.qualifiedDraft(who: [alice])) else { Issue.record("確認済みのはず"); return }
        _ = f.core.toggleParticipant(bob)
        #expect(rejection(f.core.submit(draft)) == .stale)
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 確認後で送信前に有効期限を迎えると再確認で無効を判定して作成要求を送らない() async {
        let f = Fixture()
        f.service.set(alice, .held(expiresAt: EpochMillis(start - hour / 2)))
        guard let draft = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        f.clock.set(EpochMillis(start - hour / 2))
        _ = f.core.submit(draft)
        await f.settled { isEligibilityLost(.expired, $0.request) }
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 発行後に付与が取り消されても送信前の再確認が現在のサービスの観測で拒否する() async {
        let f = Fixture()
        f.service.set(alice, .held(expiresAt: farFuture))
        guard let draft = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        f.service.set(alice, .notHeld)
        _ = f.core.submit(draft)
        await f.settled { isEligibilityLost(.notHeld, $0.request) }
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 過去の再確認結果を再生する差し替えの資格実装は今回の呼び出し区間に入らないので拒否する() async {
        let recorded = LockedValue<Reverification?>(nil)
        let f = Fixture(authority: { eligibility in
            TransformingAuthority(eligibility) { result in
                recorded.withLock { stored -> Reverification in
                    if let stored { return stored }
                    stored = result
                    return result
                }
            }
        })
        f.service.set(alice, .held(expiresAt: farFuture))
        guard let first = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(first)
        await f.settled { $0.request == nil && $0.reservations.count == 1 }
        f.clock.advance(millis: 1)
        _ = f.core.selectWorkshop(pottery.id)
        guard let second = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(second)
        await f.settled { isEligibilityLost(.unconfirmed, $0.request) }
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 再試行で資格を満たさないと作成要求を送らず元の未成立は資格不成立と区別して残る() async {
        let f = Fixture()
        f.service.set(alice, .held(expiresAt: farFuture))
        f.port.onCreate { _ in .notCreated }
        guard let draft = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(draft)
        await f.settled { if case .notCreated? = $0.request { return true } else { return false } }
        guard case .notCreated(let retry, nil)? = f.snapshot.request else { Issue.record("未成立のはず"); return }
        f.service.set(alice, .notHeld)
        #expect(f.core.retry(retry) == .accepted)
        await f.settled { snapshot in
            guard case .notCreated(_, .eligibilityLost?)? = snapshot.request else { return false }
            return true
        }
        #expect(f.port.createCalls.count == 1)
    }

    @Test func 判定時点の後に送信期限を越えたときは資格が有効でも作成要求を送らない() async {
        let clockBox = LockedValue<ManualClock?>(nil)
        let f = Fixture(authority: { eligibility in
            TransformingAuthority(eligibility) { result in
                clockBox.withLock { $0 }?.set(EpochMillis(start))
                return result
            }
        })
        clockBox.withLock { $0 = f.clock }
        f.service.set(alice, .held(expiresAt: farFuture))
        guard let draft = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(draft)
        await f.settled { if case .registrationClosed? = $0.request { return true } else { return false } }
        #expect(f.port.createCalls.count == 0)
    }

    @Test func 成立後に資格だけが失効しても予約は維持され有効な予約の受付前提は有効のまま() async {
        let f = Fixture()
        f.service.set(alice, .held(expiresAt: EpochMillis(start - hour / 2)))
        guard let draft = confirmedDraft(await f.qualifiedDraft()) else { Issue.record("確認済みのはず"); return }
        _ = f.core.submit(draft)
        await f.settled { $0.reservations.count == 1 }
        f.clock.set(EpochMillis(start - hour / 4))
        guard case .valid(let current) = await f.core.admission(reservation: ReservationId("r-1"), participant: alice) else {
            Issue.record("資格の失効だけでは予約は無効にならないはず")
            return
        }
        #expect(current.record.basis == .withQualification(advancedKind))
    }
}
