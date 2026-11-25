import java.util.Properties

// API keys live in local.properties (never committed), so users never have to enter one.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\"", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ng.ainow.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "ng.ainow.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "YARNGPT_API_KEY", "\"${secret("YARNGPT_API_KEY")}\"")
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        // Only ARM is shipped (see abiFilters); sherpa-onnx and onnxruntime both carry x86 copies.
        jniLibs.excludes += listOf("lib/x86/**", "lib/x86_64/**")
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
    // On-device OCR. Bundled model: works offline immediately, adds a few MB.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Wake word ("Hey Jarvis" style): vendored openWakeWord Kotlin port (Apache-2.0) needs these.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // YarnGPT: streaming Nigerian voices (HTTP) and live speech recognition (WebSocket).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-phone Yorùbá and Hausa voices (Meta MMS models) via sherpa-onnx. This build has its own
    // copy of onnxruntime linked in, so it can't clash with the wake word's onnxruntime above.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    // On-phone language detection (offline), to read each sentence in its own language.
    implementation("com.google.mlkit:language-id:17.0.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // real JSON for unit tests (Android's is a stub)
}
