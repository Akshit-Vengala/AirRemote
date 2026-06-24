package com.airremote.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import com.airremote.protocol.AppIconMessage
import com.airremote.protocol.AppInfo
import com.airremote.protocol.AppListMessage
import com.airremote.protocol.ClickMessage
import com.airremote.protocol.CursorVisibilityMessage
import com.airremote.protocol.GyroMessage
import com.airremote.protocol.HoverFocusMessage
import com.airremote.protocol.ProtocolJson
import com.airremote.protocol.RemoteMessage
import com.airremote.protocol.RequestAppIconMessage
import com.airremote.protocol.RequestAppListMessage
import com.airremote.protocol.ScrollSensitivityMessage
import com.airremote.protocol.SwipeStrengthMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

private const val TAG = "WsServer"
// Pixel size we render app icons to before sending. 96px is crisp on a phone
// button without bloating the base64 payload (~5-10 KB/icon).
private const val ICON_SIZE_PX = 96

// `appContext` is the application Context (passed by MainService) used purely to
// read the PackageManager for the app-shortcut picker. It is NOT the
// AccessibilityService — app enumeration doesn't need a11y, so these queries work
// even if the user hasn't granted the service yet.
class WsServer(port: Int, private val appContext: Context) : WebSocketServer(InetSocketAddress(port)) {

    // Lets InputService reach the live server to push messages TV → phone (it holds
    // no WebSocket reference itself). Last-constructed instance wins, mirroring
    // InputService.instance. @Volatile: written on the MainService thread that builds
    // us, read on the a11y main thread that scrolls.
    companion object {
        @Volatile
        var instance: WsServer? = null
            private set
    }

    init {
        instance = this
        // SO_REUSEADDR: allow binding a port still in TIME_WAIT from a previous
        // instance. Without this, a quick restart — START_STICKY relaunch after a
        // low-memory kill, a phone reconnect, or force-stop+restart — fails with
        // BindException("Address already in use") and the server thread dies
        // SILENTLY: MainService stays up and ADB + mDNS keep working, so the only
        // visible symptom is the phone's WS connect being refused on :8765. Must be
        // set before start() (which MainService calls right after constructing us).
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val parsed = runCatching { ProtocolJson.decodeFromString<RemoteMessage>(message) }
            .onFailure { Log.w(TAG, "Parse error (raw='$message'): ${it.message}") }
            .getOrNull() ?: return

        // App-shortcut queries only read the PackageManager (via appContext), so
        // handle them BEFORE the InputService guard below — the picker must work
        // even when the a11y service isn't bound.
        when (parsed) {
            is RequestAppListMessage -> { handleAppListRequest(conn); return }
            is RequestAppIconMessage -> { handleAppIconRequest(conn, parsed.packageName); return }
            else -> { /* fall through to the input messages */ }
        }

        // ?. is the safe-call operator — if InputService.instance is null
        // (user hasn't enabled the a11y service yet) we just drop the message.
        // Logged so it's obvious during debugging that the service is missing.
        val svc = InputService.instance
        if (svc == null) {
            Log.w(TAG, "InputService not bound; dropping $parsed")
            return
        }

        // Gyro is the hot path — runs at sensor rate (~60-200 Hz). Keep it
        // free of allocations; ClickMessage is rare and can log.
        when (parsed) {
            is GyroMessage              -> svc.moveBy(parsed.dx, parsed.dy)
            is ClickMessage             -> { Log.i(TAG, "click"); svc.click() }
            is CursorVisibilityMessage  -> { Log.i(TAG, "cursor visible=${parsed.visible}"); svc.setCursorVisible(parsed.visible) }
            is HoverFocusMessage        -> { Log.i(TAG, "hoverFocus enabled=${parsed.enabled}"); svc.setHoverFocusEnabled(parsed.enabled) }
            is ScrollSensitivityMessage -> { Log.i(TAG, "scrollSensitivity=${parsed.value}"); svc.setScrollSensitivity(parsed.value) }
            is SwipeStrengthMessage     -> { Log.i(TAG, "swipeStrength h=${parsed.horizontal} v=${parsed.vertical}"); svc.setSwipeStrength(parsed.horizontal, parsed.vertical) }
            else                        -> Log.i(TAG, "Unhandled: $parsed")
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Client disconnected (remote=$remote, code=$code): $reason")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "Listening on port $port")
    }

    // Push a message to the connected phone (TV → phone). InputService uses this to
    // hand off touch swipes it can't inject itself. broadcast() fans out to every open
    // client socket — in practice the single phone. Cheap (queues bytes, non-blocking),
    // so it's safe to call from the a11y scroll path on the main thread.
    fun sendToPhone(msg: RemoteMessage) {
        runCatching { broadcast(ProtocolJson.encodeToString(msg)) }
            .onFailure { Log.w(TAG, "sendToPhone failed: ${it.message}") }
    }

    // ── App-shortcut picker support ─────────────────────────────────────────────
    // Both run on a throwaway background thread: PackageManager enumeration and
    // icon rendering are slow-ish, and we must not block Java-WebSocket's decoder
    // thread (it also pumps the hot gyro path). conn.send() is thread-safe.

    private fun handleAppListRequest(conn: WebSocket) {
        Thread {
            runCatching {
                val apps = launchableApps(appContext.packageManager)
                conn.send(ProtocolJson.encodeToString<RemoteMessage>(AppListMessage(apps)))
                Log.i(TAG, "Sent app list (${apps.size} apps)")
            }.onFailure { Log.w(TAG, "app list failed: ${it.message}") }
        }.start()
    }

    private fun handleAppIconRequest(conn: WebSocket, pkg: String) {
        Thread {
            runCatching {
                val drawable = appContext.packageManager.getApplicationIcon(pkg)
                val png = drawableToPngBase64(drawable)
                conn.send(ProtocolJson.encodeToString<RemoteMessage>(AppIconMessage(pkg, png)))
                Log.i(TAG, "Sent icon for $pkg (${png.length} b64 chars)")
            }.onFailure {
                Log.w(TAG, "icon for $pkg failed: ${it.message}")
                // Reply with an empty icon so the phone stops waiting and falls back
                // to its bundled logo / letter tile.
                runCatching { conn.send(ProtocolJson.encodeToString<RemoteMessage>(AppIconMessage(pkg, ""))) }
            }
        }.start()
    }

    // Every app that has a launcher entry. We query BOTH the TV (LEANBACK) and the
    // phone (LAUNCHER) categories so sideloaded phone apps appear too, dedupe by
    // package, and sort by label for a stable picker order.
    private fun launchableApps(pm: PackageManager): List<AppInfo> {
        fun query(category: String) =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

        return (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    // Rasterise an app icon Drawable (which may be adaptive, vector, or bitmap) onto
    // a fixed-size ARGB canvas, then PNG-compress and base64-encode it. NO_WRAP omits
    // the line breaks the default Base64 inserts (which would bloat the JSON).
    private fun drawableToPngBase64(drawable: Drawable): String {
        val bmp = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        Canvas(bmp).also { drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX); drawable.draw(it) }
        val bytes = ByteArrayOutputStream().use { baos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            baos.toByteArray()
        }
        bmp.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
