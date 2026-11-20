package example.contracts

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

private val fixtures = File(checkNotNull(System.getProperty("contracts.fixtures")))
private val classpath = checkNotNull(System.getProperty("contracts.classpath"))

private class Compilation(val exit: ExitCode, val errors: List<Diagnostic>, val log: String)

private data class Diagnostic(val file: String, val line: Int, val id: String)

// 利用側の fixture を、各 Core の JVM ジャーだけを classpath にして、別々にコンパイルする。
private fun compile(source: File): Compilation {
    val out = Files.createTempDirectory("dca-contract").toFile()
    val buffer = ByteArrayOutputStream()
    val exit = PrintStream(buffer, true, "UTF-8").use { stream ->
        K2JVMCompiler().exec(
            stream,
            "-Xrender-internal-diagnostic-names", "-no-stdlib", "-no-reflect",
            "-cp", classpath, "-module-name", "dca-contract", "-d", out.absolutePath, source.absolutePath,
        )
    }
    out.deleteRecursively()
    val log = buffer.toString("UTF-8")
    val errors = Regex("(?m)^(?:.*/)?([\\w.]+\\.kt):(\\d+):\\d+: error: \\[(\\w+)] ").findAll(log)
        .map { Diagnostic(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.toList()
    return Compilation(exit, errors, log)
}

private fun fixtureFiles(kind: String): List<File> =
    File(fixtures, kind).listFiles { file -> file.extension == "kt" }.orEmpty().sortedBy { it.name }

class CompileContractsTest {
    @Test
    fun `正規の公開契約だけを使う利用側は、Core ごとのジャーだけでコンパイルできる`() {
        val accepted = fixtureFiles("accept")
        assertTrue(accepted.isNotEmpty())
        for (file in accepted) {
            val result = compile(file)
            assertEquals(ExitCode.OK, result.exit, "${file.name}\n${result.log}")
            assertEquals(emptyList(), result.errors, file.name)
        }
    }

    @Test
    fun `確認済みの値・操作権限・券の偽造、未確認の入力、取り違えた試行は、期待した診断一件で拒否される`() {
        val rejected = fixtureFiles("reject")
        assertTrue(rejected.size >= 10)
        for (file in rejected) {
            val marked = file.readLines().withIndex().mapNotNull { (index, line) ->
                Regex("// VIOLATION \\[(\\w+)]").find(line)?.let { index + 1 to it.groupValues[1] }
            }
            assertEquals(1, marked.size, "${file.name} には // VIOLATION [診断 ID] が一行だけ必要です")
            val (line, id) = marked.single()
            val result = compile(file)
            assertEquals(ExitCode.COMPILATION_ERROR, result.exit, "${file.name}\n${result.log}")
            assertEquals(listOf(Diagnostic(file.name, line, id)), result.errors, "${file.name}\n${result.log}")
        }
    }
}
