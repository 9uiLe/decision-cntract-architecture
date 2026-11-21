// EXPECT: is inaccessible due to 'internal' protection level
import ReservationCore

func forge() -> CancelTicket {
    CancelTicket(reservation: ReservationId("r"))
}
