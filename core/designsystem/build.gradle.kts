plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.librelookai.core.designsystem"
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
    // DriveImage / ClothingTags / AppFont / CreditPacks appear in public signatures.
    api(project(":core:model"))
    // scrollbar modifier + LocalSystemBarsPadding.
    implementation(project(":core:common"))
    // Tag normalizers + CANONICAL_COLORS (taxonomy) and ManagedBilling (confirm dialog).
    implementation(project(":core:ai"))
    // Embedding/histogram/pHash math for the match debug viz.
    implementation(project(":core:ml"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
}
