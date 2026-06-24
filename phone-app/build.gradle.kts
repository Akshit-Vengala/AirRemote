import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Read local.properties (gitignored, machine-specific) so we can inject
// the TV IP at build time without it living in source control.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// Read keystore.properties (gitignored) for RELEASE signing. Holds the path to the
// keystore + its passwords, so none of that ever touches source control. When the file
// is absent (fresh clone, CI without secrets) we fall back to UNSIGNED release builds —
// debug builds are unaffected. See README for how to generate the keystore.
//
// IMPORTANT: the self-updater installs a new APK over the old one, and Android only
// allows that when both are signed with the SAME key. So every release you publish MUST
// be signed with this one keystore — lose it and existing installs can never auto-update.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}
val releaseKeystore = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

// We bundle the tv-app APK as a phone-app asset and ship it to the TV on
// connect (so a "normal user" never has to install tv-app manually). The
// runtime install logic skips the push if the installed versionCode is
// already up-to-date, so we need to know the bundled APK's versionCode at
// runtime — exposed via BuildConfig below.
//
// evaluationDependsOn(":tv-app") forces Gradle to evaluate tv-app's build
// script BEFORE this one finishes, so its `android` extension is populated
// by the time we read defaultConfig.versionCode. Without it we'd race the
// subproject's config and get versionCode=null (default).
evaluationDependsOn(":tv-app")
val tvAppVersionCode: Int = (project(":tv-app").extensions.getByName("android")
    as com.android.build.gradle.AppExtension).defaultConfig.versionCode ?: 1

android {
    namespace = "com.airremote.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airremote.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // Lets AdbManager compare the bundled tv-app's versionCode against
        // what's installed on the TV, and skip the push if already up-to-date.
        buildConfigField("int", "TV_APP_VERSION_CODE", "$tvAppVersionCode")

        // ─── Remote config: self-update source + suggested-apps store ────────────
        // Read from local.properties (gitignored) when present, else the public
        // defaults below. Owner/repo/URL are NOT secret (your GitHub handle is public
        // anyway) so they ship as committed defaults — a fresh clone builds and works.
        // Only `github.token` is sensitive: leave it OUT of local.properties for a
        // public repo (anonymous GitHub calls are plenty for a manual button), and if
        // you ever need one for a private repo, put it ONLY in local.properties.
        buildConfigField("String", "GITHUB_OWNER",
            "\"${localProps.getProperty("github.owner", "Akshit-Vengala")}\"")
        buildConfigField("String", "GITHUB_REPO",
            "\"${localProps.getProperty("github.repo", "AirRemote")}\"")
        buildConfigField("String", "GITHUB_TOKEN",
            "\"${localProps.getProperty("github.token", "")}\"")
        buildConfigField("String", "SUGGESTED_APPS_URL",
            "\"${localProps.getProperty(
                "suggested.apps.url",
                "https://raw.githubusercontent.com/Akshit-Vengala/AirRemote/main/suggested-apps.json",
            )}\"")
    }

    // Release signing config, populated only when keystore.properties points at a real
    // keystore. Created unconditionally (so `signingConfigs.getByName("release")` always
    // resolves) but left empty when there's no keystore — the buildType below then skips
    // wiring it, yielding an unsigned release APK instead of a configuration error.
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Only attach the signing config if a keystore is actually present; otherwise
            // produce an unsigned APK (which you'd have to sign by hand). This keeps a
            // keystore-less clone/CI build from failing.
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("phone-app: no keystore.properties — release APK will be UNSIGNED")
            }
            // Minify left OFF for now: the self-update + store paths are untested on
            // hardware, and R8 shrinking adds a variable we don't want to debug yet.
            // Flip to true (and verify keep-rules for okhttp/dadb/kotlinx-serialization)
            // once the release flow is proven.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true  // must be explicit in AGP 8+ — generates the BuildConfig class
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dadb)
    // androidx.core — provides FileProvider, used by Updater to hand the downloaded
    // update APK to the system installer as a content:// URI. Pulled in transitively by
    // the neumorphism lib at runtime, but declared explicitly so it's on the COMPILE
    // classpath (transitive `implementation` deps aren't).
    implementation(libs.androidx.core.ktx)
    // Soft-UI / neumorphism components (NeumorphCardView, NeumorphImageButton, …).
    implementation("com.github.fornewid:neumorphism:0.3.2")
}

// ─── tv-helper bundling (Plan 3, Gate 2) ─────────────────────────────────────
//
// Bundle the tv-helper APK into the phone-app's assets, renamed to .jar. At
// runtime, AdbManager extracts this asset to the phone's cache dir, then ADB-
// pushes it to /data/local/tmp/ on the TV and launches it via `app_process`.
//
// We copy into src/main/assets/ rather than a generated dir because AGP 8's
// generated-asset hookup (variant.sources.assets.addGeneratedSourceDirectory)
// is awkward to wire to a plain Copy task; this is pragmatically equivalent.
// We read the APK from `intermediates/apk/debug/`, NOT `outputs/apk/debug/`.
// Android Studio's "Run" button packages to `intermediates/` and does an install
// straight from there — it does NOT run the `assemble`-suffixed copy that mirrors the
// APK into `outputs/`. So `outputs/` is frequently MISSING/STALE for a Studio-driven
// build, and a Copy task sourcing it silently ships a months-old bundled APK (this bit
// us repeatedly: the phone kept pushing a stale tv-app/helper). `intermediates/` is
// what every build path (Studio Run and CLI assembleDebug) reliably refreshes.
val copyHelperToAssets by tasks.registering(Copy::class) {
    dependsOn(":tv-helper:assembleDebug")
    from(project(":tv-helper").layout.buildDirectory.file("intermediates/apk/debug/tv-helper-debug.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "airremote-helper.jar" }
}

// ─── tv-app bundling ───────────────────────────────────────────────────
//
// Same shape as the helper bundling above, but for the full tv-app APK
// (the WebSocket server + AccessibilityService + cursor overlay). At
// runtime, AdbManager checks the TV for an installed com.airremote.tv,
// compares its versionCode to BuildConfig.TV_APP_VERSION_CODE, and pushes
// + `pm install -r`s this APK if the TV is missing it or stale.
//
// We bundle the DEBUG variant for now — matches what Studio installs, no
// release keystore setup required. Switch to assembleRelease when we ship.
// Sourced from `intermediates/apk/debug/` for the same reason as copyHelperToAssets
// above — `outputs/` is stale/absent under a Studio "Run" build, which silently
// shipped a months-old tv-app and broke every install.
val copyTvAppToAssets by tasks.registering(Copy::class) {
    dependsOn(":tv-app:assembleDebug")
    from(project(":tv-app").layout.buildDirectory.file("intermediates/apk/debug/tv-app-debug.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "airremote-tv.apk" }
}

// Run BOTH copies before AGP merges assets, for both debug and release variants.
// `afterEvaluate` lets us see tasks AGP registers from its own configuration.
//
// We also wire in any task whose name contains "lint" — Gradle 8.x's strict task
// validation flags implicit dependencies when a task reads from a directory
// another task writes to. AGP's lint analysis (generateReleaseLintVitalReportModel,
// lintVitalAnalyzeRelease, lintAnalyzeDebug, …) reads src/main/assets/, which is
// where the copy tasks write. Without an explicit dependency, Gradle fails
// the build with "uses this output … without declaring an explicit … dependency".
// Lowercase-contains catches all camelCase variants AGP introduces over time.
afterEvaluate {
    tasks.matching {
        (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
            it.name.lowercase().contains("lint")
    }.configureEach { dependsOn(copyHelperToAssets, copyTvAppToAssets) }
}
