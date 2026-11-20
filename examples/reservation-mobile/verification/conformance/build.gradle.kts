plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "example.reservation.conformance"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.dependsOn("detektDebug")
            // テストは設定・全 module の Kotlin ソース・detekt のレポートを読む。変更時に再実行されるよう入力に宣言する。
            val moduleDirectories = rootProject.subprojects.associate { module -> module.name to module.projectDir }
            it.inputs.file(rootProject.file("verification/config/detekt/dca.yml")).withPropertyName("detektPolicy")
            it.inputs.files(moduleDirectories.values.map { directory ->
                rootProject.fileTree(directory) { include("src/*Main/kotlin/**/*.kt", "src/main/kotlin/**/*.kt") }
            }).withPropertyName("productSources")
            it.inputs.files(moduleDirectories.values.map { directory -> directory.resolve("build.gradle.kts") })
                .withPropertyName("moduleDependencies")
            it.inputs.files(layout.buildDirectory.file("reports/detekt/debug.xml")).withPropertyName("detektReport")
            it.systemProperty("dca.policy", rootProject.file("verification/config/detekt/dca.yml").absolutePath)
            it.systemProperty("dca.modules", moduleDirectories.entries.joinToString("|") { (name, directory) -> "$name=${directory.absolutePath}" })
            it.systemProperty("dca.detektReport", layout.buildDirectory.file("reports/detekt/debug.xml").get().asFile.absolutePath)
        }
    }
}

dependencies {
    api(project(":reservation-core"))
    api(project(":ui"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// この module の fixture は規約違反を意図して含む。違反は失敗にせず、レポートを検証のテストが期待と照合する。
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    ignoreFailures = true
    reports {
        checkstyle.required = true
    }
}
