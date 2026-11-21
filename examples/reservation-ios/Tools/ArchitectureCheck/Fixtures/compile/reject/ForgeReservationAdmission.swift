// EXPECT: is inaccessible due to 'internal' protection level
import Kernel
import ReservationCore

func forge() -> ReservationAdmission {
    ReservationAdmission(
        reservation: ReservationId("r"), participant: ParticipantId("alice"), session: SessionId("s"),
        start: EpochMillis(0), basis: .withoutQualification
    )
}
