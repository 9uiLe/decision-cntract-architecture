// EXPECT: INVISIBLE_REFERENCE
package dca.reject

import dca.core.ConfirmedDraft
import dca.core.Workshop

fun forgeConfirmedDraft(workshop: Workshop): ConfirmedDraft =
    ConfirmedDraft(workshop, emptyList()) // VIOLATION
