package example.detekt.reservation

import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val PORTS = """
package example.reservation.ports
interface ReservationPort {
    suspend fun create(request: String)
    suspend fun lookup(request: String)
    suspend fun cancel(reservation: String)
}
"""

private const val ADAPTER = """
package example.adapter
import example.reservation.ports.ReservationPort
class Wired : ReservationPort {
    override suspend fun create(request: String) = Unit
    override suspend fun lookup(request: String) = Unit
    override suspend fun cancel(reservation: String) = Unit
}
"""

private fun lint(code: String) =
    ReservationPortCall(TestConfig()).lintWithContext(environment, code, PORTS, ADAPTER)

class ReservationPortCallTest {
    @Test
    fun `Core 以外からの create・lookup・cancel を呼び出しの行で報告する`() {
        val findings = lint(
            """
            package example.app.composition
            import example.reservation.ports.ReservationPort
            suspend fun misuse(port: ReservationPort) {
                port.create("q")
                port.lookup("q")
                port.cancel("r")
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4, 5, 6), findings.map { it.location.source.line })
    }

    @Test
    fun `実装の型を通した呼び出しとメンバー参照も報告する`() {
        val findings = lint(
            """
            package example.ui
            import example.adapter.Wired
            import example.reservation.ports.ReservationPort
            suspend fun viaImplementation(wired: Wired) = wired.create("q")
            fun viaReference(port: ReservationPort) = port::lookup
            """.trimIndent(),
        )
        assertEquals(listOf(4, 5), findings.map { it.location.source.line })
    }

    @Test
    fun `Core のパッケージとその配下からの呼び出しは許可する`() {
        for (pkg in listOf("example.reservation.core", "example.reservation.core.internal")) {
            val findings = lint(
                """
                package $pkg
                import example.reservation.ports.ReservationPort
                suspend fun legitimate(port: ReservationPort) = port.create("q")
                """.trimIndent(),
            )
            assertTrue(findings.isEmpty(), pkg)
        }
    }

    @Test
    fun `名前の先頭が同じだけの別のパッケージは許可しない`() {
        val findings = lint(
            """
            package example.reservation.coreExtra
            import example.reservation.ports.ReservationPort
            suspend fun misuse(port: ReservationPort) = port.create("q")
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }
}
