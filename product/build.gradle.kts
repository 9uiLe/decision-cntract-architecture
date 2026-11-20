import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
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
        // （Android は debug バリアント、JVM は main）を必ず含める。
        tasks.named("check") {
            dependsOn(
                provider {
                    val typed = tasks.findByName("detektDebug") ?: tasks.findByName("detektMain")
                    checkNotNull(typed) { "${project.path}: 型解決つきの detekt タスク（detektDebug / detektMain）がありません" }
                },
            )
        }
    }
}
