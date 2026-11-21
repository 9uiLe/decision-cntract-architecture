package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

class BoundActionCache(config: Config) :
    Rule(
        config,
        "束ねた操作を受け取る関数は、操作そのものをキーにしない限り、値をキャッシュしない。",
    ),
    RequiresAnalysisApi {

    @Configuration("束ねた操作の型に付ける注釈。完全修飾名で指定する。")
    private val boundActionAnnotations: List<String> by config(emptyList<String>())

    @Configuration("値を保持する呼び出し。完全修飾名で指定する。")
    private val cachingCalls: List<String> by config(emptyList<String>())

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val bound = function.valueParameters.filter(::isBound).mapNotNull { it.name }.toSet()
        if (bound.isEmpty()) return
        function.bodyExpression?.collectDescendantsOfType<KtCallExpression>().orEmpty().forEach { call ->
            val callee = calleeName(call) ?: return@forEach
            if (callee !in cachingCalls) return@forEach
            val keys = call.valueArguments.filterNot { it.getArgumentExpression() is org.jetbrains.kotlin.psi.KtLambdaExpression }
            val keyed = keys.any { (it.getArgumentExpression() as? KtNameReferenceExpression)?.getReferencedName() in bound }
            if (!keyed) {
                report(
                    Finding(
                        Entity.from(call),
                        "$callee は受け取った操作をキーにしていません。券か実行先が変わった更新の後に、古い操作が残ります。",
                    ),
                )
            }
        }
    }

    private fun isBound(parameter: KtParameter): Boolean = analyze(parameter) {
        parameter.symbol.returnType.symbol?.annotations?.any { it.classId?.asFqNameString() in boundActionAnnotations } == true
    }

    private fun calleeName(call: KtCallExpression): String? = analyze(call) {
        call.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()
            ?.partiallyAppliedSymbol?.symbol?.callableId?.asSingleFqName()?.asString()
    }
}
