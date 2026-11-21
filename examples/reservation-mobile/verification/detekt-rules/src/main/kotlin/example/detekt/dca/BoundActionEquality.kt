package example.detekt.dca

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class BoundActionEquality(config: Config) :
    Rule(
        config,
        "実行先と操作券を束ねた型は、構成要素すべてを比べる data class で宣言する。",
    ),
    RequiresAnalysisApi {

    @Configuration("束ねた操作の型に付ける注釈。完全修飾名で指定する。")
    private val boundActionAnnotations: List<String> by config(emptyList<String>())

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface() || klass.isEnum() || klass.isAnnotation() || klass.isSealed() || !isBound(klass)) return
        if (!klass.isData()) {
            report(Finding(Entity.atName(klass), "束ねた操作は data class で宣言してください。構成要素のどれかを比較から落とせます。"))
        }
        klass.primaryConstructorParameters.filter { it.isMutable }.forEach {
            report(Finding(Entity.from(it), "主コンストラクタの var は、同じ値のまま別の操作を作れます。val にしてください。"))
        }
        klass.body?.declarations.orEmpty().forEach { declaration ->
            when {
                declaration is KtNamedFunction && declaration.name in setOf("equals", "hashCode") ->
                    report(Finding(Entity.from(declaration), "equals と hashCode を手書きせず、data class の構成要素の比較に任せてください。"))
                declaration is KtProperty && (declaration.hasInitializer() || declaration.hasDelegate() || declaration.isVar) ->
                    report(Finding(Entity.from(declaration), "本体の状態は等価性に入りません。実行先と券は主コンストラクタで保持してください。"))
            }
        }
    }

    private fun isBound(klass: KtClass): Boolean = analyze(klass) {
        klass.classSymbol?.annotations?.any { it.classId?.asFqNameString() in boundActionAnnotations } == true
    }
}
