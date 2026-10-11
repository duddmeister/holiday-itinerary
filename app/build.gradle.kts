import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Lumen's own signing key, so each new build installs over the last one and the app
// can update itself. It never lives in the repository: CI writes it from the
// LUMEN_SIGNING_KEY secret to a temporary file and passes its path and password.
// Builds without it use the debug key.
val signingKeyFile: String? = providers.environmentVariable("LUMEN_SIGNING_KEY_FILE").orNull?.takeIf { it.isNotEmpty() }
val signingPassword: String? = providers.environmentVariable("LUMEN_SIGNING_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
val ownKey = signingKeyFile != null && signingPassword != null

android {
    namespace = "io.github.duddmeister.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.duddmeister.lumen"
        minSdk = 26
        targetSdk = 35
        // CI passes the number of commits, so each build on main is newer than the last.
        val build = providers.environmentVariable("LUMEN_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"

        // Where the app looks for updates: the GitHub repository that built it, and only
        // in builds signed with Lumen's key, since only those can install the updates.
        val updateRepo = if (!ownKey) "" else providers.environmentVariable("GITHUB_REPOSITORY")
            .getOrElse("")
            .filter { it.isLetterOrDigit() || it in "-_./" }
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

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

    signingConfigs {
        if (ownKey) {
            create("lumen") {
                storeFile = file(signingKeyFile!!)
                storeType = "PKCS12"
                storePassword = signingPassword
                keyAlias = "lumen"
                keyPassword = signingPassword
            }
        }
    }
    val signing = signingConfigs.findByName("lumen") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
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
