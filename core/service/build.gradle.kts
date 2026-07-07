plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librelookai.core.service"
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
    // Context.localized() for the notification strings.
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    // @Singleton @Inject / @ApplicationContext annotations on JobLock — the app's Hilt
    // compilation generates the factory from the classpath; no KSP needed here.
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
}
