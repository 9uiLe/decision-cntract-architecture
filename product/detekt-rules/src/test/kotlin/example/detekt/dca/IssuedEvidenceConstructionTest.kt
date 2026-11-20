package example.detekt.dca

import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val MARKER = """
package sample.kernel
@Target(AnnotationTarget.CLASS)
annotation class Issued
"""

private fun lint(code: String) =
    IssuedEvidenceConstruction(TestConfig("issuedAnnotations" to listOf("sample.kernel.Issued")))
        .lintWithContext(environment, code, MARKER)

class IssuedEvidenceConstructionTest {
    @Test
    fun `internal のコンストラクタで宣言した発行型は報告しない`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            class Token internal constructor(val id: String)
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `公開のコンストラクタは、コンストラクタの行で報告する`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            class Token(val id: String)
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `コンストラクタを宣言しない発行型は、公開の既定のコンストラクタが残るので報告する`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            class Token
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `公開の二次コンストラクタも報告する`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            class Token internal constructor(val id: String) {
                constructor() : this("default")
            }
            """.trimIndent(),
        )
        assertEquals(listOf(5), findings.map { it.location.source.line })
    }

    @Test
    fun `data class は copy で付け替えられるので報告する`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            data class Token internal constructor(val id: String)
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `継承できる発行型は、外で派生型を作れるので報告する`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            open class Token internal constructor(val id: String)
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `sealed の基底型と、internal のコンストラクタを持つ派生型は報告しない`() {
        val findings = lint(
            """
            package sample.core
            import sample.kernel.Issued
            @Issued
            sealed class Token {
                @Issued
                class Plain internal constructor(val id: String) : Token()
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `注釈のない型は、公開のコンストラクタでも対象にしない`() {
        val findings = lint(
            """
            package sample.core
            data class Plain(val id: String)
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
