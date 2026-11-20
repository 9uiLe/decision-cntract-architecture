package contracts.reject

import example.reservation.core.AdmissionLookup
import example.reservation.core.ReservationAdmission

fun asCurrent(record: ReservationAdmission) = AdmissionLookup.Valid(record) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
