pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Prevents individual modules from declaring their own repos —
    // all dependency resolution goes through here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — hosts the Neumorphism UI library (com.github.fornewid:neumorphism).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AirRemote"

include(":shared", ":tv-app", ":phone-app", ":tv-helper")
