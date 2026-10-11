import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.duddmeister.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.duddmeister.lumen"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // GIF search uses GIPHY or KLIPY, which need an API key. Build one in with
        // -PgiphyApiKey=... / -PklipyApiKey=... or the GIPHY_API_KEY / KLIPY_API_KEY
        // environment variables (CI reads them from repository secrets). People can
        // also add their own key in Lumen's settings.
        for ((property, name) in listOf("giphyApiKey" to "GIPHY_API_KEY", "klipyApiKey" to "KLIPY_API_KEY")) {
            val key = providers.gradleProperty(property)
                .orElse(providers.environmentVariable(name))
                .getOrElse("")
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            buildConfigField("String", name, "\"$key\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so release builds install straight away.
            // Use your own signing config before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A plain-text report that CI prints, so every issue shows in the build log.
        textReport = true
        textOutput = layout.buildDirectory.file("reports/lint-results.txt").get().asFile
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests; the Android stub's methods just throw.
    testImplementation("org.json:json:20240303")
}
