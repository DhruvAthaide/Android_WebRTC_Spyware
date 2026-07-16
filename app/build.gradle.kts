plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.wallpaperapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wallpaperapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Enable vector drawable support
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Performance optimization
    buildFeatures {
        buildConfig = true
    }

    // 16KB Page Size Compatibility (required for WebRTC native libs)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation(libs.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.play.services.location)

    // Firebase (BOM manages all Firebase library versions)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.analytics)

    // WebRTC & Socket.IO
    implementation(libs.stream.webrtc.android.v104)
    implementation(libs.socket.io.client)

    // WorkManager (kept as FCM fallback)
    implementation(libs.work.runtime)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.cardview)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.v115)
    androidTestImplementation(libs.androidx.espresso.core.v351)
}