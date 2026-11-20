package contracts.reject

import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationId

fun forge() = ReservationAdmission.Open(ReservationId("r"), ParticipantId("p"), SessionId("s"), EpochMillis(0)) // VIOLATION [INVISIBLE_REFERENCE]
