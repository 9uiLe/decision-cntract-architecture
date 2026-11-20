package contracts.reject

import example.reservation.core.ConfirmedDraft

fun copyIt(draft: ConfirmedDraft.Open) = draft.copy() // VIOLATION [UNRESOLVED_REFERENCE]
