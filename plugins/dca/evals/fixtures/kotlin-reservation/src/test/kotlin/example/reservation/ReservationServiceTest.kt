package example.reservation

import kotlin.test.Test
import kotlin.test.assertTrue

class ReservationServiceTest {
    @Test
    fun acceptedResponseIsReported() {
        val gateway = object : ReservationGateway {
            override fun create(request: ReservationRequest) = ReservationResponse(accepted = true)
        }
        assertTrue(ReservationService(gateway).reserve(ReservationRequest("session-1", 2)))
    }
}
