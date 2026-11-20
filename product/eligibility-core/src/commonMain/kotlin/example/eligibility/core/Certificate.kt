package example.eligibility.core

import example.kernel.EpochMillis
import example.kernel.IssuedEvidence
import example.kernel.ParticipantId

// 構築は eligibility-core だけが行う。確認した時点は記録として公開するが、期限は公開しない。
// 利用側は期限や確認時刻から有効性を判断せず、鮮度の再確認を EligibilityAuthority.verify に委ねる。
@IssuedEvidence
class EligibilityCertificate internal constructor(
    val participant: ParticipantId,
    val kind: QualificationKind,
    val checkedAt: EpochMillis,
    internal val expiresAt: EpochMillis,
)
