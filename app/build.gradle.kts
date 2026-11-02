import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    jvmToolchain(21)
}

// Release signing. The release workflow passes these as environment variables; locally they can
// live in keystore.properties (never committed). Without them, release builds are signed with the
// debug key so they can still be installed and tested.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("SEGNO_KEYSTORE_FILE", "storeFile")
val hasReleaseKey = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "io.github.ferasbusiness666.segno"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.ferasbusiness666.segno"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "1.0.0"
        resValue("string", "app_name", "Segno")
    }

    signingConfigs {
        // A fixed debug key kept in the repo, so each new test build installs over the last one.
        // It is not a secret: the release workflow signs with Feras's own key and refuses this one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("SEGNO_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SEGNO_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SEGNO_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Segno Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        // NewPipeExtractor uses newer Java APIs; desugaring brings them to Android 8.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        // Only English for now; keeps library translations out of the APK.
        localeFilters += listOf("en")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.coil.network)

    implementation(libs.newpipe.extractor)
    implementation(libs.nanojson)

    testImplementation(libs.junit)
}

// The Baseline Profile lists the code to compile ahead of time, so the first screens and scrolls
// don't stutter while the code is still interpreted. CI makes it on an emulator; it is committed
// under src/release/generated/baselineProfiles and goes into every release build.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}
