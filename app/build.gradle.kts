import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.havoice.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.havoice.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        // Modern phones (including the Galaxy S25) are 64-bit ARM; skipping the other
        // CPU types keeps the speech engine from tripling the app size.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    androidResources {
        // Models are read straight from the APK; leaving them uncompressed loads faster.
        noCompress += "onnx"
    }

    // Release signing reads keystore.properties (never committed). Without it, release builds
    // are unsigned and debug builds use the usual debug key.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // The speech engine is reached through JNI by class name; shrinking would break it.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Barcodes: Google's scanner screen (no camera permission needed) and reading barcodes
    // out of photos. Both run on the phone through Google Play services.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device speech engine: noise removal, VoxLock voice isolation, streaming and
    // final speech recognition, speaker ID. Release AAR from github.com/k2-fsa/sherpa-onnx.
    // v2.0.1 is needed for VoxLock; v1.13.8 was the last version before it.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    testImplementation("junit:junit:4.13.2")
}
