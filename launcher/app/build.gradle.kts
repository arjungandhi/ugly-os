plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uglyos.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uglyos.launcher"
        minSdk = 30
        targetSdk = 35
        // CI passes -PuglyosVersionName=X.Y.Z -PuglyosVersionCode=N; local builds get defaults.
        versionCode = (findProperty("uglyosVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("uglyosVersionName") as String?) ?: "0.1"
    }

    // Release signing comes from env vars (CI). Without UGLYOS_KEYSTORE_FILE we fall back to debug signing so
    // `assembleRelease` still produces an installable APK locally.
    val releaseKeystore = System.getenv("UGLYOS_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("UGLYOS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UGLYOS_KEY_ALIAS")
                keyPassword = System.getenv("UGLYOS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }
}

dependencies {
    implementation(project(":common"))

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
