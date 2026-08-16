plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.phonelink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonelink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Release signing via environment variables only (never hardcode secrets in repo).
            // Missing env vars fail only release-oriented tasks with a clear message;
            // debug builds and unit tests never require them.
            val releaseEnv = listOf(
                "PHONELINK_KEYSTORE_PATH" to System.getenv("PHONELINK_KEYSTORE_PATH"),
                "PHONELINK_KEYSTORE_PASSWORD" to System.getenv("PHONELINK_KEYSTORE_PASSWORD"),
                "PHONELINK_KEY_ALIAS" to System.getenv("PHONELINK_KEY_ALIAS"),
                "PHONELINK_KEY_PASSWORD" to System.getenv("PHONELINK_KEY_PASSWORD"),
            )
            val missingEnv = releaseEnv.filter { it.second.isNullOrEmpty() }
            if (missingEnv.isEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseEnv[0].second!!)
                    storePassword = releaseEnv[1].second!!
                    keyAlias = releaseEnv[2].second!!
                    keyPassword = releaseEnv[3].second!!
                }
            } else {
                tasks.matching { it.name.contains("Release", ignoreCase = true) }.configureEach {
                    doFirst {
                        throw GradleException(
                            "Release signing not configured: missing env var(s) " +
                                missingEnv.joinToString(", ") { it.first } +
                                ". See docs/RELEASE_BUILD.md. Debug builds do not need them."
                        )
                    }
                }
            }
        }
    }

    // OpenCV：仅打包真机常用 ABI（arm64-v8a + armeabi-v7a），显著减小 APK
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Phase 2: pairing + transfer over TLS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    // CameraX (QR scanning)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Phase 3: EXIF orientation normalization
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Phase 4B: local document scanning (OpenCV native, no cloud; AAR vendored in app/libs)
    implementation(files("libs/opencv-4.10.0.aar"))

    // Phase 4B-D1: DocQuadNet-256 ML Inference via ONNX Runtime (Apache 2.0 / MIT)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}