import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lockedin.morning"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lockedin.morning"
        minSdk = 26
        targetSdk = 35
        // Build number from GitHub (matches the "build-N" release), so the app can tell when an update exists.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + run
        versionName = "build-$run"
    }

    // A fixed signing key (from GitHub secrets) so new builds install over old ones.
    val ksFile = rootProject.file("keystore.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("fixed") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "lockedin"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No third-party libraries: everything uses the Android framework + Kotlin stdlib.
dependencies {
}
