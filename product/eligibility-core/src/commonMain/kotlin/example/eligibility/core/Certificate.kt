package example.eligibility.core

import example.kernel.EpochMillis
import example.kernel.IssuedEvidence
import example.kernel.ParticipantId

// 構築は eligibility-core だけが行う。確認した時点は記録として公開するが、期限は公開しない。
// 期限は eligibility-core が判定する。利用側は checkedAt を再確認の呼び出し区間と照合する。
@IssuedEvidence
class EligibilityCertificate internal constructor(
    val participant: ParticipantId,
    val kind: QualificationKind,
    val checkedAt: EpochMillis,
    internal val expiresAt: EpochMillis,
)
