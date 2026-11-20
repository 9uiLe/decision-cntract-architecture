import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
}

tasks.register<Exec>("verifyComparisons") {
    group = "verification"
    description = "Run the three isolated decision contract comparisons."
    workingDir(layout.projectDirectory)
    commandLine(
        "python3", "experiments/run.py", "comparisons",
        "--evidence", layout.buildDirectory.dir("reports/contract-comparisons").get().asFile.absolutePath,
        "--gradle-option=--no-daemon", "--gradle-option=--max-workers=2",
        "--gradle-option=-Pkotlin.compiler.execution.strategy=in-process",
    )
}

// 全 KMP module で同じ target を持たせる。iOS は実機とシミュレーターの両方を対象にする。
// JVM は検証・コンパイル診断の実行手段で、製品の対象 OS は Android と iOS。
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            jvm()
            iosArm64()
            iosSimulatorArm64()
        }
    }
    // 変更実験の実行器は、コンパイラが出した診断 ID とファイル・行・列で期待と照合する。
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
    }
}

// 規約の検査（detekt の型解決つきタスク）は、各 module が dev.detekt を適用したときに共通の設定を与える。
// ルールの実装自身（:detekt-rules）は適用しない。
subprojects {
    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/dca.yml"))
            buildUponDefaultConfig = false
        }

        dependencies {
            add("detektPlugins", project(":detekt-rules"))
        }

        // 型解決なしの detekt タスクは @RequiresAnalysisApi のルールを実行しない。check には、型解決つきのタスク
        // （Android ライブラリは debug バリアント、JVM は main、KMP は commonMain を含む jvm と android の main）を必ず含める。
        tasks.named("check") {
            dependsOn(
                provider {
                    val typed = listOf("detektDebug", "detektMain", "detektMainJvm", "detektMainAndroid")
                        .mapNotNull { tasks.findByName(it) }
                    check(typed.isNotEmpty()) { "${project.path}: 型解決つきの detekt タスクがありません" }
                    typed
                },
            )
        }
    }
}
