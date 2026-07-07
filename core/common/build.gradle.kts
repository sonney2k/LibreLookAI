plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.librelookai.core.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    // ImageEncoding.tier is typed ImageQuality, which lives in :core:model (public API → api).
    api(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    // Call.await() exposes OkHttp types in its signature.
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Analytics (sunk in § 1 slice 5 so the extracted feature modules can log events).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics.ktx)

    testImplementation(libs.junit)
}
