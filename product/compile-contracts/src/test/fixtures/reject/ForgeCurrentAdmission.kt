package contracts.reject

import example.kernel.EpochMillis
import example.reservation.core.CurrentAdmission
import example.reservation.core.ReservationAdmission

fun forge(record: ReservationAdmission) = CurrentAdmission(record, EpochMillis(0)) // VIOLATION [INVISIBLE_REFERENCE]
