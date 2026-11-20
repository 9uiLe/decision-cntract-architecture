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
        mainSourceRoots(module).flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }.map { file ->
            val text = file.readText()
            val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1).orEmpty()
            SourceFile(module.name, file, pkg, text)
        }.toList()
    }

// KMP の main source set（commonMain・androidMain・iosMain・jvmMain など）と、JVM/Android の src/main を含む。
private fun mainSourceRoots(module: File): List<File> =
    File(module, "src").listFiles { dir -> dir.isDirectory && (dir.name == "main" || dir.name.endsWith("Main")) }.orEmpty()
        .map { File(it, "kotlin") }

// 手書きの型リストを注釈へ置き換えたとき、検査の対象を減らしていないことを確認する基準。新しい判断型は注釈だけで足りる。
private val coveredDecisionTypes = listOf(
    "example.reservation.core.ActiveRequest",
    "example.reservation.core.ReservationRecord",
    "example.reservation.core.Confirmation",
    "example.reservation.core.Rejection",
    "example.reservation.core.Acceptance",
    "example.reservation.core.ConfirmOutcome",
    "example.reservation.core.CancelFailure",
    "example.reservation.core.NewBookingRequirement",
    "example.reservation.core.BookedBasis",
    "example.reservation.core.CreateDenial",
    "example.reservation.core.AdmissionLookup",
    "example.reservation.core.AdmissionUnavailableReason",
    "example.reservation.core.CreateObservation",
    "example.reservation.core.LookupObservation",
    "example.reservation.core.CancelObservation",
    "example.reservation.core.UsageObservation",
    "example.eligibility.core.EligibilityOutcome",
    "example.eligibility.core.EligibilityStatus",
    "example.eligibility.core.QualificationObservation",
    "example.eligibility.core.Reverification",
    "example.eligibility.core.ReverificationFailure",
    "example.checkin.core.ParticipantCheckIn",
    "example.checkin.core.Denial",
    "example.checkin.core.CheckInRejection",
    "example.checkin.core.ProcessingStage",
    "example.checkin.core.CheckInAcceptance",
    "example.checkin.core.CheckInCreateObservation",
    "example.checkin.core.CheckInLookupObservation",
    "example.reservation.ui.RequestCard",
    "example.reservation.ui.ReservationCard",
    "example.reservation.ui.DetailsStage",
    "example.reservation.ui.RequirementView",
    "example.reservation.ui.QualificationBadge",
    "example.reservation.ui.CheckInView",
)

private val coreOwnerPackages = setOf("example.reservation.core", "example.eligibility.core", "example.checkin.core")

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
            (lists.getValue("decisionAnnotations") + lists.getValue("issuedAnnotations"))
                .filterNot { resolves(it, asFunction = false) }.let(::addAll)
            lists.getValue("restrictedCallables").filterNot { resolves(it, asFunction = true) }.let(::addAll)
            reservationRuleDefaults()
                .filter { name -> sources.none { it.packageName == name } }
                .filterNot { resolves(it, asFunction = true) }
                .let(::addAll)
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `判断型の注釈は、従来の設定が指定していた型のすべてに付いている`() {
        val unmarked = coveredDecisionTypes.filterNot { fqn ->
            val pkg = fqn.substringBeforeLast('.')
            val name = fqn.substringAfterLast('.')
            val declaration = Regex("@DecisionResult\\s+(?:\\w+\\s+)*?(?:class|interface)\\s+$name\\b")
            sources.any { it.packageName == pkg && declaration.containsMatchIn(it.text) }
        }
        assertEquals(emptyList(), unmarked)
    }

    @Test
    fun `許可するパッケージの宣言は、一つの所有 module のソースにだけある`() {
        val allowed = policyLists().getValue("allowedCallerPackages") +
            reservationRuleDefaults().filter { it in coreOwnerPackages }
        val owners = allowed.associateWith { allowedPackage ->
            sources.filter { it.packageName == allowedPackage || it.packageName.startsWith("$allowedPackage.") }
                .map { it.module }.toSet()
        }
        assertEquals(allowed.associateWith { 1 }, owners.mapValues { it.value.size })
        assertEquals("shared", owners.getValue("example.app.composition").single())
    }

    private fun reservationRuleDefaults(): List<String> =
        sources.filter { it.module == "detekt-rules" && it.path.name.endsWith("PortCall.kt") }.flatMap { source ->
            Regex("\"(example\\.(?:reservation|eligibility|checkin)\\.[\\w.]+)\"").findAll(source.text).map { it.groupValues[1] }.toList()
        }
}
