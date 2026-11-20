package example.reservation.ports

sealed interface CreateObservation {
    data class Created(val reservation: ReservationId) : CreateObservation
    data object Full : CreateObservation
    data object NotCreated : CreateObservation
    data object NoResponse : CreateObservation
}

sealed interface LookupObservation {
    data class Found(val reservation: ReservationId) : LookupObservation
    data object NotCreated : LookupObservation
    data object NotFound : LookupObservation
    data object Failed : LookupObservation
}

sealed interface CancelObservation {
    data object Cancelled : CancelObservation
    data object Declined : CancelObservation
    data object NoResponse : CancelObservation
}

interface ReservationPort {
    suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation
    suspend fun lookup(request: RequestId): LookupObservation
    suspend fun cancel(reservation: ReservationId): CancelObservation
}
