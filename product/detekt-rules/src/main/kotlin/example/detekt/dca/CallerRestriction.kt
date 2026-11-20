package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.psi.FunctionMatcher
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.util.asCallableReferenceExpression

// 呼び出し先は宣言ではなく解決された参照で判定する。許可するのは呼び出し元ファイルの package 宣言であり、
// 宣言の偽装は別の検査（モジュールの所有）で扱う。
abstract class CallerRestriction(config: Config, description: String) :
    Rule(config, description),
    RequiresAnalysisApi {

    protected abstract val restrictedCallables: List<FunctionMatcher>
    protected abstract val allowedCallerPackages: List<String>

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        check(expression)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        check(expression.callableReference)
    }

    private fun check(expression: KtExpression) {
        if (isAllowedCaller(expression.containingKtFile.packageFqName.asString())) return
        val restricted = analyze(expression) {
            val call = expression.resolveToCall() ?: expression.asCallableReferenceExpression()?.resolveToCall()
            val symbol = call?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol?.symbol
            symbol?.let { callee ->
                (sequenceOf(callee) + callee.allOverriddenSymbols)
                    .firstNotNullOfOrNull { candidate -> restrictedCallables.firstOrNull { it.match(candidate) } }
            }
        } ?: return
        report(
            Finding(
                Entity.from(expression),
                "$restricted の呼び出しは ${allowedCallerPackages.joinToString()} の配下に限られています。",
            ),
        )
    }

    private fun isAllowedCaller(callerPackage: String): Boolean =
        allowedCallerPackages.any { callerPackage == it || callerPackage.startsWith("$it.") }
}
