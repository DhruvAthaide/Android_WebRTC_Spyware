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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation (libs.appcompat)
    implementation (libs.core)
    implementation (libs.recyclerview)
    implementation (libs.material)
    implementation (libs.activity)
    implementation (libs.constraintlayout)
    testImplementation (libs.junit)
    androidTestImplementation (libs.ext.junit)
    androidTestImplementation (libs.espresso.core)
    implementation (libs.stream.webrtc.android)
    implementation (libs.cardview)
    implementation (libs.preference)
    implementation (libs.okhttp)
    implementation (libs.socket.io.client)
}