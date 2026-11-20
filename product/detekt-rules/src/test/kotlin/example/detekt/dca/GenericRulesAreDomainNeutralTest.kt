package example.detekt.dca

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class GenericRulesAreDomainNeutralTest {
    @Test
    fun `汎用ルールの実装は予約の語に依存しない`() {
        val root = File(checkNotNull(System.getProperty("dca.genericSources")))
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("reservation|予約", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
