import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Resolves the Gemini API key from, in order:
 * 1. `GEMINI_API_KEY` in `local.properties` (gitignored — preferred for local builds),
 * 2. the `GEMINI_API_KEY` environment variable (used by CI),
 * 3. empty, which makes the app start without the language layer instead of failing.
 *
 * The key is embedded in `BuildConfig` because this is a prototype with no backend. It is
 * extractable from a released APK, so production must proxy Gemini through a server.
 */
fun resolveSecret(name: String): String {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.exists()) {
        Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty(name)
    } else {
        null
    }
    return (fromLocal ?: System.getenv(name) ?: "").trim()
}

val geminiApiKey: String = resolveSecret("GEMINI_API_KEY")

/** Tavily key for the news tool; blank disables news and the tool reports it as unavailable. */
val tavilyApiKey: String = resolveSecret("TAVILY_API_KEY")

/**
 * Google Maps Platform key: Routes, Geocoding, Places, Weather, Air Quality, Pollen and (later) the
 * Maps SDK. Restrict it to the app's package and SHA-1 in Google Cloud before any public build.
 */
val googleMapsApiKey: String = resolveSecret("GOOGLE_MAPS_API_KEY")

android {
    namespace = "com.ferousco.hydro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ferousco.hydro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TAVILY_API_KEY", "\"${tavilyApiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${googleMapsApiKey.replace("\"", "\\\"")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = googleMapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload build: signed with the standard debug key so it installs. Replace this with a
            // private release keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.google.maps.compose)
    implementation(libs.google.play.services.maps)
    // Key-free OpenStreetMap map, shown only while Google Maps is not usable.
    implementation(libs.osmdroid.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
