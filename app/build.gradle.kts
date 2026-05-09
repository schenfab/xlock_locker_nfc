plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fschenkel.xlocklocker"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.fschenkel.xlocklocker"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = System.getenv("VERSION_NAME")?.trimStart('v') ?: "dev"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "/home/fschenkel/.android/xlock-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: error("Set KEYSTORE_PASSWORD env var")
            keyAlias = System.getenv("KEY_ALIAS") ?: "xlock"
            keyPassword = System.getenv("KEY_PASSWORD") ?: error("Set KEY_PASSWORD env var")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
