package com.airremote.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

private const val TAG          = "MainService"
private const val CHANNEL_ID   = "airremote_service"
private const val NOTIF_ID     = 1
const val WS_PORT              = 8765
private const val NSD_TYPE     = "_airremote._tcp"
// Ultimate fallback for the advertised name if the TV reports neither a
// user-set device name nor a model.
private const val NSD_NAME     = "AirRemote TV"

// Service is an Android component that runs in the background with no UI.
// Unlike an Activity, it has no screen — it just executes logic.
//
// "Foreground service" = a service the user is aware of (shown in the notification
// tray). The OS treats it differently: it's the last thing killed under memory
// pressure, and it survives the app going to the background.
class MainService : Service() {

    companion object {
        // True while the service is alive. Read by InfoActivity to decide whether
        // its tap STARTED the service or found it already running. @Volatile so the
        // write on the main thread is visible to the activity immediately.
        @Volatile
        var isRunning = false
            private set
    }

    // `lateinit var` tells the compiler: "I'll initialise this before using it —
    // skip the null-safety checks." If you read it before init, you get
    // UninitializedPropertyAccessException at runtime.
    private lateinit var wsServer: WsServer
    private lateinit var nsdManager: NsdManager

    // NsdManager calls these callbacks on an internal thread; just log the outcome.
    private val nsdRegistrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "mDNS registration failed: $code")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "mDNS unregistration failed: $code")
        }
        // NsdManager may rename the service (e.g. "AirRemote (2)") to avoid conflicts.
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS registered as '${info.serviceName}'")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS unregistered")
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Android 8+: you MUST call startForeground() within 5 seconds of onCreate().
        // If you don't, the OS throws a ForegroundServiceDidNotStartInTimeException
        // and kills the service. So we do it first, before anything slow.
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // WsServer.start() spins up Java-WebSocket's own I/O thread — non-blocking.
        // applicationContext is handed in so the server can read the PackageManager
        // for the app-shortcut picker (list launchable apps + fetch one icon).
        wsServer = WsServer(WS_PORT, applicationContext)
        wsServer.start()
        Log.i(TAG, "Service started — WebSocket server on port $WS_PORT")

        // Advertise over mDNS so phone-app's NsdDiscovery can find this TV
        // automatically without the user typing an IP address.
        // Service type "_airremote._tcp" is our private convention; we publish
        // the WebSocket port (8765) as the resolved port so the phone can connect.
        // Advertise under the TV's own name so Discover on the phone shows e.g.
        // "OnePlus TV" / "Living Room TV" instead of a generic "AirRemote".
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = NSD_TYPE
            port        = WS_PORT
        }
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)

        isRunning = true
    }

    // Called each time startService() / startForegroundService() is invoked.
    // onCreate() runs only once (first start); onStartCommand() runs every time.
    //
    // START_STICKY: if the OS kills this service due to low memory, restart it
    // automatically once memory is available again (with a null Intent).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::nsdManager.isInitialized) {
            runCatching { nsdManager.unregisterService(nsdRegistrationListener) }
        }
        // stop(timeoutMs) closes all open WebSocket connections and shuts down
        // the server thread. 1 000 ms gives clients time to receive the close frame.
        runCatching { wsServer.stop(1_000) }
        Log.i(TAG, "Service destroyed")
    }

    // "Bound service" is a second Service mode where a client component gets
    // a direct IBinder reference to call methods on us. We don't need that —
    // our service is fully self-contained. null = "not bindable."
    override fun onBind(intent: Intent?): IBinder? = null

    // The TV's display name for mDNS: prefer the user-set device name (the
    // friendly name shown in system settings / Cast), fall back to the hardware
    // model (e.g. "OnePlus TV"), then to a generic constant if both are blank.
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: NSD_NAME

    private fun createNotificationChannel() {
        // NotificationChannel is required on API 26+. Without it, notifications
        // are silently dropped. IMPORTANCE_LOW = no sound, no heads-up — just
        // a quiet persistent icon in the status bar.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirRemote Server",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AirRemote")
            .setContentText("Listening on port $WS_PORT…")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
}
