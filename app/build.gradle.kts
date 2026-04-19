import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.firebase.appdistribution)
    alias(libs.plugins.google.services)
}

// Apply google-services only when google-services.json is present (opt-in for managed-mode builds)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.librelookai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.librelookai"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProps.getProperty("gemini.api.key", "")}\"",
        )
        buildConfigField(
            "String",
            "AMAZON_AFFILIATE_TAG",
            "\"${localProps.getProperty("amazon.affiliate.tag", "")}\"",
        )
        buildConfigField(
            "String",
            "SHOPSTYLE_PUBLISHER_ID",
            "\"${localProps.getProperty("shopstyle.publisher.id", "")}\"",
        )
        // Managed-mode: Firebase proxy base URL (e.g. https://us-central1-PROJECT.cloudfunctions.net)
        buildConfigField(
            "String",
            "PROXY_BASE_URL",
            "\"${localProps.getProperty("firebase.proxy.url", "")}\"",
        )
        // Firebase web client ID for Google Sign-In → Firebase Auth linking
        buildConfigField(
            "String",
            "FIREBASE_WEB_CLIENT_ID",
            "\"${localProps.getProperty("firebase.web.client.id", "")}\"",
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("signing.store.file", "").trim())
            storePassword = localProps.getProperty("signing.store.password", "").trim()
            keyAlias = localProps.getProperty("signing.key.alias", "").trim()
            keyPassword = localProps.getProperty("signing.key.password", "").trim()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            firebaseAppDistribution {
                artifactType = "APK"
                groups = "testers"
            }
        }
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.location)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.billing.ktx)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
