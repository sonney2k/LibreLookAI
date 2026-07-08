plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.librelookai.feature.wardrobe"
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
    // DriveImage / Location / ClothingTags / ItemSidecar / UserPreferences appear in
    // public signatures.
    api(project(":core:model"))
    // DriveService + SyncEngine + SidecarSyncQueue + the § 2 move/delete mutation kinds/payloads.
    implementation(project(":core:sync"))
    // WardrobeItemStore + OutfitStore/… + ItemVersions + toCachedItem (the § 5 derivations).
    implementation(project(":core:database"))
    // AiClient / AiResult / AiRetry / FashionTrendsCache / TagNormalizer + gemini progress.
    implementation(project(":core:ai"))
    // EmbeddingService + SegmentationRepository (dedupe / find-by-photo / bg removal).
    implementation(project(":core:ml"))
    // Analytics, localized, ImageEncoding, NetworkUtils, WebProductFetcher, rotateBitmapFileBy90.
    implementation(project(":core:common"))
    // ClosetSession + UserPreferencesRepository.
    implementation(project(":core:session"))
    // JobLock + JobForegroundService (the ingestion / bulk-op keepalive).
    implementation(project(":core:service"))
    // Shared vocabulary strings (DsR), AppScreenHeader / SharedChrome (LocationButton),
    // AppFab / SelectionActionBar, the wardrobe grid/taxonomy/filter UI, CaptureScreen /
    // PhotoReviewScreen, MatchPreviewDialog, DestructiveConfirmDialog, AiProcessingOverlay.
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
