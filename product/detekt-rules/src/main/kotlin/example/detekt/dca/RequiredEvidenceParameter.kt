package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault

class RequiredEvidenceParameter(config: Config) :
    Rule(
        config,
        "公開の操作が要求する確認済みの値は、既定値や null で省略できる引数にしない。",
    ),
    RequiresAnalysisApi {

    @Configuration("発行経路を制限する型に付ける注釈。完全修飾名で指定する。")
    private val issuedAnnotations: List<String> by config(emptyList<String>())

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val owner = parameter.ownerFunction as? KtDeclaration ?: return
        if (!isPublicOperation(owner)) return
        val (issued, nullable) = analyze(parameter) {
            val type = parameter.symbol.returnType
            val marked = type.symbol?.annotations?.any { it.classId?.asFqNameString() in issuedAnnotations } == true
            marked to type.isMarkedNullable
        }
        if (!issued) return
        if (parameter.hasDefaultValue()) {
            report(Finding(Entity.from(parameter), "確認済みの値の引数に既定値があります。呼び出し側が前提を省略できないようにしてください。"))
        } else if (nullable) {
            report(Finding(Entity.from(parameter), "確認済みの値の引数が null を許しています。前提を必須にしてください。"))
        }
    }

    private fun isPublicOperation(owner: KtDeclaration): Boolean {
        if ((owner as? KtNamedFunction)?.isLocal == true) return false
        if (owner.visibilityModifierTypeOrDefault() != KtTokens.PUBLIC_KEYWORD) return false
        return generateSequence(owner.parent) { it.parent }
            .filterIsInstance<KtClassOrObject>()
            .all { it.visibilityModifierTypeOrDefault() == KtTokens.PUBLIC_KEYWORD }
    }
}
