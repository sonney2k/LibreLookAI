import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.librelookai.core.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Same local.properties switches as :app's BuildConfig (see app/build.gradle.kts) —
        // the values can't drift because both read the same file at build time.
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProps.getProperty("gemini.api.key", "")}\"",
        )
        // Managed-mode: Firebase proxy base URL (e.g. https://us-central1-PROJECT.cloudfunctions.net)
        buildConfigField(
            "String",
            "PROXY_BASE_URL",
            "\"${localProps.getProperty("firebase.proxy.url", "")}\"",
        )
        // Master switch for the managed coin economy (purchase/refinancing UI + proxy billing).
        // Default off → the Play release ships BYOK-only.
        buildConfigField(
            "boolean",
            "MANAGED_BILLING_ENABLED",
            localProps.getProperty("managed.billing.enabled", "false").trim().ifEmpty { "false" },
        )
    }

    buildFeatures {
        buildConfig = true
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
    // GeminiRepository / TokenUsageRepository signatures expose core:model types
    // (ClothingTags, FashionTrends, DriveImage, CreditPack…).
    api(project(":core:model"))
    // TokenUsageRepository / FashionTrendsCache take a DriveRepository in their public API.
    api(project(":core:sync"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    testImplementation(libs.junit)
}
