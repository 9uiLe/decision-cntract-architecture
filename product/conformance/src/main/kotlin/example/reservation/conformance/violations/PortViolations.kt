package example.reservation.conformance.violations

import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId
import example.reservation.ports.ReservationPort
import example.reservation.ports.SessionId

suspend fun callPortOutsideCore(port: ReservationPort) {
    port.create(RequestId("q"), SessionId("s"), listOf(ParticipantId("p"))) // EXPECT: ReservationPortCall
    port.lookup(RequestId("q")) // EXPECT: ReservationPortCall
    port.cancel(ReservationId("r")) // EXPECT: ReservationPortCall
}

fun portOperationReference(port: ReservationPort) = port::cancel // EXPECT: ReservationPortCall
