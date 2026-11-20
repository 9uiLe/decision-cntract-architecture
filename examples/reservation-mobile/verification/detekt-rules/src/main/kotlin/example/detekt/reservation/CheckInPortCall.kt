package example.detekt.reservation

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.config
import dev.detekt.psi.FunctionMatcher
import example.detekt.dca.CallerRestriction

// 受付サービスへの要求は来場受付の Core だけが出す。要求の回数と結果の解釈を一か所に保つ。
class CheckInPortCall(config: Config) :
    CallerRestriction(
        config,
        "CheckInPort の create・lookup は来場受付の Core だけが呼び出す。",
    ) {

    @Configuration("呼び出しを制限する CheckInPort の操作。")
    override val restrictedCallables: List<FunctionMatcher> by config(
        listOf(
            "example.checkin.core.CheckInPort.create",
            "example.checkin.core.CheckInPort.lookup",
        ),
    ) { signatures -> signatures.map(FunctionMatcher::fromFunctionSignature) }

    @Configuration("呼び出しを許可するパッケージ。")
    override val allowedCallerPackages: List<String> by config(listOf("example.checkin.core"))
}
