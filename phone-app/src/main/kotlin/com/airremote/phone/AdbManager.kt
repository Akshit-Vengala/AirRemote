package com.airremote.phone

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.airremote.protocol.KeyCode
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "AdbManager"

// Remote path where we push the helper APK on every connect.
// /data/local/tmp/ is shell-user-writable and persists across reboots.
private const val REMOTE_HELPER_PATH = "/data/local/tmp/airremote-helper.jar"

// Asset name (matches the rename in phone-app/build.gradle.kts → copyHelperToAssets).
private const val HELPER_ASSET_NAME = "airremote-helper.jar"

// Unix file mode for the pushed helper: 0o644 = rw-r--r--.
// Kotlin has no octal literal — must write the decimal. The shell user needs
// read; nobody needs execute (it's loaded via app_process, not exec'd directly).
private const val HELPER_FILE_MODE = 420  // 0o644

// tv-app bundling — parallels the helper constants above.
private const val TV_APP_PACKAGE = "com.airremote.tv"
private const val TV_APP_ASSET_NAME = "airremote-tv.apk"
private const val REMOTE_TV_APP_PATH = "/data/local/tmp/airremote-tv.apk"

// Scratch path for installing a suggested-store APK on the TV (see installApkOnTv).
// One reused path is fine — installs are serialized through the single-thread executor.
private const val REMOTE_STORE_APK_PATH = "/data/local/tmp/airremote-store.apk"

// Manages a persistent ADB connection to the TV's ADB daemon (port 5555).
//
// On connect, pushes the tv-helper APK (bundled as an asset) to /data/local/tmp/
// and launches it via `app_process` as the shell user (UID 2000). The helper is
// a long-lived JVM that reads commands like `key 22\n` from its stdin and calls
// InputManager.injectInputEvent directly — no per-key process spawn, no JVM
// cold-start. See Plan 3 in the plan file.
//
// Shell has INJECT_EVENTS via group membership and bypasses hidden-API
// enforcement, so reflection-based injection works without any extra grants.
class AdbManager(private val context: Context) {

    // RSA key pair used to authenticate with the TV's ADB daemon.
    // `by lazy` — generated/loaded on first access, not at construction time.
    // Stored in app-private storage so the same key survives app restarts.
    // First connection to a TV that hasn't seen this key will show a dialog:
    // "Allow USB debugging from this computer?" — approve it with the physical
    // TV remote or via PC ADB: `adb shell input keyevent 23`
    private val keyPair: AdbKeyPair by lazy { loadOrCreateKeyPair() }

    // Extracted to a named function so the return type is explicit — Kotlin's
    // type inference inside a `lazy {}` block was widening the if/else result to Any.
    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val priv = File(context.filesDir, "adb_key")
        val pub  = File(context.filesDir, "adb_key.pub")
        // generate() writes the key pair directly to the two files and returns Unit —
        // there are no separate write methods. read() then loads them into an AdbKeyPair.
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    // Single background thread for ADB I/O.
    // Dadb.create() and dadb.shell() are blocking — they must never run on
    // the Android main thread, or they will trigger an ANR (Application Not Responding).
    //
    // BOUNDED queue (size 8) with `DiscardOldestPolicy`:
    //   - One worker thread (core=max=1).
    //   - When the queue is full, the OLDEST queued task is dropped and the new one
    //     takes its place. The currently in-flight `shell()` call is NOT interrupted.
    //   - This kills the "buffered keys replay after release" problem: if the user
    //     spams D-pad faster than the TV can drain, stale taps fall off the back of
    //     the queue rather than piling up unboundedly (the default behaviour of
    //     `Executors.newSingleThreadExecutor()`, which uses an unbounded LinkedBlockingQueue).
    //   - Why discard OLDEST not NEWEST: the user's most recent intent is more relevant
    //     than the stale one. Dropping recent taps would feel like the app ignored them.
    //   - Why size 8: comfortably covers a short burst (~5-8 quick D-pad presses) so
    //     legitimate rapid navigation drains naturally; further taps are treated as
    //     "input the user already regrets" and dropped.
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        /* corePoolSize    */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime   */ 0L,
        /* unit            */ TimeUnit.MILLISECONDS,
        /* workQueue       */ ArrayBlockingQueue(8),
        /* handler         */ ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    // @Volatile: writes on the executor thread are immediately visible when
    // the main thread checks isConnected(). Without it the CPU could cache a stale value.
    @Volatile private var dadb: Dadb? = null

    // PERSISTENT INTERACTIVE SHELL — Plan 2.
    //
    // Opened once per connect via `dadb.openShell("")` (interactive shell, no command).
    // Per key, we write `input keyevent <code>\n` to its stdin instead of doing a fresh
    // `dadb.shell(cmd)` (which internally opens a new shell stream, runs the command,
    // reads stdout/stderr until EOF, then closes). That per-call open/close was the
    // dominant cost of the old path on this TV (~650 ms per key).
    //
    // Why dadb's openShell() helps: under the hood it opens a `shell,v2,raw:` ADB stream
    // and returns an `AdbShellStream` whose `.write(String)` packetizes the bytes as an
    // ID_STDIN frame and flushes. So one TCP/ADB packet per key, fire-and-forget.
    //
    // What it does NOT fix: TV-side `input keyevent` still cold-starts `app_process` (a
    // fresh JVM) per call — that's ~80-200 ms residual. Plan 3 (TV-side helper that
    // keeps a JVM warm) would eliminate that.
    //
    // We never read from the stream — `input keyevent` produces no stdout, and we
    // don't need exit codes. Stderr from any error would accumulate in the socket but
    // the volume is negligible.
    @Volatile private var shellStream: AdbShellStream? = null

    fun connect(ip: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        executor.execute {
            runCatching {
                // Reset any prior connection state before opening a new one.
                shellStream?.runCatching { close() }
                shellStream = null
                dadb?.close()
                dadb = null

                // Dadb.create() constructs a Dadb instance but does NOT open the
                // TCP socket — the socket is opened lazily on first use. So this
                // line "succeeds" for any IP, even an unreachable one. We force a
                // real round-trip with a tiny shell command; if the TV is off or
                // the IP is wrong, shell() throws and we treat it as failure.
                val probe = Dadb.create(ip, 5555, keyPair)

                // Wrap the probe shell call in an explicit 5s timeout.
                //
                // Why a SEPARATE one-shot executor: we want `Future.get(5s)`, but our
                // main `executor` is already running THIS connect block on its only
                // thread — submitting another task to it and calling .get() would
                // deadlock (the inner task waits for a thread we're holding).
                //
                // The throwaway executor has its own thread that runs the blocking
                // shell call; the connect-thread waits up to 5s for the result.
                //
                // On TimeoutException we close `probe` to unblock the socket read
                // (Thread.interrupt() alone does NOT interrupt blocking socket I/O on
                // Android — only closing the underlying socket does). The throwaway
                // executor's thread will then exit cleanly.
                val probeExecutor = Executors.newSingleThreadExecutor()
                val response = try {
                    probeExecutor.submit<String> { probe.shell("echo ok").output.trim() }
                        .get(5, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    probe.close()
                    throw RuntimeException("TV did not respond within 5s (off or wrong IP?)")
                } finally {
                    probeExecutor.shutdownNow()
                }
                check(response == "ok") { "Unexpected shell response: '$response'" }

                // ─── Ensure tv-app is installed at the bundled version ───────
                // Runs BEFORE helper push because without tv-app there's no
                // MainService / WsServer / InputService — nothing else works.
                // No-op fast path when versionCode already matches (one shell
                // round-trip), so normal connects pay essentially no cost.
                ensureTvAppInstalled(probe)

                // ─── Push the helper APK (Plan 3) ────────────────────────────
                // Always overwrite — the helper is small (~40 KB) and re-pushing
                // eliminates any version skew across app upgrades or partial pushes.
                // dadb.push uses its own ADB sync stream internally — independent
                // of the shell streams we open above/below.
                val helperFile = extractHelperToCache()
                probe.push(helperFile, REMOTE_HELPER_PATH, mode = HELPER_FILE_MODE)
                Log.i(TAG, "Helper pushed to TV: $REMOTE_HELPER_PATH (${helperFile.length()} bytes)")

                // Open the persistent interactive shell ONCE here, on the connect
                // thread, so the first sendKey() doesn't pay an open-shell round-trip.
                // openShell("") with empty command = `shell,v2,raw:` (interactive).
                val newShell = probe.openShell()

                // ─── Launch the helper (Plan 3) ──────────────────────────────
                // The shell `exec` builtin REPLACES the shell process with the helper —
                // no leftover `sh` wrapper, and our stdin pipes straight to the helper.
                // `CLASSPATH=…` prefix sets the env var for just this exec'd process
                // (sh per-command env-var syntax). --nice-name makes the helper show
                // up as `airremote-helper` in `ps`, easier to find/kill while debugging.
                newShell.write(
                    "CLASSPATH=$REMOTE_HELPER_PATH exec app_process / " +
                        "--nice-name=airremote-helper com.airremote.helper.MainKt\n"
                )

                // ─── Auto-setup the TV side (sidequest fix) ──────────────────
                // Done inline on the connect thread, BEFORE we publish `dadb` /
                // signal success, so by the time the UI says "Connected" the
                // a11y service is granted and MainService is up. Otherwise the
                // first WS connect can race InputService bind.
                //
                // These use `probe.shell(...)` (one-shot streams) — independent
                // of `newShell` (the persistent helper stdin), so we don't
                // poison the helper's command stream.
                ensureA11yEnabled(probe)
                probe.shell("am start-foreground-service -n com.airremote.tv/.MainService")

                dadb = probe
                shellStream = newShell
                Log.i(TAG, "ADB connected to $ip:5555 (helper launched, persistent shell open, TV setup done)")
            }.fold(
                onSuccess = { onSuccess() },
                onFailure = { e ->
                    Log.e(TAG, "ADB connect failed", e)
                    shellStream?.runCatching { close() }
                    shellStream = null
                    dadb = null
                    onFailure(e.message ?: "Connection failed")
                }
            )
        }
    }

    fun sendKey(code: KeyCode) {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendKey called but persistent shell not open")
            return
        }
        // Map our shared KeyCode enum to Android's KEYCODE_* integer values.
        // `input keyevent <n>` on the TV shell interprets these integers the
        // same way Android's KeyEvent class does.
        val androidCode = when (code) {
            KeyCode.DPAD_UP     -> KeyEvent.KEYCODE_DPAD_UP
            KeyCode.DPAD_DOWN   -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyCode.DPAD_LEFT   -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyCode.DPAD_RIGHT  -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyCode.OK          -> KeyEvent.KEYCODE_DPAD_CENTER  // confirm / select
            KeyCode.BACK        -> KeyEvent.KEYCODE_BACK
            KeyCode.HOME        -> KeyEvent.KEYCODE_HOME
            KeyCode.VOLUME_UP   -> KeyEvent.KEYCODE_VOLUME_UP
            KeyCode.VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
            KeyCode.POWER       -> KeyEvent.KEYCODE_POWER
        }
        executor.execute {
            // Write `key <code>\n` to the helper's stdin. The helper (running as
            // a warm JVM via app_process) parses the line and calls
            // InputManager.injectInputEvent directly — no per-key process spawn,
            // no JVM cold-start. AdbShellStream.write flushes for us.
            runCatching {
                stream.write("key $androidCode\n")
            }.onFailure { e ->
                Log.e(TAG, "key $androidCode ($code) failed", e)
                // Connection likely broken — reset both so the UI shows disconnected
                // and the next connect() rebuilds from scratch.
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // Long-press a key. Reuses sendKey's KeyCode→KEYCODE_* mapping, but writes the
    // helper's `longpress` command so the TV side synthesises a HELD key (DOWN,
    // long-press repeat, UP) instead of a quick tap. Used for OK → open a tile's
    // context menu in launchers (flauncher etc.).
    fun sendLongPress(code: KeyCode) {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendLongPress called but persistent shell not open")
            return
        }
        val androidCode = when (code) {
            KeyCode.DPAD_UP     -> KeyEvent.KEYCODE_DPAD_UP
            KeyCode.DPAD_DOWN   -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyCode.DPAD_LEFT   -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyCode.DPAD_RIGHT  -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyCode.OK          -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyCode.BACK        -> KeyEvent.KEYCODE_BACK
            KeyCode.HOME        -> KeyEvent.KEYCODE_HOME
            KeyCode.VOLUME_UP   -> KeyEvent.KEYCODE_VOLUME_UP
            KeyCode.VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
            KeyCode.POWER       -> KeyEvent.KEYCODE_POWER
        }
        executor.execute {
            runCatching {
                stream.write("longpress $androidCode\n")
            }.onFailure { e ->
                Log.e(TAG, "longpress $androidCode ($code) failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    fun sendText(text: String) {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendText called but persistent shell not open")
            return
        }
        // The helper protocol is one command per line — an embedded newline would
        // split the text into two commands, and the second half would be logged
        // as "unknown command". Replace with spaces so word boundaries survive
        // a pasted multi-line input.
        val sanitized = text.replace('\n', ' ').replace('\r', ' ')
        if (sanitized.isEmpty()) return
        executor.execute {
            runCatching {
                // One ADB packet, one network write — regardless of length. The
                // helper does the per-char keystroke expansion on the TV side.
                stream.write("text $sanitized\n")
            }.onFailure { e ->
                Log.e(TAG, "text send failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // Drive a real touchscreen swipe on the TV by forwarding to the warm helper's
    // `swipe` command. The TV's a11y service (tv-app) computes the swipe geometry and
    // sends it to us over the WebSocket (TouchSwipeMessage → AndroidTvDriver); we relay
    // it here. This is the scroll path for touch-handling apps (CloudStream etc.) where
    // AccessibilityService.dispatchGesture is dropped by the TV's input dispatcher.
    // Mirrors sendKey: one fire-and-forget write to the helper stdin, off the UI thread.
    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendSwipe called but persistent shell not open")
            return
        }
        executor.execute {
            runCatching {
                stream.write("swipe $x1 $y1 $x2 $y2 $durationMs\n")
            }.onFailure { e ->
                Log.e(TAG, "swipe failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // Live-mirror primitives. Both reuse the helper's existing `key <int>` command,
    // so no helper-protocol changes are needed.

    fun sendBackspaces(count: Int) {
        if (count <= 0) return
        val stream = shellStream ?: run {
            Log.w(TAG, "sendBackspaces called but persistent shell not open")
            return
        }
        executor.execute {
            runCatching {
                // KEYCODE_DEL = 67 (Android's "backspace" — deletes char left of cursor).
                // Each line is one helper command → one injected DOWN+UP pair.
                // For LAN ADB, writes are fast enough that even N=50 (used by hold-to-clear)
                // completes in well under a second; we don't bother coalescing.
                repeat(count) { stream.write("key 67\n") }
            }.onFailure { e ->
                Log.e(TAG, "backspace send failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    fun sendEnter() {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendEnter called but persistent shell not open")
            return
        }
        executor.execute {
            runCatching {
                // KEYCODE_ENTER = 66. In a focused EditText this typically fires the
                // text-field's editor action (search submit, IME action, etc.).
                stream.write("key 66\n")
            }.onFailure { e ->
                Log.e(TAG, "enter send failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // ── Gyro / air-mouse primitives (Phase 2 of gyro plan) ─────────────
    //
    // Both write a single text line to the helper's stdin and return immediately
    // (fire-and-forget through the same bounded executor as sendKey). The helper
    // (Phase 3) parses these lines and synthesises MotionEvents with
    // SOURCE_MOUSE — Android's built-in system pointer renders in apps that
    // support mouse input (YouTube, Chrome, sideloaded apps).
    //
    // Wire protocol:
    //   mouse <dx> <dy>\n   — signed integer pixel delta from current cursor pos
    //   click\n             — primary-button click at current cursor pos
    //
    // Cursor state (absolute x, y) lives in the helper, not here — phone is
    // stateless about cursor position. See plan's "Architecture decisions".

    fun sendMouseMove(dx: Int, dy: Int) {
        // No-op packets just waste a network write. GyroReader already throttles
        // to ≤125Hz; this skip drops samples below the 1px-of-motion threshold
        // (rounded-down floats land on dx=dy=0 during very slow movement or
        // when the user is holding the phone steady mid-aim).
        if (dx == 0 && dy == 0) return
        val stream = shellStream ?: run {
            Log.w(TAG, "sendMouseMove called but persistent shell not open")
            return
        }
        executor.execute {
            runCatching {
                stream.write("mouse $dx $dy\n")
            }.onFailure { e ->
                Log.e(TAG, "mouse $dx $dy failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    fun sendClick() {
        val stream = shellStream ?: run {
            Log.w(TAG, "sendClick called but persistent shell not open")
            return
        }
        executor.execute {
            runCatching {
                // Click queues BEHIND any in-flight mouse-moves on the same
                // single-thread executor, so the helper's cursor is already at
                // its final position by the time the click is processed. This
                // is what makes "stop gyro before sending click" actually
                // deliver the click at the pre-release position rather than at
                // a release-jitter position.
                stream.write("click\n")
            }.onFailure { e ->
                Log.e(TAG, "click failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // Absolute volume control. Phone always speaks in percentage (0..100); the
    // helper translates to the device's actual STREAM_MUSIC index using the max
    // it queried at boot. That means we don't need to know — or even care — that
    // some Android TVs use max=15 and others max=30.
    //
    // Like sendKey, this is fire-and-forget through the bounded executor. Under a
    // rapid SeekBar drag the oldest queued `vol` writes get dropped (DiscardOldest),
    // but the *last* drag position always survives because onStopTrackingTouch
    // fires one final unthrottled call — so the resting volume always matches the
    // slider's resting position.
    fun setVolume(percent: Int) {
        val stream = shellStream ?: run {
            Log.w(TAG, "setVolume called but persistent shell not open")
            return
        }
        // coerceIn is Kotlin's clamp — guards against UI bugs that could send a
        // negative or >100 value. The helper also clamps, but cheap to do here too.
        val clamped = percent.coerceIn(0, 100)
        executor.execute {
            runCatching {
                stream.write("vol $clamped\n")
            }.onFailure { e ->
                Log.e(TAG, "vol $clamped failed", e)
                shellStream?.runCatching { close() }
                shellStream = null
                dadb = null
            }
        }
    }

    // Launch a TV app by package name. `monkey` resolves the package's launcher
    // activity itself, so we don't need to know the exact Activity/Intent. Android
    // TV apps register under the LEANBACK_LAUNCHER category; sideloaded phone apps
    // only have the normal LAUNCHER category, so we fall back to it with `||`
    // (monkey exits non-zero + prints "No activities found" when a category misses).
    //
    // Runs on a one-shot shell stream (like startTvService) — independent of the
    // persistent helper stdin, since launching an app isn't an input-injection
    // command the helper understands.
    fun launchApp(pkg: String) {
        val d = dadb ?: run {
            Log.w(TAG, "launchApp called but not connected")
            return
        }
        executor.execute {
            runCatching {
                val out = d.shell(
                    "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 " +
                        "|| monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                ).output.trim()
                Log.i(TAG, "launchApp $pkg: $out")
            }.onFailure { Log.w(TAG, "launchApp $pkg failed: ${it.message}") }
        }
    }

    // Best-effort friendly TV name for the status line (e.g. "OnePlus TV").
    // Prefers the user-set device name (Settings.Global.DEVICE_NAME, what the TV
    // shows for Cast / Bluetooth), falls back to the hardware model. Runs on the ADB
    // executor and calls back with null if neither is available. The callback fires
    // on the executor thread — the caller marshals to the UI thread.
    fun fetchDeviceName(onResult: (String?) -> Unit) {
        val d = dadb ?: return onResult(null)
        executor.execute {
            val name = runCatching {
                val n = d.shell("settings get global device_name").output.trim()
                if (n.isNotEmpty() && n != "null") n
                else d.shell("getprop ro.product.model").output.trim().ifEmpty { null }
            }.getOrNull()
            onResult(name)
        }
    }

    // ─── Suggested-apps mini-store install ───────────────────────────────
    //
    // Installs an arbitrary APK (already downloaded to the phone by
    // SuggestedAppsRepository) onto the connected TV. Reuses the exact path that
    // installs tv-app: push to /data/local/tmp, then `pm install -r -t`.
    //
    // Runs on the same single-thread executor as every other ADB op, so a big push
    // can't race the persistent shell — installs are serialized with input commands.
    // onResult(success, message) fires on the EXECUTOR thread; the caller marshals to
    // the UI thread (see MainActivity).
    fun installApkOnTv(apk: File, onResult: (success: Boolean, message: String) -> Unit) {
        val d = dadb
        if (d == null) {
            onResult(false, "Not connected to a TV")
            return
        }
        executor.execute {
            try {
                d.push(apk, REMOTE_STORE_APK_PATH, mode = HELPER_FILE_MODE)
                Log.i(TAG, "store APK pushed (${apk.length()} bytes)")
                // -t allows test-only APKs (Studio debug builds); -r reinstalls keeping data.
                val out = d.shell("pm install -r -t $REMOTE_STORE_APK_PATH").output.trim()
                Log.i(TAG, "store pm install: $out")
                when {
                    out.contains("Success") -> onResult(true, "Installed")
                    // Signature mismatch with an existing copy. We DON'T auto-uninstall a
                    // third-party app (that would wipe its user data) — just report it.
                    out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                        onResult(false, "A different build is already installed on the TV")
                    out.contains("INSTALL_FAILED_ALREADY_EXISTS") ->
                        onResult(false, "Already installed on the TV")
                    else -> onResult(false, out.ifBlank { "Install failed" })
                }
            } catch (e: Exception) {
                Log.e(TAG, "store install failed", e)
                onResult(false, e.message ?: "Install failed")
            } finally {
                // Best-effort cleanup of the scratch APK on the TV.
                runCatching { d.shell("rm -f $REMOTE_STORE_APK_PATH") }
            }
        }
    }

    // ─── tv-app auto-install ─────────────────────────────────────────────
    //
    // Checks whether com.airremote.tv is installed on the TV and at the
    // bundled versionCode (BuildConfig.TV_APP_VERSION_CODE). If missing or
    // stale, pushes the bundled APK and runs `pm install -r`.
    //
    // Signature-mismatch handling: if the TV has an old tv-app signed with a
    // different key (e.g. side-loaded build vs phone-app's bundled debug),
    // `pm install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. We catch
    // that, run `pm uninstall com.airremote.tv`, and retry. The uninstall
    // wipes the a11y grant too — but ensureA11yEnabled() re-grants right
    // after, so self-healing.
    //
    // Why before helper push: the helper is just stdin-driven input
    // injection; tv-app is what owns WsServer + InputService. If a fresh TV
    // doesn't even have tv-app, nothing else matters until it's installed.
    private fun ensureTvAppInstalled(d: Dadb) {
        // Probe installed versionCode. `dumpsys package` lists every package's
        // metadata; the first `versionCode=NNN minSdk=…` line is the installed
        // version. If the package isn't installed at all, the dumpsys block is
        // missing entirely and grep prints nothing.
        //
        // `head -1` because long-running installs (split APKs, system packages)
        // can have multiple versionCode lines; the first is canonical.
        val probe = d.shell(
            "dumpsys package $TV_APP_PACKAGE | grep -m1 versionCode="
        ).output.trim()

        // Parse "    versionCode=NNN minSdk=…" → NNN. If grep matched nothing,
        // probe is empty and the regex returns null → installed=null → install.
        val installedVersion: Int? = Regex("versionCode=(\\d+)")
            .find(probe)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        val bundledVersion = BuildConfig.TV_APP_VERSION_CODE
        if (installedVersion != null && installedVersion >= bundledVersion) {
            Log.i(TAG, "tv-app already up-to-date (installed=$installedVersion, bundled=$bundledVersion)")
            return
        }
        Log.i(TAG, "tv-app needs install: installed=$installedVersion, bundled=$bundledVersion")

        // Push the bundled APK to /data/local/tmp (shell-writable, same as helper).
        val apkFile = extractTvAppToCache()
        d.push(apkFile, REMOTE_TV_APP_PATH, mode = HELPER_FILE_MODE)
        Log.i(TAG, "tv-app APK pushed (${apkFile.length()} bytes)")

        // `pm install -r` reinstalls keeping data (irrelevant for us, no data).
        // The shell user has install permission via the INSTALL_PACKAGES
        // platform permission granted to the `shell` group.
        // `-t` allows test-only APKs: Android Studio injects android:testOnly="true"
        // into debug builds (and into the bundled tv-app APK when phone-app is built
        // via the Studio Run button), and `pm install` refuses those with
        // INSTALL_FAILED_TEST_ONLY unless -t is passed.
        val installOut = d.shell("pm install -r -t $REMOTE_TV_APP_PATH").output.trim()
        Log.i(TAG, "pm install -r -t: $installOut")

        // pm prints "Success" on the happy path; anything else is a failure
        // reason like "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: …]".
        if (installOut.contains("Success")) return

        if (installOut.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            // Signature mismatch — the installed APK was signed with a
            // different key than ours. Uninstall + reinstall. Loses any
            // app-private data, but tv-app currently has none.
            Log.w(TAG, "tv-app signature mismatch — uninstalling and retrying")
            val uninstallOut = d.shell("pm uninstall $TV_APP_PACKAGE").output.trim()
            Log.i(TAG, "pm uninstall: $uninstallOut")
            val retryOut = d.shell("pm install -t $REMOTE_TV_APP_PATH").output.trim()
            Log.i(TAG, "pm install (retry): $retryOut")
            check(retryOut.contains("Success")) { "tv-app install failed after uninstall: $retryOut" }
            return
        }

        // Any other failure — bubble up. Connect treats this as fatal.
        error("tv-app install failed: $installOut")
    }

    // Mirror of extractHelperToCache. Same justification — cacheDir is fine
    // for a re-derivable file, .use {} closes streams on exception.
    private fun extractTvAppToCache(): File {
        val cacheFile = File(context.cacheDir, TV_APP_ASSET_NAME)
        context.assets.open(TV_APP_ASSET_NAME).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    // Enables our AccessibilityService on the TV by APPENDING to the existing
    // enabled_accessibility_services list (does NOT overwrite — other services
    // like TalkBack stay enabled on TVs that have them).
    //
    // Runs entirely on the TV in one shell round-trip. The colon wrappers in the
    // grep (":$CUR:" / ":$SVC:") are the standard trick to avoid substring
    // false-matches — without them, "com.airremote.tv/.Input" would falsely
    // match "com.airremote.tv/.InputServiceExtra".
    //
    // `settings get` returns the literal string "null" when unset (not empty),
    // so we check both. `accessibility_enabled 1` is the master switch — always
    // set, idempotent.
    //
    // Kotlin escaping: in regular "" strings, `$ident` and `${...}` are
    // interpolated. `\$` produces a literal `$` for the shell.
    private fun ensureA11yEnabled(d: Dadb) {
        val svc = "com.airremote.tv/com.airremote.tv.InputService"
        val script =
            "SVC=$svc; " +
            "CUR=\$(settings get secure enabled_accessibility_services); " +
            "if [ \"\$CUR\" = \"null\" ] || [ -z \"\$CUR\" ]; then " +
                "settings put secure enabled_accessibility_services \"\$SVC\"; " +
            "elif ! echo \":\$CUR:\" | grep -q \":\$SVC:\"; then " +
                "settings put secure enabled_accessibility_services \"\$CUR:\$SVC\"; " +
            "fi; " +
            "settings put secure accessibility_enabled 1"
        val out = d.shell(script).output.trim()
        Log.i(TAG, "ensureA11yEnabled: ${out.ifEmpty { "ok" }}")
    }

    // Copies the bundled helper APK from app assets into the phone's cache dir,
    // returning a real File that dadb.push() can read from. Always overwrites —
    // the helper is tiny (~40 KB) and re-extracting on every connect eliminates
    // any stale-cache problems after an app upgrade.
    //
    // context.assets: the AssetManager — gives streaming access to anything under
    // src/main/assets/ in the APK. context.cacheDir: app-private writable storage
    // that Android may reclaim under storage pressure (fine for a re-derivable file).
    // `.use { … }` is Kotlin's try-with-resources — closes the stream even on
    // exception.
    private fun extractHelperToCache(): File {
        val cacheFile = File(context.cacheDir, HELPER_ASSET_NAME)
        context.assets.open(HELPER_ASSET_NAME).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    // ── TV-service recovery (used by AndroidTvDriver's WS troubleshooting) ──────
    //
    // Both run on the existing ADB connection via one-shot shell streams (like the
    // bootstrap calls in connect()), so they don't disturb the persistent helper
    // stdin stream. Used when the air-mouse WebSocket (:8765) won't connect.

    // Gentle: (re)start MainService. If it wasn't running, this brings it up. If it
    // was already running, this only hits onStartCommand (NOT onCreate) — so it will
    // NOT recreate a WsServer whose thread already died. Use restartTvService() for that.
    fun startTvService() {
        val d = dadb ?: return
        executor.execute {
            runCatching {
                d.shell("am start-foreground-service -n com.airremote.tv/.MainService")
                Log.i(TAG, "startTvService: issued")
            }.onFailure { Log.w(TAG, "startTvService failed: ${it.message}") }
        }
    }

    // Hard: force-stop the app then start it, guaranteeing a fresh process →
    // MainService.onCreate() → a brand-new WsServer that re-binds :8765 (with
    // SO_REUSEADDR it succeeds even over a TIME_WAIT socket). Force-stop kills the
    // tv-app process but NOT the tv-helper (separate shell-UID process), so keys
    // keep working; the a11y service rebinds automatically once the app restarts.
    fun restartTvService() {
        val d = dadb ?: return
        executor.execute {
            runCatching {
                d.shell("am force-stop com.airremote.tv")
                d.shell("am start-foreground-service -n com.airremote.tv/.MainService")
                Log.i(TAG, "restartTvService: force-stop + start issued")
            }.onFailure { Log.w(TAG, "restartTvService failed: ${it.message}") }
        }
    }

    val isConnected get() = dadb != null

    fun close() {
        shellStream?.runCatching { close() }
        shellStream = null
        dadb?.close()
        dadb = null
        executor.shutdown()
    }
}
