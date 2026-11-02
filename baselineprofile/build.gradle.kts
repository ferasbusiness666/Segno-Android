// Makes Segno's Baseline Profile: runs the main screens on a connected phone or emulator (API 33+,
// or a rooted 28+) and records the code they use. CI runs it with
// ./gradlew :app:generateReleaseBaselineProfile and the result is committed in the app module.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "io.github.ferasbusiness666.segno.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk {
            version = release(28)
        }
        targetSdk {
            version = release(37)
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
