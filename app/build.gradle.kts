plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.covernotifier"
    compileSdkVersion = "android-36.1"
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.covernotifier"
        minSdk = 30
        // Device is Android 11 (API 30). Staying on target 30 keeps us out of the
        // Android 12+ broadcast/PendingIntent tightening described in DESIGN.md §7.
        targetSdk = 30
        versionCode = 5
        versionName = "0.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    lint {
        // targetSdk 30 + hidden-vendor broadcasts trip a pile of "modern Android" lints
        // that do not apply to this device.
        abortOnError = false
    }
}

dependencies {
    // Intentionally no AndroidX: everything used here is platform API on API 30.
}
