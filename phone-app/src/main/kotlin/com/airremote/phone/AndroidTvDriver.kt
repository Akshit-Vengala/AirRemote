package com.airremote.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.airremote.protocol.AppInfo
import com.airremote.protocol.KeyCode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AndroidTvDriver"
// Background WS troubleshooting: how many attempts, and the gap between them.
private const val MAX_WS_ATTEMPTS = 4
private const val WS_RETRY_DELAY_MS = 1_500L

/**
 * Driver for Android TV and Fire TV (same ADB + WebSocket path).
 *
 * Fire TV runs FireOS, which is a fork of AOSP Android — our tv-helper and tv-app
 * APKs install and run identically on it. The user enables ADB the same way
 * (Developer Options → ADB Debugging), connects to the same port 5555, and the
 * bootstrap (ensureTvAppInstalled → helper push → a11y grant → MainService start)
 * works without modification.
 *
 * Two managers are recreated on each connect() so reconnect works cleanly after
 * a disconnect() (OkHttp and the ADB executor both shut down in close/disconnect).
 */
class AndroidTvDriver(private val context: Context) : TvDriver {

    override val capabilities = TvCapabilities(airMouse = true, freeText = true)

    private var adbManager: AdbManager? = null
    private var wsClient: WsClient? = null
    private var airMouseListener: ((Boolean) -> Unit)? = null

    // Pending icon requests keyed by package, so several can be in flight at once
    // (prefetching all custom-button defaults on connect). A single overwritten
    // WsClient.onAppIcon listener would route every reply to the last request's
    // callback; this map keeps each request matched to its own callback.
    private val iconCallbacks = ConcurrentHashMap<String, (String, String) -> Unit>()

    // Schedules the background WS retries. Main looper so the closures can touch
    // wsClient without extra synchronisation.
    private val handler = Handler(Looper.getMainLooper())

    // Bumped on every connect()/disconnect() so retries scheduled for a previous
    // session abort instead of acting on a stale/closed wsClient.
    @Volatile private var wsGeneration = 0

    override fun setAirMouseListener(listener: (Boolean) -> Unit) {
        airMouseListener = listener
    }

    override fun connect(ip: String, onReady: () -> Unit, onFailure: (String) -> Unit) {
        // Tear down any previous session first (idempotent).
        handler.removeCallbacksAndMessages(null)
        val gen = ++wsGeneration
        wsClient?.close()
        wsClient = null
        adbManager?.close()

        val mgr = AdbManager(context)
        val ws  = WsClient()
        adbManager = mgr
        wsClient   = ws

        // Relay TV-initiated touch swipes (cursor-driven scrolling) to the warm helper.
        // The TV's a11y service can't inject touch itself, so it sends us the geometry
        // and we forward it over ADB. Set before connecting so no early swipe is missed.
        ws.onTouchSwipe = { x1, y1, x2, y2, dur -> mgr.sendSwipe(x1, y1, x2, y2, dur) }

        mgr.connect(ip,
            onSuccess = {
                // ADB is up → keys/text/volume (via tv-helper) work NOW. Report
                // ready immediately; the whole connection no longer hinges on the
                // air-mouse WebSocket. The WS comes up in the background below.
                onReady()
                attemptWs(ip, attempt = 0, gen = gen)
            },
            onFailure = onFailure,
        )
    }

    // Tries to open the air-mouse WebSocket; on failure, troubleshoots the TV side
    // (escalating from a gentle service start to a full restart that re-binds :8765)
    // and retries with a delay, up to MAX_WS_ATTEMPTS. Reports availability through
    // airMouseListener so the UI can enable/disable the aim button.
    private fun attemptWs(ip: String, attempt: Int, gen: Int) {
        if (gen != wsGeneration) return            // superseded by a newer connect/disconnect
        val ws = wsClient ?: return
        ws.connect(ip,
            onOpen = {
                if (gen != wsGeneration) return@connect
                Log.i(TAG, "air-mouse WS open (attempt $attempt)")
                airMouseListener?.invoke(true)
            },
            onFailure = { msg ->
                if (gen != wsGeneration) return@connect
                airMouseListener?.invoke(false)
                if (attempt + 1 >= MAX_WS_ATTEMPTS) {
                    Log.w(TAG, "air-mouse WS unavailable after ${attempt + 1} attempts: $msg")
                    return@connect
                }
                // Escalate troubleshooting on the TV side before retrying:
                //   attempt 0 → just retry (WsServer may still be binding on cold start)
                //   attempt 1 → gentle start (in case MainService isn't running)
                //   attempt 2+ → hard restart (recreates a dead/failed WsServer)
                when (attempt) {
                    0 -> Log.i(TAG, "WS failed ($msg) — retrying")
                    1 -> adbManager?.startTvService()
                    else -> adbManager?.restartTvService()
                }
                handler.postDelayed({ attemptWs(ip, attempt + 1, gen) }, WS_RETRY_DELAY_MS)
            },
        )
    }

    override fun disconnect() {
        wsGeneration++                             // abort any pending WS retries
        handler.removeCallbacksAndMessages(null)
        wsClient?.close()
        wsClient   = null
        adbManager?.close()
        adbManager = null
    }

    // Each delegate is a null-safe forwarding call. AdbManager and WsClient already
    // log a warning if a send is attempted before connect (shellStream==null / socket==null),
    // so no extra guard is needed here.
    override fun sendKey(code: KeyCode)       { adbManager?.sendKey(code) }
    override fun sendLongPress(code: KeyCode) { adbManager?.sendLongPress(code) }
    override fun sendText(text: String)        { adbManager?.sendText(text) }
    override fun sendBackspaces(count: Int)    { adbManager?.sendBackspaces(count) }
    override fun sendEnter()                   { adbManager?.sendEnter() }
    override fun setVolume(percent: Int)       { adbManager?.setVolume(percent) }
    override fun launchApp(pkg: String)        { adbManager?.launchApp(pkg) }

    // App-shortcut picker. The reply listener is set on the WsClient right before the
    // request goes out; replies land on OkHttp's reader thread (caller marshals to UI).
    // One picker is open at a time, so overwriting the listener per call is fine.
    override fun requestAppList(onResult: (List<AppInfo>) -> Unit) {
        val ws = wsClient ?: return
        ws.onAppList = onResult
        ws.sendRequestAppList()
    }
    override fun requestAppIcon(pkg: String, onResult: (pkg: String, pngBase64: String) -> Unit) {
        val ws = wsClient ?: return
        iconCallbacks[pkg] = onResult
        // The dispatcher routes each reply to the callback registered for its
        // package, then removes it (one reply per request).
        ws.onAppIcon = { p, b64 -> iconCallbacks.remove(p)?.invoke(p, b64) }
        ws.sendRequestAppIcon(pkg)
    }

    override fun fetchDeviceName(onResult: (String?) -> Unit) {
        adbManager?.fetchDeviceName(onResult) ?: onResult(null)
    }

    override fun cursorVisible(visible: Boolean) { wsClient?.sendCursorVisible(visible) }
    override fun cursorMove(dx: Int, dy: Int)    { wsClient?.sendGyro(dx, dy) }
    override fun sendClick()                     { wsClient?.sendClick() }
    override fun sendHoverFocus(enabled: Boolean) { wsClient?.sendHoverFocus(enabled) }
    override fun sendScrollSensitivity(value: Int) { wsClient?.sendScrollSensitivity(value) }
    override fun sendSwipeStrength(horizontal: Int, vertical: Int) { wsClient?.sendSwipeStrength(horizontal, vertical) }

    override fun installApkOnTv(apk: File, onResult: (Boolean, String) -> Unit) {
        adbManager?.installApkOnTv(apk, onResult) ?: onResult(false, "Not connected to a TV")
    }
}
