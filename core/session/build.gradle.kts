plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librelookai.core.session"
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
    // ClosetSession / UserPreferences appear in public signatures.
    api(project(":core:model"))
    // StaticPreferenceMirrors feeds the two legacy statics.
    implementation(project(":core:common"))
    implementation(project(":core:ml"))
    // @Singleton @Inject annotations — the app's Hilt compilation generates the factories.
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
}
