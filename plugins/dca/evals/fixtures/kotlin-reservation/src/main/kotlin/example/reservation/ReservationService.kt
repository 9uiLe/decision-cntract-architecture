package example.reservation

data class ReservationRequest(val sessionId: String, val participants: Int)

data class ReservationResponse(val accepted: Boolean)

interface ReservationGateway {
    fun create(request: ReservationRequest): ReservationResponse?
}

class ReservationService(private val gateway: ReservationGateway) {
    fun reserve(request: ReservationRequest): Boolean {
        val response = gateway.create(request)
        if (response == null) {
            return gateway.create(request)?.accepted ?: false
        }
        return response.accepted
    }
}
