package contracts.reject

import example.reservation.core.ReservationCore
import example.reservation.core.Workshop

fun submitWithoutConfirmation(core: ReservationCore, workshop: Workshop) = core.submit(workshop) // VIOLATION [ARGUMENT_TYPE_MISMATCH]
