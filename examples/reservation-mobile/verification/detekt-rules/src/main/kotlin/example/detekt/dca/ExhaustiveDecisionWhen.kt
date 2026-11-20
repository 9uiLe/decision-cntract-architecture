package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import dev.detekt.psi.FunctionMatcher
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtWhenExpression

class ExhaustiveDecisionWhen(config: Config) :
    Rule(
        config,
        "判断を要求する型に対する when は else を持たず、型に追加されたケースをコンパイルで検出する。",
    ),
    RequiresAnalysisApi {

    @Configuration("when の対象式が解決される型に付いていれば判断型とする注釈。完全修飾名で指定する。関数の指定に関係なく検査する。")
    private val decisionAnnotations: List<String> by config(emptyList<String>())

    @Configuration("注釈を付けられない型を判断型にするときの、when の対象式が解決される型。ネストした型を含め、完全修飾名で指定する。")
    private val decisionTypes: List<String> by config(emptyList<String>())

    @Configuration(
        "decisionTypes の型にだけ適用する関数の絞り込み。完全修飾名、または引数型を含むシグネチャで指定する。空のときはすべての関数を検査する。",
    )
    private val decisionFunctions: List<FunctionMatcher> by config(emptyList<String>()) { signatures ->
        signatures.map(FunctionMatcher::fromFunctionSignature)
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        val fallback = expression.entries.firstOrNull { it.isElse } ?: return
        val subject = expression.subjectExpression ?: return
        val (subjectType, annotated) = analyze(subject) {
            val symbol = subject.expressionType?.symbol
            val marked = symbol?.annotations?.any { it.classId?.asFqNameString() in decisionAnnotations } == true
            symbol?.classId?.asFqNameString() to marked
        }
        subjectType ?: return
        val decision = annotated || (subjectType in decisionTypes && isInDecisionFunction(expression))
        if (decision) {
            report(
                Finding(
                    Entity.from(fallback),
                    "$subjectType の when に else があります。ケースが追加されたときに判断を見直せるよう、すべてのケースを列挙してください。",
                ),
            )
        }
    }

    private fun isInDecisionFunction(expression: KtWhenExpression): Boolean {
        if (decisionFunctions.isEmpty()) return true
        return generateSequence<com.intellij.psi.PsiElement>(expression) { it.parent }
            .filterIsInstance<KtNamedFunction>()
            .any { function -> decisionFunctions.any { it.match(function, fullAnalysis = true) } }
    }
}
