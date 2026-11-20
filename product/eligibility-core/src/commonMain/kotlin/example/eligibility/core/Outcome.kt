package example.eligibility.core

import example.kernel.ParticipantId

data class EligibilityKey(val participant: ParticipantId, val kind: QualificationKind)

sealed interface EligibilityOutcome {
    class Eligible(val certificate: EligibilityCertificate) : EligibilityOutcome
    data object NotHeld : EligibilityOutcome
    data object Expired : EligibilityOutcome
    data object Unknown : EligibilityOutcome
}

sealed interface EligibilityStatus {
    data object NotChecked : EligibilityStatus
    data object Checking : EligibilityStatus
    class Settled(val outcome: EligibilityOutcome) : EligibilityStatus
}

enum class EligibilityRequestRejection { Busy }

sealed interface EligibilityRequestAcceptance {
    data object Accepted : EligibilityRequestAcceptance
    data class Rejected(val reason: EligibilityRequestRejection) : EligibilityRequestAcceptance
}

enum class ReverificationFailure { NotHeld, Expired, Unconfirmed, TargetMismatch }

// 送信の可否に必要な結果だけを持つ。失敗の理由が増えても、受け取る側の分岐は増えない。
sealed interface Reverification {
    class Verified(val certificates: List<EligibilityCertificate>) : Reverification
    class Failed(val reason: ReverificationFailure) : Reverification
}

data class EligibilitySnapshot(val statuses: Map<EligibilityKey, EligibilityStatus>)
