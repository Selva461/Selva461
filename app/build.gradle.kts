plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.selva.chargefix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.selva.chargefix"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        // The OnePlus 9R (and nearly every phone since 2017) is 64-bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly from CI.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Built-in ADB client: the app connects to its own phone over Wireless debugging
    // to get "shell" rights without root and without any other app.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Creates the self-signed certificate that identifies the app to adbd.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
