package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault

class IssuedEvidenceConstruction(config: Config) :
    Rule(
        config,
        "発行経路を制限する型は、外から構築・継承・複製できない形で宣言する。",
    ),
    RequiresAnalysisApi {

    @Configuration("発行経路を制限する型に付ける注釈。完全修飾名で指定する。")
    private val issuedAnnotations: List<String> by config(emptyList<String>())

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface() || klass.isEnum() || klass.isAnnotation() || !isIssued(klass)) return
        if (klass.isData()) {
            report(Finding(Entity.atName(klass), "data class は copy で別の内容へ付け替えられます。通常の class にしてください。"))
        }
        if (klass.isSealed()) return
        if (klass.hasModifier(KtTokens.OPEN_KEYWORD) || klass.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
            report(Finding(Entity.atName(klass), "継承できる型は、外で派生型を作れます。final か sealed にしてください。"))
        }
        val constructors = listOfNotNull(klass.primaryConstructor) + klass.secondaryConstructors
        if (constructors.isEmpty()) {
            report(Finding(Entity.atName(klass), "コンストラクタを宣言せず、公開の既定のコンストラクタを残しています。internal で宣言してください。"))
        }
        constructors.filter { it.visibilityModifierTypeOrDefault() == KtTokens.PUBLIC_KEYWORD }.forEach {
            report(Finding(Entity.from(it), "公開のコンストラクタは、判断を経由せずに値を構築できます。internal にしてください。"))
        }
    }

    private fun isIssued(klass: KtClass): Boolean = analyze(klass) {
        klass.classSymbol?.annotations?.any { it.classId?.asFqNameString() in issuedAnnotations } == true
    }
}
