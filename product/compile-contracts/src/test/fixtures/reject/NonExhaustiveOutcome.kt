package contracts.reject

import example.eligibility.core.EligibilityOutcome

fun describe(outcome: EligibilityOutcome): String =
    when (outcome) { // VIOLATION [NO_ELSE_IN_WHEN]
        is EligibilityOutcome.Eligible -> "eligible"
        EligibilityOutcome.NotHeld -> "not held"
    }
