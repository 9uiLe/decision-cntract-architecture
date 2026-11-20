package contracts.reject

import example.checkin.core.CheckInCore
import example.reservation.core.CurrentAdmission

fun checkIn(core: CheckInCore, current: CurrentAdmission) = core.checkIn(current) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
