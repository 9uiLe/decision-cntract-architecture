pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dca-reservation"

include(
    ":kernel",
    ":eligibility-core",
    ":reservation-core",
    ":checkin-core",
    ":adapter-simulator",
    ":ui",
    ":shared",
    ":app",
    ":detekt-rules",
    ":conformance",
    ":compile-contracts",
)

// モジュール ID は変えず、物理配置だけを用途別にする。変更実験の patch と steps.json はこの物理パスを参照する。
val coreModules = listOf("kernel", "eligibility-core", "reservation-core", "checkin-core")
val verificationModules = listOf("detekt-rules", "conformance", "compile-contracts")
coreModules.forEach { project(":$it").projectDir = file("core/$it") }
verificationModules.forEach { project(":$it").projectDir = file("verification/$it") }
