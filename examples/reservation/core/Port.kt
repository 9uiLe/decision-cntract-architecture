package dca.core

sealed interface CreateObservation {
    data class Created(val reservation: Reservation) : CreateObservation
    data object Full : CreateObservation
    data object NotCreated : CreateObservation
    data object NoResponse : CreateObservation
}

sealed interface LookupObservation {
    data class Found(val reservation: Reservation) : LookupObservation
    data object NotCreated : LookupObservation
    data object NotFound : LookupObservation
    data object Failed : LookupObservation
}

interface ReservationPort {
    fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation
    fun lookup(request: RequestId): LookupObservation
}
