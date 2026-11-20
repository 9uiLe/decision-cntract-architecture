package example.detekt.reservation

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.config
import dev.detekt.psi.FunctionMatcher
import example.detekt.dca.CallerRestriction

// 資格サービスへの照会は参加資格の Core だけが出す。予約や来場受付が直接呼ぶと、資格の判断が複製される。
class EligibilityPortCall(config: Config) :
    CallerRestriction(
        config,
        "EligibilityPort の fetch は参加資格の Core だけが呼び出す。",
    ) {

    @Configuration("呼び出しを制限する EligibilityPort の操作。")
    override val restrictedCallables: List<FunctionMatcher> by config(
        listOf("example.eligibility.core.EligibilityPort.fetch"),
    ) { signatures -> signatures.map(FunctionMatcher::fromFunctionSignature) }

    @Configuration("呼び出しを許可するパッケージ。")
    override val allowedCallerPackages: List<String> by config(listOf("example.eligibility.core"))
}
