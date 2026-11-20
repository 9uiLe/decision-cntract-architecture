package example.reservation.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val productRoot = File(checkNotNull(System.getProperty("dca.productRoot")))

private val cores = setOf("kernel", "eligibility-core", "reservation-core", "checkin-core")

// 基準仕様の依存方向。来場受付 Core は予約 Core の公開契約だけに依存し、参加資格 Core を参照しない（課題 I で許可する）。
// 逆向きや循環の依存を持たず、画面（ui）はシミュレーターへ、Core は画面とシミュレーターへ依存しない。
internal val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
    "kernel" to emptySet(),
    "eligibility-core" to setOf("kernel"),
    "reservation-core" to setOf("kernel", "eligibility-core"),
    "checkin-core" to setOf("kernel", "reservation-core"),
    "adapter-simulator" to cores,
    "ui" to cores,
    "shared" to cores + setOf("adapter-simulator", "ui"),
    "app" to setOf("shared"),
)

// 推移的に見えるだけの型の参照も許さない。api で公開された依存の型でも、許可した package 以外は import できない。
internal val allowedImportPackages: Map<String, Set<String>> = mapOf(
    "kernel" to setOf("example.kernel"),
    "eligibility-core" to setOf("example.kernel", "example.eligibility.core"),
    "reservation-core" to setOf("example.kernel", "example.eligibility.core", "example.reservation.core"),
    "checkin-core" to setOf("example.kernel", "example.reservation.core", "example.checkin.core"),
    "adapter-simulator" to setOf(
        "example.kernel", "example.eligibility.core", "example.reservation.core", "example.checkin.core", "example.adapter.simulator",
    ),
    "ui" to setOf(
        "example.kernel", "example.eligibility.core", "example.reservation.core", "example.checkin.core", "example.reservation.ui",
    ),
    "shared" to setOf(
        "example.kernel", "example.eligibility.core", "example.reservation.core", "example.checkin.core",
        "example.adapter.simulator", "example.reservation.ui", "example.app",
    ),
    "app" to setOf("example.app"),
)

internal fun declaredProjectDependencies(buildScript: String): Set<String> =
    Regex("project\\(\":([\\w-]+)\"\\)").findAll(buildScript).map { it.groupValues[1] }.toSet()

internal fun importViolations(module: String, sourceText: String): List<String> {
    val allowed = allowedImportPackages.getValue(module)
    return Regex("^import\\s+(example\\.[\\w.]+)", RegexOption.MULTILINE).findAll(sourceText)
        .map { it.groupValues[1] }
        .filterNot { import -> allowed.any { import == it || import.startsWith("$it.") } }
        .toList()
}

private fun mainSources(module: String): List<File> =
    File(productRoot, "$module/src").listFiles { dir -> dir.isDirectory && (dir.name == "main" || dir.name.endsWith("Main")) }.orEmpty()
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

class ModuleBoundaryTest {
    @Test
    fun `module の依存宣言は定義した依存方向だけで、逆向きや禁止の依存を持たない`() {
        val actual = allowedProjectDependencies.keys.associateWith { module ->
            declaredProjectDependencies(File(productRoot, "$module/build.gradle.kts").readText())
        }
        val violations = actual.mapNotNull { (module, dependencies) ->
            (dependencies - allowedProjectDependencies.getValue(module)).takeIf { it.isNotEmpty() }?.let { module to it }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `module のソースは、許可した package の型だけを import する`() {
        val violations = allowedImportPackages.keys.flatMap { module ->
            mainSources(module).flatMap { file -> importViolations(module, file.readText()).map { "$module ${file.name} $it" } }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `基準の来場受付 Core が参加資格の型を import する例を、禁止依存として検出する`() {
        val source = "package example.checkin.core\nimport example.eligibility.core.EligibilityCertificate\nimport example.reservation.core.ReservationAdmission\n"
        assertEquals(listOf("example.eligibility.core.EligibilityCertificate"), importViolations("checkin-core", source))
        assertTrue(importViolations("reservation-core", source).isEmpty())
    }

    @Test
    fun `逆向きの依存宣言を禁止依存として検出する`() {
        val reversed = "dependencies { api(project(\":checkin-core\")) }"
        assertEquals(setOf("checkin-core"), declaredProjectDependencies(reversed))
        assertTrue(declaredProjectDependencies(reversed) - allowedProjectDependencies.getValue("reservation-core") == setOf("checkin-core"))
        val eligibility = "api(project(\":eligibility-core\"))"
        assertTrue(declaredProjectDependencies(eligibility) - allowedProjectDependencies.getValue("checkin-core") == setOf("eligibility-core"))
    }

    @Test
    fun `画面と Core がシミュレーターや画面を参照する例を検出する`() {
        assertEquals(
            listOf("example.adapter.simulator.Simulator"),
            importViolations("ui", "import example.adapter.simulator.Simulator\nimport example.reservation.core.Catalog\n"),
        )
        assertEquals(
            listOf("example.reservation.ui.ReservationViewModel"),
            importViolations("reservation-core", "import example.reservation.ui.ReservationViewModel\n"),
        )
    }
}
