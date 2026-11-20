package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.config
import dev.detekt.psi.FunctionMatcher

class RestrictedCompositionCall(config: Config) :
    CallerRestriction(
        config,
        "組み立て用の関数は、許可したパッケージの配下からだけ呼び出す。",
    ) {

    @Configuration(
        "呼び出しを制限する関数。完全修飾名、引数型を含むシグネチャ、コンストラクタの <init> で指定する。",
    )
    override val restrictedCallables: List<FunctionMatcher> by config(emptyList<String>()) { signatures ->
        signatures.map(FunctionMatcher::fromFunctionSignature)
    }

    @Configuration("呼び出しを許可するパッケージ。そのパッケージとその配下を許可し、名前の先頭が同じ別のパッケージは許可しない。")
    override val allowedCallerPackages: List<String> by config(emptyList<String>())
}
