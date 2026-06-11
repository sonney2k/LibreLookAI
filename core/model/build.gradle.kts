plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librelookai.core.model"
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
    // Moved data classes carry Gson @SerializedName/@Transient annotations that consumers'
    // Gson instances read at runtime — expose it transitively.
    api(libs.gson)
}
