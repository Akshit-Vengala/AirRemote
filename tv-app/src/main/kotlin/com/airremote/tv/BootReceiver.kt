package com.airremote.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Unique tag so this is distinguishable in logcat from other apps' boot
// receivers (e.g. the custom TV launcher also uses a generic "BootReceiver"
// tag). Filter with: adb logcat -s AirRemoteBoot
private const val TAG = "AirRemoteBoot"

/**
 * Starts [MainService] automatically when the TV finishes booting, so the
 * WebSocket server + mDNS advertisement come up WITHOUT the phone having to
 * launch the service over ADB first.
 *
 * Why this matters: mDNS advertisement lives inside MainService.onCreate(), but
 * the phone normally starts MainService via ADB — which requires already knowing
 * the TV's IP. That chicken-and-egg means Discover can't find a cold TV. With
 * this receiver the TV advertises itself from boot, so the phone can discover a
 * TV it has never connected to.
 *
 * A BroadcastReceiver is a component the OS instantiates just long enough to run
 * onReceive() for one event, then discards. It has no UI and no lifecycle of its
 * own — so all it does here is fire-and-forget a startForegroundService() call.
 *
 * Requires the RECEIVE_BOOT_COMPLETED permission and a manifest <receiver> with
 * an intent-filter for BOOT_COMPLETED (see AndroidManifest.xml).
 *
 * Caveat: this only brings up the WS/mDNS side. The keys/text path still needs
 * the phone's ADB connection (shell UID for tv-helper), and the Accessibility
 * service must still be enabled manually.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON is some OEMs' non-standard equivalent of BOOT_COMPLETED
        // (used after a "fast boot" / resume-from-deep-sleep). Accept both.
        Log.i(TAG, "onReceive action=${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "ignoring (not a boot action)")
            return
        }

        Log.i(TAG, "BOOT_COMPLETED received — starting MainService (mDNS + WS)")
        val serviceIntent = Intent(context, MainService::class.java)
        // startForegroundService (API 26+) is mandatory here: a background start
        // from a receiver is otherwise blocked. MainService must then call
        // startForeground() within ~5s (it does, first thing in onCreate()).
        runCatching { context.startForegroundService(serviceIntent) }
            .onSuccess { Log.i(TAG, "startForegroundService(MainService) issued") }
            .onFailure { Log.e(TAG, "startForegroundService FAILED: ${it.message}") }
    }
}
