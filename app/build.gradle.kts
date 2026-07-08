import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.firebase.appdistribution)
    alias(libs.plugins.google.services)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Apply google-services only when google-services.json is present (opt-in for managed-mode builds)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val gitHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

android {
    namespace = "com.librelookai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.librelookai"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "2.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
        // Firebase web client ID for Google Sign-In → Firebase Auth linking
        buildConfigField(
            "String",
            "FIREBASE_WEB_CLIENT_ID",
            "\"${localProps.getProperty("firebase.web.client.id", "")}\"",
        )
        // Drive OAuth scope selector. Default off → production ships the narrow `drive.file`
        // scope (no sensitive-scope CASA review). Flip `drive.full.scope=true` in local.properties
        // for the migration build distributed to existing testers, which needs full `drive` to read
        // legacy data created under a different OAuth client and copy it into an app-owned folder.
        buildConfigField(
            "boolean",
            "DRIVE_FULL_SCOPE",
            localProps.getProperty("drive.full.scope", "false").trim().ifEmpty { "false" },
        )
        // Power/diagnostic features toggle. Default off → the Play release hides the
        // bulk wardrobe maintenance ops (re-remove backgrounds / fix cutout pixels) and the
        // diagnostic settings (similarity preview, image-quality picker). Flip
        // `power.features.enabled=true` in local.properties to surface them.
        buildConfigField(
            "boolean",
            "POWER_FEATURES_ENABLED",
            localProps.getProperty("power.features.enabled", "false").trim().ifEmpty { "false" },
        )
        // Git commit hash for debugging
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
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
                // Latest section only — release-notes.txt accumulates all history
                // and eventually blows App Distribution's 16 KB notes limit.
                releaseNotesFile = rootProject.file("release-notes-latest.txt").absolutePath
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

    androidResources {
        // Keep .tflite uncompressed so MediaPipe can mmap it directly from the APK
        noCompress += listOf("tflite")
    }

    testOptions {
        // Robolectric needs the merged manifest + resources so stringResource(...) and
        // theming resolve when Compose UI tests run on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric's bytecode instrumentation can't run under a very new JDK (its bundled ASM
// caps out below JDK 25, the machine default here). Pin unit tests to a Java 21 toolchain so
// they run deterministically regardless of the JDK launching Gradle. Auto-provisioned via the
// foojay resolver in settings.gradle.kts.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:weather"))
    implementation(project(":core:service"))
    implementation(project(":core:session"))
    implementation(project(":core:outfit"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:billing"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:tryon"))
    implementation(project(":feature:travel"))
    implementation(project(":feature:shopping"))
    implementation(project(":feature:outfit"))
    implementation(project(":core:database"))
    implementation(project(":core:ml"))
    implementation(project(":core:sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
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
    implementation(libs.firebase.analytics.ktx)
    implementation(libs.billing.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Fake-based repository tests (refactor § 3/§ 8): virtual-time Main dispatcher for the
    // repos' `Dispatchers.Main.immediate` scopes.
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-side Compose UI tests via Robolectric (./gradlew testDebugUnitTest — no device needed)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
