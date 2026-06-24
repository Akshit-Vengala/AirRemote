package com.airremote.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "Updater"

// ─── GitHub source ───────────────────────────────────────────────────────────────
// The repo whose Releases page hosts the phone-app APK. "Check for updates" reads the
// LATEST published release here, compares its tag to this build's versionName, and
// offers to download + install the APK asset attached to that release.
//
// Owner/repo/token now come from BuildConfig (injected by build.gradle.kts from
// local.properties, with public defaults). This keeps environment config out of source
// and the optional token out of git entirely. See the buildConfigField block in
// phone-app/build.gradle.kts.

// The result of a check. `sealed` = the complete set of outcomes is known here, so a
// `when` over an UpdateCheck can be exhaustive (no `else` branch needed).
sealed class UpdateCheck {
    /** A newer release exists. [apkUrl] is the .apk asset; [pageUrl] is the release web page. */
    data class Available(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
    ) : UpdateCheck()

    /** Installed build is the latest (or newer, e.g. a local debug build). */
    object UpToDate : UpdateCheck()

    /** Network error, no APK asset, bad placeholder repo, etc. [reason] is user-facing. */
    data class Failed(val reason: String) : UpdateCheck()
}

/**
 * Self-update: checks the project's GitHub Releases for a newer phone-app APK and, on
 * the user's confirmation, downloads and launches the system installer for it.
 *
 * All network work runs on a background thread; every callback is marshalled back to the
 * main thread, so callers can touch the UI directly without their own thread-hopping.
 */
class Updater(private val context: Context) {

    // One background thread for the blocking OkHttp calls + file download. Single-thread
    // is plenty: the user taps a button, we do one check or one download.
    private val io = Executors.newSingleThreadExecutor()

    // Posts callbacks to the UI thread. Looper.getMainLooper() is the main thread's
    // message queue; Handler.post() runs the block there.
    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // ignoreUnknownKeys: GitHub's release JSON has dozens of fields we don't model;
    // without this the decoder throws on the first unrecognised key.
    private val json = Json { ignoreUnknownKeys = true }

    // Built from BuildConfig (owner/repo injected at build time). Not a top-level const
    // because BuildConfig values aren't compile-time constants from Kotlin's view.
    private val latestReleaseUrl =
        "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    /** Human-readable current version, e.g. "1.2", for display next to the button. */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Hit the GitHub API, compare versions, and report the outcome on the main thread.
     * Safe to call from a click listener.
     */
    fun checkForUpdate(onResult: (UpdateCheck) -> Unit) {
        io.execute {
            val result = runCatching { fetchLatest() }.getOrElse { e ->
                Log.w(TAG, "update check failed", e)
                UpdateCheck.Failed(e.message ?: "Network error")
            }
            main.post { onResult(result) }
        }
    }

    private fun fetchLatest(): UpdateCheck {
        val reqBuilder = Request.Builder()
            .url(latestReleaseUrl)
            // GitHub recommends this Accept header for the REST API.
            .header("Accept", "application/vnd.github+json")
        if (BuildConfig.GITHUB_TOKEN.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
        }

        http.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 404) {
                // Most likely the placeholder owner/repo hasn't been replaced, or there
                // are no published (non-draft) releases yet.
                return UpdateCheck.Failed("No releases found — check github.owner/repo (local.properties)")
            }
            if (!resp.isSuccessful) {
                return UpdateCheck.Failed("GitHub returned ${resp.code}")
            }
            val body = resp.body?.string()
                ?: return UpdateCheck.Failed("Empty response from GitHub")

            val release = json.decodeFromString<GithubRelease>(body)
            val latestTag = release.tagName.removePrefix("v")

            // Pick the PHONE-app APK. A release also carries the tv-app APK (and the
            // helper .jar), so we must NOT just grab the first .apk — that could be the
            // TV build. Prefer an asset whose name contains "phone"; only if none does
            // (e.g. a single-APK release) fall back to the first .apk.
            val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val apkAsset = apks.firstOrNull { it.name.contains("phone", ignoreCase = true) }
                ?: apks.firstOrNull()
                ?: return UpdateCheck.Failed("Latest release has no phone APK attached")

            return if (isNewer(latestTag, currentVersion)) {
                UpdateCheck.Available(
                    versionName = release.tagName,
                    notes = release.body.ifBlank { "No release notes." },
                    apkUrl = apkAsset.downloadUrl,
                    pageUrl = release.htmlUrl,
                )
            } else {
                UpdateCheck.UpToDate
            }
        }
    }

    /**
     * Download [apkUrl] to cacheDir/updates/ and launch the system installer.
     * [onProgress] reports 0..100 (or -1 when the total size is unknown); [onError]
     * fires with a user-facing message. Both are delivered on the main thread.
     *
     * If the user hasn't granted "install unknown apps" for us yet, this routes them to
     * that system settings screen instead of installing — they grant it once, then retry.
     */
    fun downloadAndInstall(
        apkUrl: String,
        onProgress: (percent: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Gate on the install-sources grant up front so we don't download then dead-end.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Send the user to "Install unknown apps" for THIS app. package: URI pre-selects us.
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            onError("Allow installing apps from AirRemote, then tap update again.")
            return
        }

        io.execute {
            val result = runCatching { downloadApk(apkUrl) { p -> main.post { onProgress(p) } } }
            main.post {
                result.onSuccess { file -> launchInstaller(file, onError) }
                result.onFailure { e ->
                    Log.w(TAG, "update download failed", e)
                    onError(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun downloadApk(apkUrl: String, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clear stale downloads so we never install a half-written or old APK.
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "airremote-update.apk")

        http.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Empty download body")
            val total = body.contentLength()   // -1 if the server doesn't send Content-Length
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
        return out
    }

    private fun launchInstaller(apk: File, onError: (String) -> Unit) {
        runCatching {
            // FileProvider turns the private file into a content:// URI the installer
            // can read. The authority MUST match the manifest's <provider> authority.
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                // Grant the installer one-shot read access to the content URI, and start
                // it as a new task (we're launching from a non-Activity context path).
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            Log.w(TAG, "launch installer failed", e)
            onError(e.message ?: "Couldn't open the installer")
        }
    }

    // Compare dotted version strings numerically: "1.10" > "1.9", "2.0" > "1.12".
    // A purely lexical String compare would get those wrong, so we split on '.' and
    // compare segment-by-segment as ints. Non-numeric junk in a segment counts as 0.
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.')
        val l = local.split('.')
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val lv = l.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (rv != lv) return rv > lv
        }
        return false   // equal → not newer
    }
}

// ─── GitHub REST shapes (only the fields we use; rest ignored via ignoreUnknownKeys) ──
// @SerialName maps the JSON key (snake_case) to our camelCase property.
@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)
