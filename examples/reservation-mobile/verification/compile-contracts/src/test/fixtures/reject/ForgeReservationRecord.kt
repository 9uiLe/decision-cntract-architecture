package contracts.reject

import example.reservation.core.Booking
import example.reservation.core.CancelTicket
import example.reservation.core.ReservationId
import example.reservation.core.ReservationRecord

fun forge(booking: Booking, ticket: CancelTicket) =
    ReservationRecord.Reserved(ReservationId("r"), booking, ticket, null, emptyList()) // VIOLATION [INVISIBLE_REFERENCE]
