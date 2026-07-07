import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.librelookai.feature.onboarding"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Google Picker (drive.file folder selection): API key + Cloud project number —
        // same local.properties switches :app used to carry.
        buildConfigField(
            "String",
            "PICKER_API_KEY",
            "\"${localProps.getProperty("picker.api.key", "")}\"",
        )
        buildConfigField(
            "String",
            "PICKER_APP_ID",
            "\"${localProps.getProperty("picker.app.id", "923211051414")}\"",
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
    // DriveRepository (the deliberate DriveMigrationViewModel concrete exception),
    // GoogleAuthManager and core:sync's DRIVE_FULL_SCOPE BuildConfig.
    implementation(project(":core:sync"))
    // ApiKeyStore (the API-key onboarding step).
    implementation(project(":core:ai"))
    // JobForegroundService keepalive during the legacy-Drive migration copy.
    implementation(project(":core:service"))
    // Analytics + Context.localized().
    implementation(project(":core:common"))
    // ProfileUiState / UserPreferences / TryOnSlot.
    api(project(":core:model"))
    // Shared vocabulary strings (DsR).
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
