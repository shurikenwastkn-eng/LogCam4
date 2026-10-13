plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.logcam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.logcam.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("logcam") {
            storeFile = file("logcam.jks")
            storePassword = "logcam123"
            keyAlias = "logcam"
            keyPassword = "logcam123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("logcam") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("logcam")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
