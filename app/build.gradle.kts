plugins {
    id("com.android.application") // Kotlin is built into AGP 9 — no KGP needed
}

android {
    namespace = "dev.karasu.ndotprayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.karasu.ndotprayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}