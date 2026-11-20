package example.detekt.reservation

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class ReservationContractRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("reservation-contract")

    override fun instance(): RuleSet = RuleSet(ruleSetId, listOf(::ReservationPortCall))
}
