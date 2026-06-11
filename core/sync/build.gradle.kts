import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.librelookai.core.sync"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Same local.properties switches as :app's BuildConfig (see app/build.gradle.kts) —
        // the values can't drift because both read the same file at build time.
        buildConfigField(
            "boolean",
            "DRIVE_FULL_SCOPE",
            localProps.getProperty("drive.full.scope", "false").trim().ifEmpty { "false" },
        )
        buildConfigField(
            "String",
            "FIREBASE_WEB_CLIENT_ID",
            "\"${localProps.getProperty("firebase.web.client.id", "")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
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
    // DriveRepository's API surface exposes core:model types (Location, Outfit, …).
    api(project(":core:model"))
    // SyncEngine's MutationHandler signatures expose PendingMutation(Store).
    api(project(":core:database"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)

    testImplementation(libs.junit)
}
