plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.librelookai.feature.shopping"
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
    // DriveImage / Location / ClothingTags / ItemSidecar / UrlImportPickerState appear in
    // public signatures.
    api(project(":core:model"))
    // DriveService + the § 2 move/delete mutation kinds/payloads + SidecarSyncQueue.
    implementation(project(":core:sync"))
    // WardrobeItemStore + toCachedItem + ItemVersions (the § 5 slice 4c derived wishlist).
    implementation(project(":core:database"))
    // AiClient / AiResult (classifyClothing) + ClothingTags.
    implementation(project(":core:ai"))
    // EmbeddingService (similarity finder index + search).
    implementation(project(":core:ml"))
    // Analytics, localized, isNetworkAvailable, WebProductFetcher, rotateBitmapFileBy90.
    implementation(project(":core:common"))
    // ClosetSession + UserPreferencesRepository.
    implementation(project(":core:session"))
    // Shared vocabulary strings (DsR), AppFab / SelectionActionBar, the wardrobe
    // grid/taxonomy/filter UI, MatchPreviewDialog, LocalWardrobePalette.
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
