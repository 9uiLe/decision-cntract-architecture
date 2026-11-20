plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.detekt)
}

kotlin {
    android {
        namespace = "example.eligibility.core"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            api(libs.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}
