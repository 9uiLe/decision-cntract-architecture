public import Kernel

// 差し替えられる契約にして、別人や重複の証明書を返す負の対照をテストできるようにする。
// 差し替えた実装は発行の責務を負わないため、利用する Core が証明書の対象と判定時刻（checkedAt）を自分で照合する。
// 型は古い証明書の再生を防がない。
public protocol EligibilityAuthority: Sendable {
    var snapshot: EligibilitySnapshot { get }

    func snapshots() -> AsyncStream<EligibilitySnapshot>

    func status(participant: ParticipantId, kind: QualificationKind) -> EligibilityStatus

    func request(participant: ParticipantId, kind: QualificationKind) -> EligibilityRequestAcceptance

    func verify(_ certificates: [EligibilityCertificate]) async -> Reverification
}
