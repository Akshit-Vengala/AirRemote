package com.airremote.phone

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
import com.airremote.protocol.TouchSwipeMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "WsClient"
private const val PORT = 8765

// Phone-side WebSocket client to the TV's WsServer (Phase D, gyro/click path).
//
// Why a separate transport from ADB:
//   ADB shell injection works for keys/text/volume (UID = shell has INJECT_EVENTS),
//   but synthetic MotionEvents are silently dropped by the TV input dispatcher on
//   this device. AccessibilityService.dispatchGesture is the working route, and
//   that lives in tv-app (regular app UID), reachable only over the network.
//
// Threading: OkHttp's WebSocket is thread-safe — send() can be called from any
// thread, and queues internally. The sensor thread in GyroReader will call
// sendGyro at ~60–200 Hz; no marshalling needed.
class WsClient {

    // The HTTP client is heavy to construct (pools, dispatcher threads) so we
    // keep one for the lifetime of the WsClient. pingInterval keeps the socket
    // alive across NAT timeouts and surfaces dead-peer faster than TCP would.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // @Volatile — writes by the listener thread (onOpen/onClosed) become
    // visible to the sensor thread that reads it in send(). Cheaper than a
    // lock; sufficient because we only ever assign (not read-modify-write).
    @Volatile
    private var socket: WebSocket? = null

    // App-shortcut reply listeners. Set by AndroidTvDriver right before sending the
    // matching request. INVOKED ON OKHTTP'S READER THREAD — consumers must marshal
    // to the main thread themselves (MainActivity uses runOnUiThread). @Volatile so
    // the assignment is visible to that reader thread.
    @Volatile var onAppList: ((List<AppInfo>) -> Unit)? = null
    @Volatile var onAppIcon: ((pkg: String, pngBase64: String) -> Unit)? = null

    // TV → phone touch-swipe relay. The TV's a11y service computes a scroll swipe it
    // can't inject itself and asks us to perform it (via the helper). Invoked on
    // OkHttp's reader thread; AndroidTvDriver forwards to AdbManager (its own executor),
    // so no UI marshalling is needed. @Volatile so the assignment is visible to that thread.
    @Volatile var onTouchSwipe: ((x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) -> Unit)? = null

    // connect is fire-and-forget. OkHttp performs the handshake on its own
    // dispatcher thread; we expose lifecycle via the callbacks rather than
    // blocking. Mirrors AdbManager.connect's shape so MainActivity wiring
    // stays uniform.
    fun connect(
        ip: String,
        onOpen: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) {
        // Tear down any previous socket first so reconnect is idempotent.
        // code 1000 = normal closure.
        socket?.close(1000, "reconnect")
        socket = null

        val request = Request.Builder()
            .url("ws://$ip:$PORT")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "open $ip:$PORT")
                onOpen()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "failure: ${t.message}")
                socket = null
                onFailure(t.message ?: "unknown")
            }

            // First inbound traffic on this socket — until the app-shortcut picker,
            // the wire was phone→TV only. Decode and fan out to the listeners.
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { ProtocolJson.decodeFromString<RemoteMessage>(text) }
                    .onFailure { Log.w(TAG, "recv parse error: ${it.message}") }
                    .getOrNull() ?: return
                when (msg) {
                    is AppListMessage -> onAppList?.invoke(msg.apps)
                    is AppIconMessage -> onAppIcon?.invoke(msg.packageName, msg.pngBase64)
                    is TouchSwipeMessage -> onTouchSwipe?.invoke(msg.x1, msg.y1, msg.x2, msg.y2, msg.durationMs)
                    else              -> Log.i(TAG, "recv unhandled: $msg")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closed code=$code reason=$reason")
                socket = null
            }
        })
    }

    fun sendGyro(dx: Int, dy: Int) {
        send(GyroMessage(dx.toDouble(), dy.toDouble()))
    }

    fun sendClick() {
        val ok = socket != null
        Log.i(TAG, "sendClick socketOpen=$ok")
        send(ClickMessage)
    }

    fun sendCursorVisible(visible: Boolean) {
        send(CursorVisibilityMessage(visible))
    }

    // Push the hover-to-focus preference to the TV. Sent when the user toggles
    // the checkbox AND once on every connect (so the TV's default-false state is
    // corrected to the user's actual choice). Safe to call when disconnected —
    // send() no-ops if the socket isn't open.
    fun sendHoverFocus(enabled: Boolean) {
        Log.i(TAG, "sendHoverFocus enabled=$enabled")
        send(HoverFocusMessage(enabled))
    }

    // Push the hover-to-scroll sensitivity (0..100) to the TV. Sent on change and once
    // per connect, same pattern as sendHoverFocus. No-ops if the socket isn't open.
    fun sendScrollSensitivity(value: Int) {
        send(ScrollSensitivityMessage(value))
    }

    // Push per-axis touch-swipe strength (0..100 each) to the TV. Sent on change and once
    // per connect. No-ops if the socket isn't open.
    fun sendSwipeStrength(horizontal: Int, vertical: Int) {
        send(SwipeStrengthMessage(horizontal, vertical))
    }

    // App-shortcut picker requests. Replies arrive asynchronously on onAppList /
    // onAppIcon above. Both no-op if the socket isn't open (send() guards).
    fun sendRequestAppList() { send(RequestAppListMessage) }
    fun sendRequestAppIcon(pkg: String) { send(RequestAppIconMessage(pkg)) }

    // OkHttp.WebSocket.send returns false if the outgoing queue is full or the
    // socket is closed/closing. We drop silently on the hot path — losing a
    // single 5ms gyro sample is invisible; logging at this rate would flood.
    private fun send(msg: RemoteMessage) {
        val s = socket ?: return
        val json = ProtocolJson.encodeToString(msg)
        s.send(json)
    }

    fun close() {
        socket?.close(1000, "client closing")
        socket = null
        // dispatcher().executorService().shutdown() lets the JVM exit promptly
        // when the Activity is destroyed; otherwise OkHttp's idle threads
        // linger for ~60s.
        http.dispatcher.executorService.shutdown()
    }
}
