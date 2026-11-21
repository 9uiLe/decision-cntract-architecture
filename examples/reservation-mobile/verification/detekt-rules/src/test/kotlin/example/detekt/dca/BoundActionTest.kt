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
annotation class Bound
"""

private const val RUNTIME = """
package sample.runtime
fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()
fun <T> other(vararg keys: Any?, calculation: () -> T): T = calculation()
"""

private val equality = { code: String ->
    BoundActionEquality(TestConfig("boundActionAnnotations" to listOf("sample.kernel.Bound")))
        .lintWithContext(environment, code, MARKER)
}

private val cache = { code: String ->
    BoundActionCache(
        TestConfig(
            "boundActionAnnotations" to listOf("sample.kernel.Bound"),
            "cachingCalls" to listOf("sample.runtime.remember"),
        ),
    ).lintWithContext(environment, code, MARKER, RUNTIME)
}

class BoundActionEqualityTest {
    @Test
    fun `構成要素を主コンストラクタに持つ data class は報告しない`() {
        val findings = equality(
            """
            package sample.ui
            import sample.kernel.Bound
            @Bound
            data class Retry(val endpoint: Any, val ticket: Any) { fun run(): Int = 1 }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `data class でない束ねた型は宣言の行で報告する`() {
        val findings = equality(
            """
            package sample.ui
            import sample.kernel.Bound
            @Bound
            class Retry(val endpoint: Any, val ticket: Any)
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `equals の手書きと本体の状態は、それぞれの行で報告する`() {
        val findings = equality(
            """
            package sample.ui
            import sample.kernel.Bound
            @Bound
            data class Retry(val endpoint: Any, val ticket: Any) {
                var cached: Any? = null
                override fun equals(other: Any?): Boolean = other is Retry && other.endpoint === endpoint
                override fun hashCode(): Int = endpoint.hashCode()
            }
            """.trimIndent(),
        )
        assertEquals(listOf(5, 6, 7), findings.map { it.location.source.line })
    }

    @Test
    fun `主コンストラクタの var は、その引数の行で報告する`() {
        val findings = equality(
            """
            package sample.ui
            import sample.kernel.Bound
            @Bound
            data class Retry(
                val endpoint: Any,
                var ticket: Any,
            )
            """.trimIndent(),
        )
        assertEquals(listOf(6), findings.map { it.location.source.line })
    }

    @Test
    fun `注釈のない型は対象にしない`() {
        val findings = equality(
            """
            package sample.ui
            class Plain(val endpoint: Any) { override fun equals(other: Any?): Boolean = true }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}

class BoundActionCacheTest {
    private val declaration = """
        package sample.ui
        import sample.kernel.Bound
        import sample.runtime.remember
        import sample.runtime.other
        @Bound
        data class Retry(val endpoint: Any, val ticket: Any)
    """.trimIndent()

    @Test
    fun `操作そのものをキーにした保持と、保持しない関数は報告しない`() {
        val findings = cache(
            """
            $declaration
            fun keyed(action: Retry): Any = remember(action) { action.ticket }
            fun plain(action: Retry): Any = action.ticket
            fun unrelated(text: String): Any = remember { text }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `キーなしの保持と、操作の一部だけをキーにした保持は、呼び出しの行で報告する`() {
        val findings = cache(
            """
            $declaration
            fun noKey(action: Retry): Any = remember { action }
            fun partial(action: Retry): Any = remember(action.endpoint) { action }
            fun unrelatedKey(action: Retry, label: String): Any = remember(label) { action }
            """.trimIndent(),
        )
        assertEquals(listOf(7, 8, 9), findings.map { it.location.source.line })
    }

    @Test
    fun `対象でない保持の呼び出しは報告しない`() {
        val findings = cache(
            """
            $declaration
            fun other(action: Retry): Any = other { action }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
