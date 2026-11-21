package example.detekt.dca

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class DecisionContractRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("decision-contract")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::ExhaustiveDecisionWhen,
            ::RestrictedCompositionCall,
            ::IssuedEvidenceConstruction,
            ::RequiredEvidenceParameter,
            ::BoundActionEquality,
            ::BoundActionCache,
        ),
    )
}
