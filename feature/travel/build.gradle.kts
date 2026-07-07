plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.librelookai.feature.travel"
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
    // Trip / Outfit / PackingList / DriveImage / UserPreferences / Location appear in
    // public signatures.
    api(project(":core:model"))
    // DriveService + SyncEngine / MutationHandler (the trip.save/trip.delete handlers,
    // refactor § 2).
    implementation(project(":core:sync"))
    // TripStore + PendingMutationStore (the § 5 slice 4d derived trips feed).
    implementation(project(":core:database"))
    // AiClient / AiResult / AiRetry / UsageCategory / CostTokens (packing generation).
    implementation(project(":core:ai"))
    // Analytics, localized, isNetworkAvailable, ImageEncoding.
    implementation(project(":core:common"))
    // ClosetSession + UserPreferencesRepository.
    implementation(project(":core:session"))
    // wearHistoryFlow + buildWearHistorySummary + buildLovedOutfitsSummary.
    implementation(project(":core:outfit"))
    // wmoEmoji + WeatherData.
    implementation(project(":core:weather"))
    // Shared vocabulary strings (DsR), AppScreenHeader / SharedChrome, AppFab /
    // SelectionActionBar, the wardrobe grid/taxonomy/filter UI, ClosetPickerSheet,
    // AiProcessingOverlay, LocalWardrobePalette.
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
    implementation(libs.coil.compose)
    implementation(libs.gson)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
