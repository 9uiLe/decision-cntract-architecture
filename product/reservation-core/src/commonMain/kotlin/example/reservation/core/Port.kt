package example.reservation.core

import example.kernel.DecisionResult
import example.kernel.ParticipantId
import example.kernel.SessionId

@DecisionResult
sealed interface CreateObservation {
    data class Created(val reservation: ReservationId) : CreateObservation
    data object Full : CreateObservation
    data object NotCreated : CreateObservation
    data object NoResponse : CreateObservation
}

@DecisionResult
sealed interface LookupObservation {
    data class Found(val reservation: ReservationId) : LookupObservation
    data object NotCreated : LookupObservation
    data object NotFound : LookupObservation
    data object Failed : LookupObservation
}

@DecisionResult
sealed interface CancelObservation {
    data object Cancelled : CancelObservation
    data object Declined : CancelObservation
    data object NoResponse : CancelObservation
}

// 予約の現在の利用可否は、取消の応答が失われた後でも記録だけでは分からない。サービスに現在の状態を尋ねる。
@DecisionResult
sealed interface UsageObservation {
    data object Active : UsageObservation
    data object Cancelled : UsageObservation
    data object NotFound : UsageObservation
    data object Failed : UsageObservation
}

interface ReservationPort {
    suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation
    suspend fun lookup(request: RequestId): LookupObservation
    suspend fun cancel(reservation: ReservationId): CancelObservation
    suspend fun usage(reservation: ReservationId): UsageObservation
}
