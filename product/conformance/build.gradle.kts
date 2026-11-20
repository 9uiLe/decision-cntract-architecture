plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "example.reservation.conformance"
    compileSdk = 36

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
            it.inputs.file(rootProject.file("config/detekt/dca.yml")).withPropertyName("detektPolicy")
            it.inputs.files(rootProject.fileTree(rootProject.projectDir) { include("*/src/main/kotlin/**/*.kt") })
                .withPropertyName("productSources")
            it.inputs.files(layout.buildDirectory.file("reports/detekt/debug.xml")).withPropertyName("detektReport")
            it.systemProperty("dca.productRoot", rootProject.projectDir.absolutePath)
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
