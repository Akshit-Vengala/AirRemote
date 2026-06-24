package com.airremote.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "InfoActivity"

/**
 * A no-UI launcher tile. tv-app is otherwise a service-only app, so this Activity
 * exists purely so the user has something to TAP in the TV's app list. It doesn't
 * open a screen — it shows a Toast with the service state and the TV's IP, then
 * finishes immediately. This saves the user from digging through Settings to find
 * the IP, and doubles as a manual way to (re)start MainService past the OEM
 * autostart block.
 *
 * Uses a translucent theme (see manifest) + finish() in onCreate so no window is
 * ever visibly drawn; only the Toast appears over whatever launcher is in front.
 */
class InfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture state BEFORE we (re)start the service, so we can tell the user
        // whether this tap started it or it was already up.
        val wasRunning = MainService.isRunning

        // Idempotent: starts MainService if down, otherwise just hits onStartCommand.
        runCatching { startForegroundService(Intent(this, MainService::class.java)) }
            .onFailure { Log.e(TAG, "startForegroundService failed: ${it.message}") }

        val ip = localIpv4() ?: "unavailable"
        val msg = if (wasRunning)
            "AirRemote service running — TV IP: $ip"
        else
            "AirRemote service started — TV IP: $ip"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Log.i(TAG, msg)

        // Nothing to show — close immediately. The Toast outlives the Activity.
        finish()
    }

    // First site-local IPv4 on an up, non-loopback interface. Enumerating interfaces
    // (rather than WifiManager) covers BOTH Wi-Fi and Ethernet — many TVs are wired.
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
