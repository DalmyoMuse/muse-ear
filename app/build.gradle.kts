import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing: Muse's own key, from keystore.properties next to settings.gradle.kts (never commit or upload it):
//   storeFile=/path/to/museear.jks  storePassword=…  keyAlias=…  keyPassword=…
// Without that file the build falls back to the local debug key.
// The PC built 0.1.0–0.3.5 with assembleRelease (minify + shrink), so that is the tested path.
val ks = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.dalmyo.museear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dalmyo.museear"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "0.5"
        // The Note 9 is arm64; leaving out the other ABIs keeps ONNX Runtime from tripling the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (!ks.isEmpty) create("muse") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (ks.isEmpty) "debug" else "muse")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ks.isEmpty) "debug" else "muse")
        }
    }

    androidResources {
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
}
