package contracts.reject

import example.kernel.ParticipantId
import example.reservation.core.ConfirmedDraft
import example.reservation.core.Workshop

fun forge(workshop: Workshop) = ConfirmedDraft.Open(workshop, listOf(ParticipantId("p"))) // VIOLATION [INVISIBLE_REFERENCE]
