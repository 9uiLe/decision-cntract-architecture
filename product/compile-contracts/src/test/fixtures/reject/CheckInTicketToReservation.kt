package contracts.reject

import example.checkin.core.CheckInStatusTicket
import example.reservation.core.ReservationCore

fun lookUpReservationWithCheckInTicket(core: ReservationCore, checkInTicket: CheckInStatusTicket) =
    core.checkStatus(checkInTicket) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
