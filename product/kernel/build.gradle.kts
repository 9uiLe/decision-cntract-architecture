plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.detekt)
}

// 業務判断・Port・権限を持たない。複数の Core が共有する識別子と時刻だけを置く。
kotlin {
    android {
        namespace = "example.kernel"
        compileSdk = 37
        minSdk = 26
    }
}
