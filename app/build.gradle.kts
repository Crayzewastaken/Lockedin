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
        versionCode = 8
        versionName = "1.7"
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
