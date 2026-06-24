// tv-helper — Plan 3 warm-JVM input helper.
//
// This module produces a debug APK that we DELIBERATELY DO NOT install on the TV.
// Instead, we push it to /data/local/tmp/ as a .jar and run it via `app_process` as
// the shell user (UID 2000). The shell user already has INJECT_EVENTS via group
// membership and bypasses hidden-API enforcement on Android 11 — so a tiny Kotlin
// main() loop can call `InputManager.injectInputEvent()` via reflection without
// the per-call `app_process` cold-start tax that `input keyevent` pays.
//
// We use `com.android.application` (not `kotlin-jvm`) so that AGP runs `d8` and
// packages our classes as Android dex bytecode inside the APK. The Kotlin stdlib
// gets shrunk and packaged too. `app_process` loads the APK via CLASSPATH and
// invokes our `MainKt.main()` (Kotlin's auto-generated facade class for the
// top-level `fun main()` in Main.kt).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.airremote.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airremote.helper"
        // Helper runs on the TV (API 30). 26 is plenty old — we don't actually
        // install this APK, but AGP wants a minSdk for the build.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// No dependencies. The helper uses only platform APIs (android.view.KeyEvent,
// android.hardware.input.InputManager via reflection) and the Kotlin stdlib that
// AGP bundles automatically.
