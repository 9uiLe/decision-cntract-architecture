// EXPECT: INVISIBLE_REFERENCE
package dca.reject

import dca.core.ConfirmedDraft
import dca.core.RetryTicket

fun forgeRetryTicket(draft: ConfirmedDraft): RetryTicket =
    RetryTicket(draft) // VIOLATION
