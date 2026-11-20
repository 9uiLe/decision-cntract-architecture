// EXPECT: ARGUMENT_TYPE_MISMATCH
package dca.reject

import dca.core.ParticipantId
import dca.core.ReservationCore

fun submitBeforeConfirm(core: ReservationCore) {
    val unconfirmed = listOf(ParticipantId("a"))
    core.submit(unconfirmed) // VIOLATION
}
