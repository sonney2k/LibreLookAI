plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librelookai.core.outfit"
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
    // Outfit / OutfitEvent / DriveImage / ClothingTags appear in public signatures.
    api(project(":core:model"))
    // OutfitEventStore (wearHistoryFlow's public signature).
    api(project(":core:database"))
    // ClosetSessionHolder (wearHistoryFlow's public signature).
    api(project(":core:session"))
    // WeatherData (buildOutfitEvent's public signature).
    api(project(":core:weather"))

    implementation(libs.kotlinx.coroutines.android)
}
