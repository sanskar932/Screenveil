plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenveil.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.screenveil.app"
        minSdk = 26          // Android 8.0 - required minimum
        targetSdk = 35       // Latest available target SDK
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    // Core AndroidX + Kotlin extensions
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")

    // Material Design components (Switch, Slider, Buttons) - required for UI spec
    implementation("com.google.android.material:material:1.12.0")

    // Activity KTX for registerForActivityResult (runtime permission request)
    implementation("androidx.activity:activity-ktx:1.9.0")
}
