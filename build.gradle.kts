buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself; this picks the Kotlin version it uses.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
