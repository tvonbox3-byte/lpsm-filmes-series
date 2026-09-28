plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lpsm.vod"
    compileSdk = 35

    val ciVersionCode = System.getenv("LPSM_VERSION_CODE")?.toIntOrNull()

    defaultConfig {
        applicationId = "com.lpsm.vod"
        minSdk = 21
        targetSdk = 35

        // No GitHub Actions, cada build recebe um versionCode crescente automaticamente.
        // Assim até correções mantendo o mesmo versionName aparecem como atualização.
        versionCode = ciVersionCode ?: 8
        versionName = "1.4.0"
    }

    buildFeatures { viewBinding = true }

    val ksPath = System.getenv("LPSM_VOD_KEYSTORE_PATH")
    val ksPass = System.getenv("LPSM_VOD_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("LPSM_VOD_KEY_ALIAS")
    val keyPass = System.getenv("LPSM_VOD_KEY_PASSWORD")

    signingConfigs {
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt.coil3:coil:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
