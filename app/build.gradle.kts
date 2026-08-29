import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing loads from an untracked keystore.properties at the repo root.
// When the file is absent (CI, another machine), the release build stays unsigned
// rather than failing — only the packaging machine holds the keys.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.medmission.survey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.medmission.survey"
        minSdk = 26
        targetSdk = 34
        // Bump both on every release that leaves this machine: the versionName is what
        // the home screen shows, and it is the only way to tell across a table which
        // tablet still runs an old build. Same-version reinstalls do work on Android,
        // which is exactly why an unbumped number lies.
        versionCode = 9
        versionName = "1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Override for a real deployment with -PsurveyApiKey=... or a surveyApiKey
        // entry in gradle.properties / local.properties. The fallback is a dev-only value.
        val surveyApiKey = project.findProperty("surveyApiKey") ?: "changeme-dev-key"
        buildConfigField("String", "SURVEY_API_KEY", "\"$surveyApiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Minification stays off for the pilot: kotlinx.serialization and Room
            // are reflection-adjacent, and an untested ProGuard config is a worse
            // field risk than a larger APK.
            isMinifyEnabled = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// Export the Room schema so a future migration has a v1 baseline to diff against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.startup:startup-runtime:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Turning what an operator types into E.164 is per-country work, not a rule we can
    // write down: most countries drop the trunk zero when the country code goes on,
    // Italy keeps it, and some have no trunk prefix at all. A number that survives the
    // trip is how a positive patient gets called back, so the metadata is worth the
    // couple of megabytes. The android build repackages the metadata as assets.
    implementation("io.michaelrocks:libphonenumber-android:8.13.35")

    testImplementation("junit:junit:4.13.2")
    // Compose UI tests run locally on Robolectric (no emulator needed):
    // isIncludeAndroidResources above provides the resources, ui-test-manifest
    // the host activity, and createComposeRule works under RobolectricTestRunner.
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
