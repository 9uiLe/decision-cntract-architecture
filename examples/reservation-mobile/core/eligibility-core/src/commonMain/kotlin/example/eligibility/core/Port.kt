package example.eligibility.core

import example.kernel.DecisionResult
import example.kernel.EpochMillis
import example.kernel.ParticipantId

data class QualificationKind(val id: String, val name: String = id)

// サービスが返す事実だけを表す。期限を過ぎているかは判断せず、eligibility-core が判定する。
@DecisionResult
sealed interface QualificationObservation {
    data class Held(val expiresAt: EpochMillis) : QualificationObservation
    data object NotHeld : QualificationObservation
    data object Unavailable : QualificationObservation
}

interface EligibilityPort {
    suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation
}
