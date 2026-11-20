package contracts.reject

import example.checkin.core.CheckInCore
import example.reservation.core.StatusCheckTicket

fun lookUpCheckInWithReservationTicket(core: CheckInCore, reservationTicket: StatusCheckTicket) =
    core.checkStatus(reservationTicket) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
