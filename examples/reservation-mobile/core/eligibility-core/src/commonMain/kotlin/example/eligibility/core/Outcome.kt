package example.eligibility.core

import example.kernel.DecisionResult
import example.kernel.ParticipantId
import example.kernel.frozen

data class EligibilityKey(val participant: ParticipantId, val kind: QualificationKind)

@DecisionResult
sealed interface EligibilityOutcome {
    class Eligible(val certificate: EligibilityCertificate) : EligibilityOutcome
    data object NotHeld : EligibilityOutcome
    data object Expired : EligibilityOutcome
    data object Unknown : EligibilityOutcome
}

@DecisionResult
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

@DecisionResult
enum class ReverificationFailure { NotHeld, Expired, Unconfirmed, TargetMismatch }

// 送信の可否に必要な結果だけを持つ。失敗の理由が増えても、受け取る側の分岐は増えない。
@DecisionResult
sealed interface Reverification {
    class Verified(certificates: List<EligibilityCertificate>) : Reverification {
        val certificates: List<EligibilityCertificate> = certificates.frozen()
    }
    class Failed(val reason: ReverificationFailure) : Reverification
}

data class EligibilitySnapshot(val statuses: Map<EligibilityKey, EligibilityStatus>)
