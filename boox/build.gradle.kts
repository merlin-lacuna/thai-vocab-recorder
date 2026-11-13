plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "app.thaivocab.boox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thaivocab.boox"
        minSdk = 30 // Boox Note Air2 Plus: Android 11
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Same committed key as the recorder: every build has the same signature, so a new
    // APK installs over the old one.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("app/personal.keystore")
            storePassword = "thaivocab"
            keyAlias = "thaivocab"
            keyPassword = "thaivocab"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // the Onyx library uses reflection; keep all classes
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
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
    }
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

// The Onyx pen library declares old android.support libraries, which contain the same
// classes as androidx.core. The pen input code does not use them.
configurations.all {
    exclude(group = "com.android.support")
}

dependencies {
    implementation(project(":core"))
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.9")
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.10")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.7")
    // The Onyx library calls hidden Android APIs. This library allows the calls on Android 9+.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
