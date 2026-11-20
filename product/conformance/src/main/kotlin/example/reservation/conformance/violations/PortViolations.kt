package example.reservation.conformance.violations

import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInPort
import example.eligibility.core.EligibilityPort
import example.eligibility.core.QualificationKind
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.RequestId
import example.reservation.core.ReservationId
import example.reservation.core.ReservationPort

suspend fun callPortOutsideCore(port: ReservationPort) {
    port.create(RequestId("q"), SessionId("s"), listOf(ParticipantId("p"))) // EXPECT: ReservationPortCall
    port.lookup(RequestId("q")) // EXPECT: ReservationPortCall
    port.cancel(ReservationId("r")) // EXPECT: ReservationPortCall
}

suspend fun callResourcePortsOutsideOwners(eligibility: EligibilityPort, checkIn: CheckInPort) {
    eligibility.fetch(ParticipantId("p"), QualificationKind("k")) // EXPECT: EligibilityPortCall
    checkIn.create(CheckInAttemptId("a"), ReservationId("r"), ParticipantId("p")) // EXPECT: CheckInPortCall
    checkIn.lookup(CheckInAttemptId("a")) // EXPECT: CheckInPortCall
}

suspend fun callUsageOutsideCore(port: ReservationPort) {
    port.usage(ReservationId("r")) // EXPECT: ReservationPortCall
}

fun portOperationReference(port: ReservationPort) = port::cancel // EXPECT: ReservationPortCall
