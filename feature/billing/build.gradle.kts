import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.librelookai.feature.billing"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Same local.properties switch as :core:ai's BuildConfig — the values can't drift
        // because both read the same file at build time. (:app no longer carries it.)
        buildConfigField(
            "String",
            "PROXY_BASE_URL",
            "\"${localProps.getProperty("firebase.proxy.url", "")}\"",
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
    // PricingClient / CostTokens / UsageCategory / ManagedBilling and, transitively,
    // :core:model (CreditPacks) + :core:sync (Call.await's okhttp surface).
    api(project(":core:ai"))
    implementation(project(":core:common"))
    // Generic action_* vocabulary strings.
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.billing.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
}
