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
        // Official Guardian Project Maven repository for tor-android 0.4.8.12 (minSdk 19, all 4 ABIs)
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "GreatFireEnvoyBrowser"
include(":app")
