plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.detekt)
}

// 基準仕様の来場受付 Core は、予約 Core の公開契約だけに依存する。資格 Core への直接の依存は課題 I で初めて加える。
kotlin {
    android {
        namespace = "example.checkin.core"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            api(project(":reservation-core"))
            api(libs.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}
