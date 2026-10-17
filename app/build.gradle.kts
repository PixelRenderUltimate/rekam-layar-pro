plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rekamlayar.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rekamlayar.pro"
        minSdk = 29          // Android 10 — batas bawah untuk rekam suara internal
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }

    // Kunci rilis dibaca dari variabel lingkungan. Kalau tidak ada (misalnya saat
    // build lokal biasa), bagian ini dilewati dan hanya APK debug yang dihasilkan.
    val berkasKunci = System.getenv("KEYSTORE_FILE")
    val adaKunci = !berkasKunci.isNullOrBlank() && file(berkasKunci).exists()

    signingConfigs {
        if (adaKunci) {
            create("rilis") {
                storeFile = file(berkasKunci!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (adaKunci) signingConfig = signingConfigs.getByName("rilis")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
