plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Repositório do GitHub de onde o app busca atualizações (Releases).
val updateRepo = "zuukvlr/top-guincho-app"

android {
    namespace = "br.com.topguincho.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.topguincho.app"
        minSdk = 26
        targetSdk = 34
        // O CI informa a versão a cada release (-PversionName=1.0.N -PversionCode=N).
        versionCode = (project.findProperty("versionCode") as String? ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "1.0.0"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    val ksPath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
