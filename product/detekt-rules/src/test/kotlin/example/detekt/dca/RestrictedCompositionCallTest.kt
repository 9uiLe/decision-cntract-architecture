package example.detekt.dca

import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val ASSEMBLY = """
package sample.core
class Engine internal constructor(val name: String)
fun createEngine(name: String): Engine = Engine(name)
fun createEngine(count: Int): Engine = Engine(count.toString())
interface Gateway { fun send(message: String) }
class Peer { fun send(message: String) = Unit }
class Wired : Gateway { override fun send(message: String) = Unit }
class Device(val id: String)
"""

private const val OTHER = """
package sample.other
fun createEngine(name: String): String = name
"""

private fun lint(callables: List<String>, allowed: List<String>, code: String) =
    RestrictedCompositionCall(TestConfig("restrictedCallables" to callables, "allowedCallerPackages" to allowed))
        .lintWithContext(environment, code, ASSEMBLY, OTHER)

class RestrictedCompositionCallTest {
    private val create = listOf("sample.core.createEngine")
    private val composition = listOf("sample.app.composition")

    @Test
    fun `許可外のパッケージからの呼び出しを、呼び出しの行で報告する`() {
        val findings = lint(
            create, composition,
            """
            package sample.app.ui
            import sample.core.createEngine
            fun build() = createEngine("x")
            """.trimIndent(),
        )
        assertEquals(listOf(3), findings.map { it.location.source.line })
    }

    @Test
    fun `許可したパッケージとその配下は呼び出せる`() {
        for (pkg in listOf("sample.app.composition", "sample.app.composition.debug")) {
            val findings = lint(
                create, composition,
                """
                package $pkg
                import sample.core.createEngine
                fun build() = createEngine("x")
                """.trimIndent(),
            )
            assertTrue(findings.isEmpty(), pkg)
        }
    }

    @Test
    fun `名前の先頭が同じだけの別のパッケージは許可しない`() {
        val findings = lint(
            create, composition,
            """
            package sample.app.compositionExtra
            import sample.core.createEngine
            fun build() = createEngine("x")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `呼び出し可能参照で取得しても報告する`() {
        val findings = lint(
            listOf("sample.core.createEngine(kotlin.String)"), composition,
            """
            package sample.app.ui
            import sample.core.createEngine
            val factory: (String) -> Any = ::createEngine
            """.trimIndent(),
        )
        assertEquals(listOf(3), findings.map { it.location.source.line })
    }

    @Test
    fun `import の別名経由の呼び出しも報告する`() {
        val findings = lint(
            create, composition,
            """
            package sample.app.ui
            import sample.core.createEngine as make
            fun build() = make("x")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `完全修飾名で呼び出しても報告する`() {
        val findings = lint(
            create, composition,
            """
            package sample.app.ui
            fun build() = sample.core.createEngine("x")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `同名の別の関数は報告しない`() {
        val findings = lint(
            create, composition,
            """
            package sample.app.ui
            import sample.other.createEngine
            fun build() = createEngine("x")
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `引数型を含むシグネチャで、同名のオーバーロードを区別する`() {
        val findings = lint(
            listOf("sample.core.createEngine(kotlin.Int)"), composition,
            """
            package sample.app.ui
            import sample.core.createEngine
            fun build() = createEngine("x")
            fun count() = createEngine(1)
            """.trimIndent(),
        )
        assertEquals(listOf(4), findings.map { it.location.source.line })
    }

    @Test
    fun `インターフェースのメンバーは、実装の型を通した呼び出しも報告する`() {
        val findings = lint(
            listOf("sample.core.Gateway.send"), composition,
            """
            package sample.app.ui
            import sample.core.Gateway
            import sample.core.Peer
            import sample.core.Wired
            fun viaInterface(gateway: Gateway) = gateway.send("x")
            fun viaImplementation(wired: Wired) = wired.send("x")
            fun viaReference(gateway: Gateway) = gateway::send
            fun unrelated(peer: Peer) = peer.send("x")
            """.trimIndent(),
        )
        assertEquals(listOf(5, 6, 7), findings.map { it.location.source.line })
    }

    @Test
    fun `コンストラクタの呼び出しを制限できる`() {
        val findings = lint(
            listOf("sample.core.Device.<init>"), composition,
            """
            package sample.app.ui
            import sample.core.Device
            fun build() = Device("x")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `許可するパッケージを空にすると、どこからの呼び出しも報告する`() {
        val findings = lint(
            create, emptyList(),
            """
            package sample.app.composition
            import sample.core.createEngine
            fun build() = createEngine("x")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }
}
