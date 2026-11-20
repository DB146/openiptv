plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The playlist URL is fixed at build time. Set it with (first match wins):
//   -PM3U_URL=...   |   env M3U_URL (GitHub: Settings > Secrets and variables > Actions > Variables)   |   DEFAULT_M3U below
val DEFAULT_M3U = "https://raw.githubusercontent.com/DB146/openiptv/refs/heads/main/m3u"
val m3uUrl: String = providers.gradleProperty("M3U_URL")
    .orElse(providers.environmentVariable("M3U_URL"))
    .orElse(DEFAULT_M3U).get().trim()

android {
    namespace = "com.example.openiptv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.openiptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "M3U_URL", "\"" + m3uUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // installable; swap for your own key to publish
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Playback only. No AppCompat, no media3-ui: plain Activity + SurfaceView.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")

    // JVM unit tests (parser, URL validation, ClearKey, HTTP client): ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
