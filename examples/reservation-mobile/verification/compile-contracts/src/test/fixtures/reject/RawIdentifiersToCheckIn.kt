package contracts.reject

import example.checkin.core.CheckInCore
import example.reservation.core.ReservationId

fun checkInWithRawIdentifier(core: CheckInCore) = core.checkIn(ReservationId("r")) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
