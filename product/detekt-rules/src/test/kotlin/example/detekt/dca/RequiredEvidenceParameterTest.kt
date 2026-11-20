package example.detekt.dca

import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val DECLARATIONS = """
package sample.kernel
@Target(AnnotationTarget.CLASS)
annotation class Issued
"""

private const val TOKEN = """
package sample.core
import sample.kernel.Issued
@Issued
class Token internal constructor(val id: String)
typealias Pass = Token
"""

private fun lint(code: String) =
    RequiredEvidenceParameter(TestConfig("issuedAnnotations" to listOf("sample.kernel.Issued")))
        .lintWithContext(environment, code, DECLARATIONS, TOKEN)

class RequiredEvidenceParameterTest {
    @Test
    fun `必須の引数で受け取る公開の操作は報告しない`() {
        val findings = lint(
            """
            package sample.app
            import sample.core.Token
            class Gate { fun enter(token: Token): Boolean = token.id.isNotEmpty() }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `公開の操作の既定値付きの引数は、引数の行で報告する`() {
        val findings = lint(
            """
            package sample.app
            import sample.core.Token
            class Gate {
                fun enter(name: String, token: Token = Token("x")): Boolean = token.id.isNotEmpty()
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `公開の操作の null を許す引数は報告する`() {
        val findings = lint(
            """
            package sample.app
            import sample.core.Token
            class Gate {
                fun enter(token: Token?): Boolean = token != null
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `型エイリアス経由の引数でも報告する`() {
        val findings = lint(
            """
            package sample.app
            import sample.core.Pass
            fun enter(token: Pass? = null): Boolean = token != null
            """.trimIndent(),
        )
        assertEquals(listOf(3), findings.map { it.location.source.line })
    }

    @Test
    fun `internal と private の操作の省略可能な引数は対象にしない`() {
        val findings = lint(
            """
            package sample.app
            import sample.core.Token
            class Gate {
                internal fun inspect(token: Token? = null): Boolean = token != null
                private fun peek(token: Token?): Boolean = token != null
            }
            internal class Hidden {
                fun enter(token: Token? = null): Boolean = token != null
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `発行型でない型の既定値と null は許可する`() {
        val findings = lint(
            """
            package sample.app
            class Gate {
                fun enter(name: String? = null, count: Int = 0): Boolean = name != null && count > 0
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
