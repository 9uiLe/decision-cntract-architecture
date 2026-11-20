package example.detekt.reservation

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.config
import dev.detekt.psi.FunctionMatcher
import example.detekt.dca.CallerRestriction

// 汎用の呼び出し元制限に、予約の方針を既定値として与える。Port への要求は所有する Core だけが出し、
// 要求回数と結果の解釈を一か所に保つ。
class ReservationPortCall(config: Config) :
    CallerRestriction(
        config,
        "ReservationPort の create・lookup・cancel・usage は予約の Core だけが呼び出す。",
    ) {

    @Configuration("呼び出しを制限する ReservationPort の操作。")
    override val restrictedCallables: List<FunctionMatcher> by config(
        listOf(
            "example.reservation.core.ReservationPort.create",
            "example.reservation.core.ReservationPort.lookup",
            "example.reservation.core.ReservationPort.cancel",
            "example.reservation.core.ReservationPort.usage",
        ),
    ) { signatures -> signatures.map(FunctionMatcher::fromFunctionSignature) }

    @Configuration("呼び出しを許可するパッケージ。")
    override val allowedCallerPackages: List<String> by config(listOf("example.reservation.core"))
}
