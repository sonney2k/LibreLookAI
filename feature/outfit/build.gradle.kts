plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.librelookai.feature.outfit"
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
    // Outfit / OutfitEvent / DriveImage / Location / UserPreferences / WearSource appear in
    // public signatures.
    api(project(":core:model"))
    // DriveService + SyncEngine + the outfit.syncFolder / outfitEvent.syncFolder handlers.
    implementation(project(":core:sync"))
    // OutfitStore + OutfitEventStore + PendingMutationStore (the § 5 slice 4b/4d derivations).
    implementation(project(":core:database"))
    // AiClient / AiResult / AiRetry / FashionTrendsCache / TagNormalizer (composer + prediction).
    implementation(project(":core:ai"))
    // Analytics, localized, ImageEncoding.itemMatchKey, rememberDialogBottomInset.
    implementation(project(":core:common"))
    // ClosetSession + UserPreferencesRepository.
    implementation(project(":core:session"))
    // wearHistoryFlow + buildWearHistorySummary + buildLovedOutfitsSummary + buildOutfitEvent.
    implementation(project(":core:outfit"))
    // WeatherViewModel + WeatherData + wmoEmoji (weather-aware suggestions).
    implementation(project(":core:weather"))
    // Shared vocabulary strings (DsR), AppScreenHeader / SharedChrome (LocationButton),
    // AppFab / SelectionActionBar, the wardrobe grid/taxonomy/filter UI, AddItemSheet /
    // OutfitItemBucket / ClosetPickerSheet / ExpertTagsCard, AiProcessingOverlay,
    // LocalWardrobePalette.
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
