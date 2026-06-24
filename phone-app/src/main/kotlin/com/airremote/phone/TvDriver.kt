package com.airremote.phone

import com.airremote.protocol.AppInfo
import com.airremote.protocol.KeyCode
import java.io.File

/**
 * What a specific TV platform can do. MainActivity reads this after connect
 * to show or hide features in the UI (e.g. grey the aim button on Roku).
 */
data class TvCapabilities(
    /** True if the driver can stream a free-moving cursor and accept click events. */
    val airMouse: Boolean,
    /**
     * True if the driver can inject arbitrary strings (not just one character at a time).
     * Android/Fire/LG → true.  Samsung → false (on-screen keyboard only, app-dependent).
     * Roku → true via per-character Lit_ ECP commands.
     */
    val freeText: Boolean,
)

/**
 * One driver per TV platform.  The phone UI (DPadView, volume slider, live keyboard,
 * GyroReader) is platform-agnostic; it calls this interface for everything that touches
 * the TV.  Implementations decide the transport (ADB+WebSocket, HTTP ECP, WS+pairing…).
 *
 * Threading: every method may be called from the UI thread. Implementations are
 * responsible for moving blocking work off-thread (same contract as today's AdbManager).
 *
 * Lifecycle:
 *   connect() → [onReady fires] → send*() calls → disconnect()
 *   connect() may be called again after disconnect() for reconnect.
 */
interface TvDriver {

    val capabilities: TvCapabilities

    /**
     * Establish the connection.  [onReady] is called (on any thread) as soon as the
     * driver's PRIMARY transport is up and basic input (keys/text/volume) works —
     * for AndroidTvDriver that's the ADB connection, independent of the air-mouse
     * WebSocket, which is brought up separately (see [setAirMouseListener]).
     * [onFailure] is called with a human-readable message on any fatal error.
     */
    fun connect(ip: String, onReady: () -> Unit, onFailure: (String) -> Unit)

    /**
     * Register a listener for AIR-MOUSE transport availability (true = cursor/click
     * channel usable, false = down/unavailable). For AndroidTvDriver this tracks the
     * WebSocket, which connects after [onReady] and may be retried/troubleshot in the
     * background. Drivers without a separate air-mouse channel never invoke it.
     * No-op by default; set before calling [connect].
     */
    fun setAirMouseListener(listener: (available: Boolean) -> Unit) {}

    /** Tear down the connection and release resources. */
    fun disconnect()

    // ── Input ─────────────────────────────────────────────────────────────────

    fun sendKey(code: KeyCode)

    /**
     * Long-press a key — a HELD press rather than a tap. Used for OK (launchers like
     * flauncher open a tile's context menu) and POWER (the system shutdown / restart
     * menu). No-op by default; only AndroidTvDriver (which can synthesise a held key
     * via the helper) overrides it. Samsung's WS protocol has no hold semantics, so
     * it stays a no-op there.
     */
    fun sendLongPress(code: KeyCode) {}

    /** Insert a string of characters into the focused field. */
    fun sendText(text: String)

    /** Delete [count] characters to the left of the cursor. */
    fun sendBackspaces(count: Int)

    /** Submit / confirm the focused field (Enter key). */
    fun sendEnter()

    /**
     * Launch a TV app by its package name (custom shortcut buttons). No-op by
     * default; AndroidTvDriver runs it over ADB. Samsung's WS protocol has no
     * generic "launch arbitrary package" command, so it stays a no-op there.
     */
    fun launchApp(pkg: String) {}

    /**
     * Ask the TV for its launchable apps (for the custom-button picker). [onResult]
     * is invoked with package+label pairs — NO icons (those are fetched per-app via
     * [requestAppIcon] when one is bound). Called back on a BACKGROUND thread; the
     * caller marshals to the UI thread. No-op by default (only AndroidTvDriver,
     * which has the tv-app WebSocket, can answer).
     */
    fun requestAppList(onResult: (List<AppInfo>) -> Unit) {}

    /**
     * Ask the TV for one app's icon as a base64 PNG (sent when the user binds an app
     * to a custom button). [onResult] gives back (packageName, pngBase64); the PNG is
     * empty if the TV couldn't render it. Background thread; no-op by default.
     */
    fun requestAppIcon(pkg: String, onResult: (pkg: String, pngBase64: String) -> Unit) {}

    /**
     * Best-effort friendly TV name for the UI (e.g. "OnePlus TV"). Called back on a
     * background thread; null if unknown. No-op default — only AndroidTvDriver can
     * query it (over ADB); Samsung has no such channel.
     */
    fun fetchDeviceName(onResult: (String?) -> Unit) { onResult(null) }

    /**
     * Set absolute volume as a percentage 0–100.
     * Drivers that only support relative volume (e.g. Roku ECP) should track
     * the last value and send the appropriate number of VolumeUp / VolumeDown
     * keypresses.
     */
    fun setVolume(percent: Int)

    // ── Air-mouse (only meaningful when capabilities.airMouse == true) ────────

    /** Show or hide the on-screen cursor dot. No-op on platforms without air-mouse. */
    fun cursorVisible(visible: Boolean) {}

    /** Move the cursor by a pixel delta. No-op on platforms without air-mouse. */
    fun cursorMove(dx: Int, dy: Int) {}

    /** Fire a primary click at the cursor's current position. No-op without air-mouse. */
    fun sendClick() {}

    // ── Feature flags ─────────────────────────────────────────────────────────

    /**
     * Push the hover-to-focus preference to the TV side.
     * Only meaningful for AndroidTvDriver (tv-app InputService). No-op by default.
     */
    fun sendHoverFocus(enabled: Boolean) {}

    /**
     * Push the hover-to-scroll sensitivity (0..100) to the TV. Only meaningful for
     * AndroidTvDriver (tv-app InputService). No-op by default.
     */
    fun sendScrollSensitivity(value: Int) {}

    /**
     * Push per-axis touch-swipe strength (0..100 each) to the TV — how far a cursor-edge
     * scroll swipes horizontally vs vertically. Only meaningful for AndroidTvDriver
     * (tv-app InputService). No-op by default.
     */
    fun sendSwipeStrength(horizontal: Int, vertical: Int) {}

    // ── Suggested-apps mini-store ───────────────────────────────────────────────

    /**
     * Install an APK already downloaded to the phone onto the TV (the suggested-apps
     * store). Only AndroidTvDriver can do this (over ADB). [onResult] is (success,
     * message) and is called on a BACKGROUND thread — marshal to the UI thread in the
     * caller. Default reports "unsupported" so non-Android drivers degrade gracefully.
     */
    fun installApkOnTv(apk: File, onResult: (success: Boolean, message: String) -> Unit) {
        onResult(false, "Not supported on this TV")
    }
}
