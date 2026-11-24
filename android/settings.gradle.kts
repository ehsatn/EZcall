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
        maven("https://jitpack.io") {
            content {
                includeModule("com.github.MuntashirAkon", "libadb-android")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}
rootProject.name = "ezcall"
include(":app", ":receiver")
