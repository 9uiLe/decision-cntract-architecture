plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.detekt.api)
    compileOnly(libs.detekt.psi.utils)

    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.psi.utils)
    testImplementation(libs.detekt.test)
    testImplementation(libs.detekt.test.utils)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("dca.genericSources", layout.projectDirectory.dir("src/main/kotlin/example/detekt/dca").asFile.absolutePath)
}
