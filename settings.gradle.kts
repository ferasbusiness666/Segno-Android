pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor and nanojson are only published on JitPack.
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "Segno"
include(":app")
