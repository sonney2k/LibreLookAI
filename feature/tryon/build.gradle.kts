plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.librelookai.feature.tryon"
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
    // TryOn / Outfit / Trip / DriveImage / ProfileUiState appear in public signatures.
    api(project(":core:model"))
    // DriveService + SyncEngine + the wardrobe.deleteItem mutation kind/payload the
    // try-on index writes ride (refactor § 2).
    implementation(project(":core:sync"))
    // TryOnStore + PendingMutationStore (the § 5 slice 4d derived history).
    implementation(project(":core:database"))
    // AiClient / AiResult / AiRetry (tryOnOutfit) + ClothingTags.
    implementation(project(":core:ai"))
    // Analytics, ImageEncoding.itemMatchKey, rememberDialogBottomInset.
    implementation(project(":core:common"))
    // Shared vocabulary strings (DsR), AddItemSheet + taxonomy/filter UI,
    // AiProcessingOverlay, ViewerHeaderActions, LocalWardrobePalette.
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
