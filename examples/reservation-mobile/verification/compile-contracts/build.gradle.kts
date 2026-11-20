plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// 各 Core の JVM ジャーだけを、利用側の classpath にする。内部可視性を与えず、別のコンパイル単位として検証する。
dependencies {
    implementation(project(":kernel"))
    implementation(project(":eligibility-core"))
    implementation(project(":reservation-core"))
    implementation(project(":checkin-core"))

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val fixtures = layout.projectDirectory.dir("src/test/fixtures")
    inputs.dir(fixtures).withPropertyName("fixtures")
    systemProperty("contracts.fixtures", fixtures.asFile.absolutePath)
    val contractClasspath = sourceSets.main.map { it.runtimeClasspath }
    inputs.files(contractClasspath).withPropertyName("contractClasspath")
    doFirst {
        systemProperty("contracts.classpath", contractClasspath.get().files.joinToString(File.pathSeparator))
    }
}
