package example.detekt.reservation

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import example.detekt.dca.CallerRestriction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val ELIGIBILITY_PORT = """
package example.eligibility.core
interface EligibilityPort { suspend fun fetch(participant: String) }
"""

private const val CHECK_IN_PORT = """
package example.checkin.core
interface CheckInPort {
    suspend fun create(attempt: String)
    suspend fun lookup(attempt: String)
}
"""

private fun lint(rule: (Config) -> CallerRestriction, code: String) =
    rule(TestConfig()).lintWithContext(environment, code, ELIGIBILITY_PORT, CHECK_IN_PORT)

class OwnedPortCallTest {
    @Test
    fun `資格サービスの照会は、予約の Core や来場受付の Core からも報告する`() {
        for (pkg in listOf("example.reservation.core", "example.checkin.core", "example.app.composition")) {
            val findings = lint(
                ::EligibilityPortCall,
                """
                package $pkg
                import example.eligibility.core.EligibilityPort
                suspend fun misuse(port: EligibilityPort) = port.fetch("p")
                """.trimIndent(),
            )
            assertEquals(listOf(3), findings.map { it.location.source.line }, pkg)
        }
    }

    @Test
    fun `資格サービスの照会は、参加資格の Core のパッケージからは許可する`() {
        val findings = lint(
            ::EligibilityPortCall,
            """
            package example.eligibility.core
            import example.eligibility.core.EligibilityPort
            suspend fun legitimate(port: EligibilityPort) = port.fetch("p")
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `受付サービスへの要求は、来場受付の Core 以外から報告し、Core からは許可する`() {
        val misuse = lint(
            ::CheckInPortCall,
            """
            package example.reservation.core
            import example.checkin.core.CheckInPort
            suspend fun misuse(port: CheckInPort) {
                port.create("a")
                port.lookup("a")
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4, 5), misuse.map { it.location.source.line })
        val legitimate = lint(
            ::CheckInPortCall,
            """
            package example.checkin.core
            import example.checkin.core.CheckInPort
            suspend fun legitimate(port: CheckInPort) = port.create("a")
            """.trimIndent(),
        )
        assertTrue(legitimate.isEmpty())
    }
}
