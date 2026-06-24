package com.airremote.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "SuggestedApps"

// ─── Curated app list source ─────────────────────────────────────────────────────
// A hand-picked list of apps you want to advertise (e.g. your own TV launcher), served
// as JSON so you can add/remove entries by editing ONE file in your repo — no phone-app
// update needed. The URL comes from BuildConfig (injected by build.gradle.kts from
// local.properties, with a public default), same as the GitHub update source. Shape:
//
//   [
//     {
//       "label": "AirRemote Launcher",
//       "packageName": "com.you.launcher",
//       "apkUrl": "https://github.com/you/launcher/releases/download/v1/launcher.apk",
//       "description": "A lightweight, fast TV launcher.",
//       "iconUrl": ""
//     }
//   ]

// One advertised app. `apkUrl` is downloaded to the phone, then pushed + installed on
// the TV over ADB. Fields with defaults are optional in the JSON.
@Serializable
data class SuggestedApp(
    val label: String,
    val packageName: String,
    val apkUrl: String,
    val description: String = "",
    val iconUrl: String = "",
)

/**
 * Loads the curated "suggested apps" list from the hosted JSON and downloads a chosen
 * app's APK to local cache (so AdbManager can push it to the TV). Network runs on a
 * background thread; callbacks are delivered on the main thread.
 */
class SuggestedAppsRepository(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Fetch + parse the curated list. [onResult] gets a Result on the main thread. */
    fun fetch(onResult: (Result<List<SuggestedApp>>) -> Unit) {
        io.execute {
            val result = runCatching {
                http.newCall(Request.Builder().url(BuildConfig.SUGGESTED_APPS_URL).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("Couldn't load list (${resp.code})")
                    val body = resp.body?.string() ?: error("Empty list response")
                    json.decodeFromString<List<SuggestedApp>>(body)
                }
            }
            main.post { onResult(result) }
        }
    }

    /**
     * Download [app]'s APK to cacheDir/store/ and hand back the File on the main thread.
     * [onProgress] reports 0..100 (or -1 when size is unknown). The caller then passes
     * the File to AdbManager.installApkOnTv.
     */
    fun downloadApk(
        app: SuggestedApp,
        onProgress: (percent: Int) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) {
        io.execute {
            val result = runCatching { download(app) { p -> main.post { onProgress(p) } } }
            main.post { onResult(result) }
        }
    }

    private fun download(app: SuggestedApp, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "store").apply { mkdirs() }
        // One file per package so concurrent-ish taps don't clobber each other.
        val out = File(dir, "${app.packageName}.apk")

        http.newCall(Request.Builder().url(app.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Empty download body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress(if (total > 0) ((downloaded * 100) / total).toInt() else -1)
                    }
                }
            }
        }
        Log.i(TAG, "downloaded ${app.packageName} (${out.length()} bytes)")
        return out
    }
}
