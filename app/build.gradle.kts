plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.likhibi.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.likhibi.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // ONNX Runtime's native library is about 34 MB per CPU type, so the APK carries only
            // 64-bit ARM (phones of recent years). Build with -Plikhibi.allAbis=true to add older
            // 32-bit phones and the emulator.
            abiFilters.clear()
            abiFilters += if (project.findProperty("likhibi.allAbis") == "true") {
                listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true  // compress native libraries in the APK; they are extracted at install
        }
    }

    androidResources {
        // The offline translation model is read straight from the APK; compressing it gains nothing.
        noCompress += listOf("onnx")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true  // android.util.Log in shared code is a no-op in JVM tests
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

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline translation: the distilled student model (nlp_research/translation/export_student.py).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // The desktop runtime, so the translator's Kotlin code runs in JVM tests. 1.19.2 because newer
    // Windows builds crash against the older msvcp140.dll that JDKs ship (std::mutex ABI change);
    // the phone itself uses 1.31.0 above.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

// JVM unit tests use the desktop runtime above, not the Android library.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

