// EXPECT: ARGUMENT_TYPE_MISMATCH
package dca.reject

import dca.core.Outcome
import dca.core.ReservationCore

fun retryFromUnknown(core: ReservationCore, unknown: Outcome.Unknown) {
    core.retry(unknown.check) // VIOLATION
}
