plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mathmaster.thcs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mathmaster.thcs"
        minSdk = 24
        targetSdk = 35
        versionCode = 20
        versionName = "2.0-local-ai"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
