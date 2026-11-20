plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.detekt)
}

kotlin {
    android {
        namespace = "example.app.shared"
        compileSdk = 37
        minSdk = 26
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DcaShared"
            isStatic = true
            binaryOption("bundleId", "example.reservation.dca.shared")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            api(project(":eligibility-core"))
            api(project(":reservation-core"))
            api(project(":checkin-core"))
            api(project(":adapter-simulator"))
            api(project(":ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.datetime)
        }
    }
}
