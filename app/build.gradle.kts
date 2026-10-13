plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.droidbox.app"
    compileSdk = 35
    ndkVersion = "30.0.16248370"
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.droidbox.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 28
        versionName = "0.1.0-dev+fix28"

        // arm64-v8a only: matches the proven QubeVM recipe
        // (x86_64 host builds are NOT supported by that recipe yet).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        debug {
            // Developer builds expose VM Diagnostics (real native logs only).
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // The REAL QEMU engine (libqemu-system-x86_64.so) is built separately
        // from QEMU 11.1.2 source with the NDK and placed into
        // src/main/jniLibs/arm64-v8a/. It is loaded at runtime via dlopen()
        // by the JNI engine. If it is absent, the app reports
        // NATIVE VM ENGINE UNAVAILABLE — it never falls back to a fake VM.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
