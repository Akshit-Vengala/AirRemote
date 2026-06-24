package com.airremote.phone

import android.content.Context
import android.util.Base64
import android.util.Log
import com.airremote.protocol.KeyCode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG   = "TizenDriver"
private const val PORT  = 8001   // unencrypted; 8002 is WSS but requires trusting a self-signed cert
private const val APP_NAME = "AirRemote"

/**
 * Driver for Samsung Smart TVs (Tizen OS, 2016+ models).
 *
 * Transport: WebSocket on port 8001 (plain WS, no TLS).
 * Port 8002 uses WSS with a self-signed cert the TV issues; OkHttp rejects it by default.
 * Port 8001 still works on all tested 2016–2024 models.
 *
 * Pairing (first connection):
 *   1. We connect without a token — TV shows "Allow connection from AirRemote?" on-screen.
 *   2. The user accepts on the TV with their remote.
 *   3. TV sends back a JSON message that includes a `token` string.
 *   4. We save the token in SharedPreferences keyed by IP.
 *   5. All future connections include `&token=<saved>` in the URL — TV accepts silently.
 *
 * Capabilities:
 *   airMouse  = false — Samsung has no pointer/cursor concept over this protocol.
 *   freeText  = false — no text injection; only key-by-key on-screen-keyboard navigation.
 */
class TizenDriver(private val context: Context) : TvDriver {

    override val capabilities = TvCapabilities(airMouse = false, freeText = false)

    // OkHttp is already a dependency (same client library used by WsClient).
    // A fresh client per TizenDriver instance so disconnect() can fully shut down
    // its thread pool without affecting other connections.
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // @Volatile: the WebSocketListener callbacks run on OkHttp's dispatcher thread;
    // sendKey() is called from the UI thread. Volatile ensures the UI thread always
    // sees the latest socket reference without a lock.
    @Volatile private var socket: WebSocket? = null

    // We don't know the TV's actual volume, so we track only our last sent value.
    // Starts at 50% as a neutral midpoint; rapid slider drags will converge it toward
    // the real TV volume because each setVolume() call sends only the delta.
    private var lastVolumePct = 50

    // ── Token persistence ─────────────────────────────────────────────────────

    // `by lazy` — getSharedPreferences requires a Context call; defer until first use.
    // MODE_PRIVATE: only this app can read the file.
    private val prefs by lazy {
        context.getSharedPreferences("airremote_tizen", Context.MODE_PRIVATE)
    }

    private fun savedToken(ip: String): String? =
        prefs.getString("token_$ip", null)

    // apply() writes asynchronously (off the UI thread) — fine here.
    private fun saveToken(ip: String, token: String) =
        prefs.edit().putString("token_$ip", token).apply()

    // ── Connection ────────────────────────────────────────────────────────────

    override fun connect(ip: String, onReady: () -> Unit, onFailure: (String) -> Unit) {
        socket?.close(1000, "reconnect")
        socket = null

        // Samsung requires the app name to be base64-encoded in the URL.
        // Base64.NO_WRAP produces a single-line string with no newline characters.
        // URL-safe encoding (replace +/=) is not strictly required by the TV, but
        // NO_WRAP avoids the newline issue in the query string.
        val encodedName = Base64.encodeToString(APP_NAME.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val token       = savedToken(ip)

        // Include the saved token in the URL if we have one. The TV skips the
        // on-screen dialog and accepts immediately when the token is valid.
        val url = buildString {
            append("ws://$ip:$PORT/api/v2/channels/samsung.remote.control")
            append("?name=$encodedName")
            if (token != null) append("&token=$token")
        }

        Log.i(TAG, "Connecting to $ip (token=${token != null})")

        // Guard against firing onReady twice if the TV sends multiple messages
        // that each look like a connect event.
        var readyFired = false

        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // WS handshake succeeded, but we're NOT ready yet.
                // Samsung sends a separate JSON event after the user accepts the
                // pairing dialog (or immediately if the token is already valid).
                Log.i(TAG, "WS open — waiting for TV accept")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "TV→ $text")
                try {
                    val obj   = JSONObject(text)
                    val event = obj.optString("event", "")
                    val data  = obj.optJSONObject("data")

                    // Persist the token whenever the TV includes one in a message.
                    // This covers both the first-pair response and any re-issue.
                    data?.optString("token")?.takeIf { it.isNotEmpty() }?.let { newToken ->
                        Log.i(TAG, "Token received — saving for $ip")
                        saveToken(ip, newToken)
                    }

                    // The TV signals readiness via one of these events:
                    //   "ms.channel.connect"       — most models (2016–2022)
                    //   "ms.channel.clientConnect" — some 2018–2020 firmware
                    //   "ms.channel.authenticate"  — some pairing flows
                    // Matching on "connect" catches the first two; "authenticate" is
                    // the third. We fire onReady on whichever arrives first.
                    if (!readyFired &&
                        (event.contains("connect",      ignoreCase = true) ||
                         event.contains("authenticate", ignoreCase = true))) {
                        readyFired = true
                        Log.i(TAG, "TV ready (event=$event)")
                        onReady()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse TV message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                socket = null
                onFailure(t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed code=$code reason=$reason")
                socket = null
            }
        })
    }

    override fun disconnect() {
        socket?.close(1000, "disconnect")
        socket = null
        // Shut down OkHttp's thread pool so the process can exit cleanly.
        http.dispatcher.executorService.shutdown()
    }

    // ── Key sending ───────────────────────────────────────────────────────────

    // Samsung's remote-control wire format. TypeOfRemote must be exactly this string.
    // `Cmd: "Click"` is a single press (DOWN + UP in one message). Some firmwares
    // also accept "SendKey" — "Click" is the more widely supported variant.
    private fun sendTizenKey(tizenKey: String) {
        val json = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$tizenKey","TypeOfRemote":"SendRemoteKey"}}"""
        if (socket?.send(json) == false) {
            Log.w(TAG, "sendKey($tizenKey): send queue full or socket closed")
        }
        if (socket == null) Log.w(TAG, "sendKey($tizenKey): not connected")
    }

    // Map our shared KeyCode enum to Samsung's key name strings.
    // Full key name list: developer.samsung.com/smarttv/develop/api-references/
    //                     samsung-product-api-references/tizen-keycode.html
    override fun sendKey(code: KeyCode) {
        val tizenKey = when (code) {
            KeyCode.DPAD_UP     -> "KEY_UP"
            KeyCode.DPAD_DOWN   -> "KEY_DOWN"
            KeyCode.DPAD_LEFT   -> "KEY_LEFT"
            KeyCode.DPAD_RIGHT  -> "KEY_RIGHT"
            KeyCode.OK          -> "KEY_ENTER"
            KeyCode.BACK        -> "KEY_RETURN"   // Samsung's "back/exit" key
            KeyCode.HOME        -> "KEY_HOME"
            KeyCode.POWER       -> "KEY_POWER"
            KeyCode.VOLUME_UP   -> "KEY_VOLUP"
            KeyCode.VOLUME_DOWN -> "KEY_VOLDOWN"
        }
        sendTizenKey(tizenKey)
    }

    // Samsung has no text-injection API over this protocol.
    // The UI hides the keyboard panel when freeText=false, so these should never
    // be called — but they're safe no-ops if somehow invoked.
    override fun sendText(text: String) = Unit
    override fun sendBackspaces(count: Int) = Unit
    override fun sendEnter() = sendTizenKey("KEY_ENTER")

    // ── Volume ────────────────────────────────────────────────────────────────

    // Samsung has no absolute-volume command — only KEY_VOLUP / KEY_VOLDOWN presses.
    // We send (target − lastSent) presses. The slider is throttled to ~40ms, so a
    // normal drag sends small deltas (3–10 steps). A full 0→100 swing in one call
    // would send 100 presses; the TV handles this but the result will be approximate
    // because we don't know the TV's real starting volume.
    override fun setVolume(percent: Int) {
        val target = percent.coerceIn(0, 100)
        val delta  = target - lastVolumePct
        if (delta == 0) return
        val key = if (delta > 0) "KEY_VOLUP" else "KEY_VOLDOWN"
        repeat(abs(delta)) { sendTizenKey(key) }
        lastVolumePct = target
    }
}
