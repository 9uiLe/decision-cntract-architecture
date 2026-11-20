package contracts.reject

import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInStatusTicket
import example.reservation.core.ReservationAdmission

fun forge(admission: ReservationAdmission) = CheckInStatusTicket(CheckInAttemptId("a"), admission) // VIOLATION [INVISIBLE_REFERENCE]
