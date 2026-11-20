package example.reservation.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val productRoot = File(checkNotNull(System.getProperty("dca.productRoot")))
private val detektReport = File(checkNotNull(System.getProperty("dca.detektReport")))

private data class Located(val file: String, val line: Int, val rule: String)

private class SourceFile(val module: String, val path: File, val packageName: String, val text: String)

private val sources: List<SourceFile> = productRoot.listFiles { dir -> dir.isDirectory }.orEmpty()
    .flatMap { module ->
        File(module, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { file ->
            val text = file.readText()
            val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1).orEmpty()
            SourceFile(module.name, file, pkg, text)
        }.toList()
    }

private fun policyLists(): Map<String, List<String>> {
    val lists = mutableMapOf<String, MutableList<String>>()
    var key: String? = null
    File(productRoot, "config/detekt/dca.yml").readLines().forEach { line ->
        Regex("^\\s+(\\w+):\\s*$").matchEntire(line)?.let { key = it.groupValues[1] }
        Regex("^\\s+-\\s+'([^']+)'\\s*$").matchEntire(line)?.let { entry ->
            lists.getOrPut(checkNotNull(key)) { mutableListOf() } += entry.groupValues[1]
        }
    }
    return lists
}

private fun declaresClass(text: String, name: String) =
    Regex("\\b(class|interface|object)\\s+$name\\b").containsMatchIn(text)

private fun declaresFunction(text: String, name: String) =
    Regex("\\bfun\\s+(<[^>]*>\\s*)?([\\w.<>?]+\\.)?$name\\s*\\(").containsMatchIn(text)

private fun resolves(fqn: String, asFunction: Boolean): Boolean {
    val segments = fqn.split('.')
    return (1 until segments.size).any { split ->
        val pkg = segments.take(split).joinToString(".")
        val rest = segments.drop(split)
        sources.filter { it.packageName == pkg }.any { source ->
            val owners = if (asFunction) rest.dropLast(1) else rest
            owners.all { declaresClass(source.text, it) } && (!asFunction || declaresFunction(source.text, rest.last()))
        }
    }
}

class ConformanceTest {
    @Test
    fun `fixture の違反は、期待した規則と行でだけ型解決つきの detekt が検出する`() {
        val expected = sources.filter { it.module == "conformance" }.flatMap { source ->
            source.text.lines().mapIndexedNotNull { index, line ->
                Regex("// EXPECT: (\\w+)").find(line)?.let { Located(source.path.name, index + 1, it.groupValues[1]) }
            }
        }
        val report = detektReport.readText()
        val actual = Regex("<file name=\"([^\"]+)\">(.*?)</file>", RegexOption.DOT_MATCHES_ALL).findAll(report)
            .flatMap { file ->
                val name = File(file.groupValues[1]).name
                Regex("<error line=\"(\\d+)\"[^>]*source=\"detekt\\.(\\w+)\"").findAll(file.groupValues[2])
                    .map { Located(name, it.groupValues[1].toInt(), it.groupValues[2]) }
            }
            .toList()
        assertTrue(expected.isNotEmpty())
        val order = compareBy<Located>({ it.file }, { it.line }, { it.rule })
        assertEquals(expected.sortedWith(order), actual.sortedWith(order))
    }

    @Test
    fun `設定が参照する型・関数・呼び出し先は実コードに存在する`() {
        val lists = policyLists()
        val missing = buildList {
            lists.getValue("decisionTypes").filterNot { resolves(it, asFunction = false) }.let(::addAll)
            lists.getValue("decisionFunctions").filterNot { resolves(it, asFunction = true) }.let(::addAll)
            lists.getValue("restrictedCallables").filterNot { resolves(it, asFunction = true) }.let(::addAll)
            reservationRuleDefaults()
                .filter { name -> sources.none { it.packageName == name } }
                .filterNot { resolves(it, asFunction = true) }
                .let(::addAll)
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `許可するパッケージの宣言は、一つの所有 module のソースにだけある`() {
        val allowed = policyLists().getValue("allowedCallerPackages") +
            reservationRuleDefaults().filter { it == "example.reservation.core" }
        val owners = allowed.associateWith { allowedPackage ->
            sources.filter { it.packageName == allowedPackage || it.packageName.startsWith("$allowedPackage.") }
                .map { it.module }.toSet()
        }
        assertEquals(allowed.associateWith { 1 }, owners.mapValues { it.value.size })
        assertEquals("app", owners.getValue("example.app.composition").single())
    }

    private fun reservationRuleDefaults(): List<String> =
        sources.single { it.path.name == "ReservationPortCall.kt" }.text.let { text ->
            Regex("\"(example\\.reservation\\.[\\w.]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        }
}
