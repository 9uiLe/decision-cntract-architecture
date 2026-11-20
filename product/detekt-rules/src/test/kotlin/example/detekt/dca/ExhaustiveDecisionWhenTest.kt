package example.detekt.dca

import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.location
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val environment = createEnvironment()

private const val OUTCOME = """
package sample.core
sealed interface Outcome {
    data object Done : Outcome
    data object Failed : Outcome
}
typealias Result = Outcome
"""

private const val OTHER_OUTCOME = """
package sample.other
sealed interface Outcome {
    data object Done : Outcome
}
"""

private fun rule(functions: List<String>) = ExhaustiveDecisionWhen(
    TestConfig("decisionTypes" to listOf("sample.core.Outcome"), "decisionFunctions" to functions),
)

private fun lint(functions: List<String>, code: String) =
    rule(functions).lintWithContext(environment, code, OUTCOME, OTHER_OUTCOME)

class ExhaustiveDecisionWhenTest {
    @Test
    fun `指定した関数で判断型の when に else があると、else の行を報告する`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome
            fun project(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertEquals(listOf(5), findings.map { it.location.source.line })
    }

    @Test
    fun `else のない網羅的な when は報告しない`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome
            fun project(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                Outcome.Failed -> "failed"
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `指定していない関数の else は報告しない`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome
            fun summarize(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `同じ関数名でもパッケージが異なれば対象にしない`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.elsewhere
            import sample.core.Outcome
            fun project(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `判断型でない対象式の else は外部コードの解釈として許可する`() {
        val findings = lint(
            listOf("sample.app.parse"),
            """
            package sample.app
            fun parse(code: String): Int = when (code) {
                "ok" -> 1
                else -> 0
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `同じ単純名の別パッケージの型は判断型にしない`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.other.Outcome
            fun project(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `型エイリアス経由でも判断型として報告する`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Result
            fun project(result: Result): String = when (result) {
                is sample.core.Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `import の別名経由でも判断型として報告する`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome as Renamed
            fun project(outcome: Renamed): String = when (outcome) {
                Renamed.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `null を許す対象式でも報告する`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome
            fun project(outcome: Outcome?): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `関数内のラムダやローカル関数の when も対象にし、重複して報告しない`() {
        val findings = lint(
            listOf("sample.app.project", "sample.app.project.inner"),
            """
            package sample.app
            import sample.core.Outcome
            fun project(outcomes: List<Outcome>): List<String> {
                fun inner(outcome: Outcome): String = when (outcome) {
                    Outcome.Done -> "done"
                    else -> "other"
                }
                return outcomes.map { outcome ->
                    when (outcome) {
                        Outcome.Done -> "done"
                        else -> inner(outcome)
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(6, 11), findings.map { it.location.source.line })
    }

    @Test
    fun `文として使った when の else も報告する`() {
        val findings = lint(
            listOf("sample.app.render"),
            """
            package sample.app
            import sample.core.Outcome
            fun render(outcome: Outcome) {
                when (outcome) {
                    Outcome.Done -> println("done")
                    else -> println("other")
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `引数型を含むシグネチャで関数を区別する`() {
        val code = """
            package sample.app
            import sample.core.Outcome
            fun project(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            fun project(outcome: Outcome, label: String): String = when (outcome) {
                Outcome.Done -> label
                else -> "other"
            }
        """.trimIndent()
        val findings = lint(listOf("sample.app.project(sample.core.Outcome, kotlin.String)"), code)
        assertEquals(listOf(9), findings.map { it.location.source.line })
    }

    @Test
    fun `対象の関数を指定しないときはすべての関数を検査する`() {
        val findings = lint(
            emptyList(),
            """
            package sample.app
            import sample.core.Outcome
            fun anything(outcome: Outcome): String = when (outcome) {
                Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `対象式のない when は判定できないので報告しない`() {
        val findings = lint(
            listOf("sample.app.project"),
            """
            package sample.app
            import sample.core.Outcome
            fun project(outcome: Outcome): String = when {
                outcome == Outcome.Done -> "done"
                else -> "other"
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `注釈を付けた型は、関数を指定しなくても else を報告する`() {
        val findings = ExhaustiveDecisionWhen(TestConfig("decisionAnnotations" to listOf("sample.core.Marked")))
            .lintWithContext(
                environment,
                """
                package sample.app
                import sample.core.Marked
                import sample.core.Judged
                @Marked
                sealed interface Local { data object One : Local }
                fun anywhere(value: Local): String = when (value) {
                    Local.One -> "one"
                    else -> "other"
                }
                fun elsewhere(value: Judged): String = when (value) {
                    Judged.Done -> "done"
                    else -> "other"
                }
                fun external(code: String): Int = when (code) {
                    "ok" -> 1
                    else -> 0
                }
                """.trimIndent(),
                MARKER_AND_JUDGED,
            )
        assertEquals(listOf(8, 12), findings.map { it.location.source.line })
    }

    @Test
    fun `注釈を付けていない型の else は、指定のない設定では報告しない`() {
        val findings = ExhaustiveDecisionWhen(TestConfig("decisionAnnotations" to listOf("sample.core.Marked")))
            .lintWithContext(
                environment,
                """
                package sample.app
                import sample.core.Plain
                fun anywhere(value: Plain): String = when (value) {
                    Plain.One -> "one"
                    else -> "other"
                }
                """.trimIndent(),
                MARKER_AND_JUDGED,
            )
        assertTrue(findings.isEmpty())
    }
}

private const val MARKER_AND_JUDGED = """
package sample.core
annotation class Marked
@Marked
sealed interface Judged { data object Done : Judged; data object Failed : Judged }
sealed interface Plain { data object One : Plain; data object Two : Plain }
"""
