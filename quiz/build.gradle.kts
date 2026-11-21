plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "app.thaivocab.quiz"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thaivocab.quiz"
        minSdk = 33
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Same fixed signing key as the other apps (see app/build.gradle.kts).
    signingConfigs {
        create("personal") {
            storeFile = file("personal.keystore")
            storePassword = "thaivocab"
            keyAlias = "thaivocab"
            keyPassword = "thaivocab"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(project(":core"))
    // UnifiedPush: receives push messages through a distributor app (ntfy) on the phone.
    implementation("org.unifiedpush.android:connector:3.3.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
