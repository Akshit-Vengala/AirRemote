package com.airremote.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

private const val TAG          = "NsdDiscovery"
private const val SERVICE_TYPE = "_airremote._tcp"

// IP + human name of a discovered AirRemote TV.
data class DiscoveredDevice(val name: String, val ip: String, val port: Int)

/**
 * Discovers AirRemote-capable TVs (Android TV / Fire TV running tv-app) on the
 * local network using mDNS (DNS-SD) via Android's NsdManager.
 *
 * Samsung TVs use SSDP/UPnP, not mDNS — they are not discoverable here.
 *
 * mDNS relies on IP multicast (224.0.0.251).  Android's WiFi driver filters out
 * multicast packets by default to save battery, so [startDiscovery] acquires a
 * [WifiManager.MulticastLock] for the duration; [stopDiscovery] releases it.
 * Requires CHANGE_WIFI_MULTICAST_STATE permission (declared in the manifest).
 *
 * Teardown is deliberately SYNCHRONOUS (in [stopDiscovery]) rather than waiting
 * for NsdManager's onDiscoveryStopped callback — that callback fires late or not
 * at all on some devices, which left discovery state + the multicast lock stuck
 * and made the *second* scan (e.g. after closing and reopening the app) silently
 * fail. We reset our own state immediately and treat the framework callbacks as
 * advisory logging only.
 */
class NsdDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // MulticastLock: tells the WiFi driver to pass mDNS multicast packets up the stack.
    // setReferenceCounted(false) = acquire/release are not nested; one release() is enough
    // regardless of how many acquire() calls were made.
    private val multicastLock: WifiManager.MulticastLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("airremote_nsd")
            .also { it.setReferenceCounted(false) }

    // One listener at a time; NsdManager rejects a second discoverServices() call
    // while one is already active (returns FAILURE_ALREADY_ACTIVE).
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Guards resolveService(): NsdManager allows only ONE resolve in flight. A
    // second concurrent resolve fails with FAILURE_ALREADY_ACTIVE and can wedge
    // the framework so later resolves never fire. We skip overlapping resolves.
    @Volatile private var resolving = false

    fun startDiscovery(
        onFound:   (DiscoveredDevice) -> Unit,
        onStopped: ()               -> Unit = {},
    ) {
        // Always fully reset first — idempotent, and clears any stuck state left
        // by a previous scan (or a previous Activity instance in the same process).
        stopDiscovery()

        if (!multicastLock.isHeld) multicastLock.acquire()
        resolving = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "Started ($type)")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "Found: ${info.serviceName} — resolving…")
                // Skip if a resolve is already in flight; NsdManager only handles
                // one at a time and a second wedges the framework.
                if (resolving) {
                    Log.i(TAG, "  (resolve already in progress — skipping)")
                    return
                }
                resolving = true
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "Resolve failed for '${info.serviceName}': $code")
                        resolving = false
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving = false
                        val ip = info.host?.hostAddress ?: return
                        Log.i(TAG, "Resolved '${info.serviceName}' → $ip:${info.port}")
                        onFound(DiscoveredDevice(info.serviceName, ip, info.port))
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "Lost: ${info.serviceName}")
            }

            override fun onDiscoveryStopped(type: String) {
                // Advisory only — stopDiscovery() already reset our state.
                Log.i(TAG, "Stopped")
                onStopped()
            }

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "Start failed: $code")
                // The discovery never started, so release our side immediately.
                releaseLock()
                if (discoveryListener === this) discoveryListener = null
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "Stop failed: $code")
            }
        }

        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            // IllegalArgumentException ("listener already in use") etc. — reset.
            Log.e(TAG, "discoverServices threw: ${it.message}")
            discoveryListener = null
            releaseLock()
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        resolving = false
        if (listener != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "stopDiscovery: ${it.message}") }
        }
        // Release the lock synchronously rather than waiting for onDiscoveryStopped.
        releaseLock()
    }

    private fun releaseLock() {
        if (multicastLock.isHeld) {
            runCatching { multicastLock.release() }
                .onFailure { Log.w(TAG, "lock release: ${it.message}") }
        }
    }
}
