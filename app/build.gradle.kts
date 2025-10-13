plugins {
    alias(libs.plugins.android.application)
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

    // Performance optimization
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation ("com.google.android.material:material:1.13.0")
    implementation ("androidx.appcompat:appcompat:1.7.1")
    implementation ("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation ("androidx.recyclerview:recyclerview:1.4.0")
    implementation ("com.google.android.material:material:1.13.0")
    implementation ("androidx.preference:preference:1.2.1")
    implementation (libs.appcompat.v161)
    implementation (libs.core.ktx)
    implementation (libs.recyclerview.v132)
    implementation (libs.material.v1110)
    implementation (libs.androidx.activity.ktx)
    implementation (libs.androidx.constraintlayout.v214)
    implementation (libs.play.services.location)
    testImplementation (libs.junit)
    androidTestImplementation (libs.androidx.junit.v115)
    androidTestImplementation (libs.androidx.espresso.core.v351)
    implementation (libs.stream.webrtc.android.v104)
    implementation (libs.cardview)
    implementation (libs.preference)
    implementation (libs.okhttp)
    implementation (libs.socket.io.client)
}