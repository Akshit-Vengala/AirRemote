package com.airremote.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.airremote.protocol.AppInfo
import com.airremote.protocol.KeyCode
import java.io.File

private const val PREF_HOVER_FOCUS = "hover_to_focus"
// Aim (gyro) sensitivity 0..100 (50 = 1.0x) and hover-to-scroll sensitivity 0..100
// (50 = the TV's default), both set via the settings-panel sliders.
private const val PREF_GYRO_SENS = "gyro_sensitivity"
// Per-axis touch-swipe strength 0..100 (50 = default), set via the two settings-panel
// sliders. Replaces the old single PREF_SCROLL_SENS — the swipe path scrolls by distance,
// which the user wants tuned separately for horizontal vs vertical.
private const val PREF_SWIPE_H = "swipe_strength_h"
private const val PREF_SWIPE_V = "swipe_strength_v"
// Haptic feedback on/off (settings switch; default on).
private const val PREF_HAPTICS = "haptics_enabled"
// Custom-shortcut button → TV package name. Stored per slot ("custom_app_1".."_3").
// Rebindable via the app picker (Phase C); until then each falls back to a default.
private const val PREF_CUSTOM_APP_PREFIX = "custom_app_"
// Human label per slot (used for the letter-tile fallback + stale contentDescription).
// Stored when the user binds an app; falls back to DEFAULT_CUSTOM_LABELS.
private const val PREF_CUSTOM_APP_LABEL_PREFIX = "custom_app_label_"
// Default package per custom slot (Android TV package names). The picker will let
// the user override these; a missing/wrong package just fails the monkey launch
// harmlessly (logged, no crash).
private val DEFAULT_CUSTOM_APPS = listOf(
    "com.google.android.youtube.tv",     // YouTube (Android TV)
    "com.netflix.ninja",                 // Netflix (Android TV / Fire TV)
    "com.amazon.amazonvideo.livingroom", // Prime Video
)
private val DEFAULT_CUSTOM_LABELS = listOf("YouTube", "Netflix", "Prime Video")
// Last IP we successfully connected to (manual Connect OR a resolved Discover).
// Prefilled into the IP field on startup so a returning user just taps Connect,
// and used as a fallback when a later Discover scan fails to re-report the TV.
private const val PREF_LAST_IP = "last_tv_ip"
// How long to wait for mDNS to resolve before giving up and handing the user
// the manual path. NsdManager can silently fail to re-report a known service,
// so without this cap "Searching…" would hang forever.
private const val DISCOVER_TIMEOUT_MS = 8_000L
// Scale gyro deltas when hover-to-focus is on — cursor moves slower so focus
// doesn't thrash across adjacent tiles on small wrist motions.
private const val HOVER_FOCUS_GYRO_SCALE = 0.65f

// How long a button must be held before it counts as a long-press. Matches
// DPadView's own constant and Android's stock long-press feel (~500ms).
private const val LONG_PRESS_TIMEOUT_MS = 500L
// Power commits to its long-press (the TV's power/restart menu) faster than other
// keys, so a deliberate hold triggers the menu from the hold itself rather than
// surfacing ~1s later (after the release-tap would have fired). Short enough to
// feel like a hold, long enough that a normal tap stays a tap.
private const val POWER_LONG_PRESS_TIMEOUT_MS = 250L

// Supported TV platforms understood by this app.
// ANDROID_TV covers both Android TV and Fire TV — both run AOSP and use the
// same two-channel driver (ADB → tv-helper + WebSocket → tv-app).
private enum class TvPlatform(val label: String) {
    ANDROID_TV("Android TV / Fire TV"),
    SAMSUNG   ("Samsung (Tizen)"),
}

class MainActivity : Activity() {

    // The active driver; instantiated fresh on each Connect click.
    private var driver: TvDriver? = null

    // Self-update (GitHub Releases) + suggested-apps mini-store. Lazily created because
    // they need a Context; each runs its own background work and posts back to the UI
    // thread, so callers here can touch views directly in the callbacks.
    private val updater by lazy { Updater(this) }
    private val suggestedAppsRepo by lazy { SuggestedAppsRepository(this) }
    // One-shot guard so re-opening the settings panel doesn't refetch the curated list.
    private var suggestedAppsLoaded = false

    // Mirrors the platform spinner's current selection so Connect knows which
    // driver to create.
    private var selectedPlatform = TvPlatform.ANDROID_TV

    // Gyro sensor reader — `by lazy` defers construction until the aim button
    // is first touched, keeping startup fast.
    private val gyroReader by lazy { GyroReader(this) }

    // mDNS device scanner. Lazy for the same reason — don't touch WifiManager
    // until the user actually hits Discover.
    private val nsdDiscovery by lazy { NsdDiscovery(this) }

    // Running model of what text is currently on the TV screen. Diffed against
    // the EditText on every change to send only the delta (backspaces + insert).
    private var tvText: String = ""

    // What the status line calls the TV. Starts as the IP at connect time, then is
    // replaced with the TV's friendly name (e.g. "OnePlus TV") once fetchDeviceName
    // resolves it. Used everywhere the "Connected to …" copy is built.
    private var tvLabel: String = ""

    // Ready-gate: false until BOTH the driver's connect sequence is complete AND
    // (for Android TV) the WebSocket is open. All input handlers early-return
    // while !ready so no commands leak out before the TV side is alive.
    private var ready = false

    // Air-mouse (WebSocket) leg up? Gates the aim button + settings gear, separately
    // from `ready` (ADB). Implies `ready` — the WS only opens after ADB connects.
    private var airMouseReady = false

    // Cached aim-sensitivity multiplier (from the PREF_GYRO_SENS slider), applied to
    // every gyro delta. Updated when the slider moves (UI thread) and read on the
    // sensor thread, so @Volatile for visibility.
    @Volatile private var gyroSensFactor = 1f

    // Main-thread handler for the discovery timeout. Class-level (not the view's
    // handler) so onDestroy can cancel a pending timeout regardless of view scope.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pending "give up searching" callback; cancelled the moment a device is
    // found or the scan is otherwise torn down. Held so we can removeCallbacks().
    private var discoverTimeout: Runnable? = null

    // Consecutive failed Discover attempts. After 2, we reveal the (normally
    // hidden) manual IP row for troubleshooting. Reset to 0 on any successful find.
    private var discoverFailures = 0

    // True while an mDNS scan is in flight. Guards the Find TVs button: NsdManager's
    // start/stop is asynchronous, so re-pressing mid-scan stops then immediately
    // restarts discovery, which wedges the framework — it then only flashes
    // "Searching…" and dies on every press until the app is restarted. We ignore
    // presses while a scan is running and clear this at its DETERMINISTIC end points
    // (device found, or the timeout fires) — never from NsdManager's own stop
    // callback, which fires late or not at all on some devices.
    private var isDiscovering = false

    private val gatedControls = mutableListOf<View>()
    private val prefs by lazy { getSharedPreferences("airremote_prefs", Context.MODE_PRIVATE) }

    // Views that are needed from multiple functions are resolved lazily so they
    // can be fields while still being safe to access any time after setContentView.
    private val dpadView       by lazy { findViewById<DPadView>(R.id.dpadView) }
    // These were ImageButton / Button before the neumorphism rehaul; they're now
    // NeumorphCardView (a FrameLayout subclass) wrapping an icon/label, so we hold
    // them as plain View — every use here is setOnClickListener / isEnabled / alpha
    // / visibility, all of which live on View.
    private val aimButton      by lazy { findViewById<View>(R.id.aimButton) }
    private val aimDimOverlay  by lazy { findViewById<View>(R.id.aimDimOverlay) }
    private val settingsButton by lazy { findViewById<View>(R.id.settingsButton) }
    private val keyboardBtn    by lazy { findViewById<View>(R.id.keyboardButton) }
    private val backButton     by lazy { findViewById<View>(R.id.backButton) }
    private val homeButton     by lazy { findViewById<View>(R.id.homeButton) }
    private val powerButton    by lazy { findViewById<View>(R.id.powerButton) }
    private val statusDot      by lazy { findViewById<View>(R.id.statusDot) }
    private val connectOverlay by lazy { findViewById<View>(R.id.connectOverlay) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ipField      = findViewById<EditText>(R.id.ipField)
        val statusText   = findViewById<TextView>(R.id.statusText)
        // connectButton / discoverButton are now NeumorphCardView (a FrameLayout
        // subclass) after the overlay restyle — resolve as View; every use here is
        // setOnClickListener / performClick, both of which live on View.
        val connectBtn   = findViewById<View>(R.id.connectButton)
        val platformSpinner = findViewById<Spinner>(R.id.platformSpinner)
        val discoverBtn  = findViewById<View>(R.id.discoverButton)
        val discoveredList = findViewById<LinearLayout>(R.id.discoveredList)
        // Connect overlay (Discover / IP) + its in-overlay status line.
        val statusPill        = findViewById<View>(R.id.statusPill)
        val closeConnectBtn   = findViewById<ImageButton>(R.id.closeConnectButton)
        val overlayStatus     = findViewById<TextView>(R.id.overlayStatus)

        // The status pill opens the connect overlay; the close button and a tap on
        // the scrim (the overlay's own click, since the card consumes its taps)
        // dismiss it.
        statusPill.setOnClickListener {
            connectOverlay.visibility = View.VISIBLE
            // Expand the panel from the top-centre, like the pill unfolding downward.
            findViewById<View>(R.id.connectPanel).startAnimation(expandFrom(0.5f, 0f))
        }
        closeConnectBtn.setOnClickListener { connectOverlay.visibility = View.GONE }
        connectOverlay.setOnClickListener { connectOverlay.visibility = View.GONE }

        // Prefill the IP: prefer the last IP we successfully connected to so a
        // returning user can just tap Connect. `?:` (elvis) falls back to the
        // build-time default when nothing has been saved yet.
        ipField.setText(prefs.getString(PREF_LAST_IP, null) ?: BuildConfig.TV_IP)

        // ── Platform picker ────────────────────────────────────────────────
        // Populate the spinner from the TvPlatform enum. The spinner shows the
        // platform labels; we use the ordinal to look up the enum entry on selection.
        val platforms = TvPlatform.entries.toTypedArray()
        platformSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            platforms.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        platformSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedPlatform = platforms[pos]
                // Preview: hide/show features based on the chosen platform BEFORE
                // connecting so the UI reflects what will be available.
                applyCapabilities(previewCapabilities())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Fills the (hidden) IP field with a discovered device's address, ensures
        // the Android TV platform is selected, then triggers the normal Connect.
        // Used by the tappable device buttons below.
        fun connectToIp(ip: String) {
            ipField.setText(ip)
            platformSpinner.setSelection(platforms.indexOf(TvPlatform.ANDROID_TV))
            connectBtn.performClick()
        }

        // Renders a discovered TV as a tappable neumorphic pill (item_discovered_tv)
        // showing its name. Tapping connects. Replaces any previously shown result
        // and reveals the list. `attachToRoot = false` so we set up the view before
        // adding it ourselves (the standard inflate-into-a-list pattern).
        fun showDiscoveredDevice(device: DiscoveredDevice) {
            discoveredList.removeAllViews()
            val card = layoutInflater.inflate(R.layout.item_discovered_tv, discoveredList, false)
            card.findViewById<TextView>(R.id.deviceName).text = device.name
            card.setOnClickListener { connectToIp(device.ip) }
            discoveredList.addView(card)
            discoveredList.visibility = View.VISIBLE
        }

        // ── Discover (mDNS) ────────────────────────────────────────────────
        // Scans the local network for AirRemote TVs (Android TV / Fire TV running
        // tv-app). Samsung TVs use SSDP not mDNS — they won't appear here.
        // On success we show the TV's name as a tappable button (tap = connect);
        // we do NOT auto-connect, so the user picks. After two failed scans the
        // hidden manual-IP row is revealed for troubleshooting.
        discoverBtn.setOnClickListener {
            // Ignore re-presses while a scan is already running (see isDiscovering) —
            // restarting NsdManager mid-scan wedges it.
            if (isDiscovering) return@setOnClickListener
            isDiscovering = true
            nsdDiscovery.stopDiscovery()   // cancel any previous scan first
            discoverTimeout?.let { mainHandler.removeCallbacks(it) }
            discoveredList.removeAllViews()
            discoveredList.visibility = View.GONE
            overlayStatus.visibility = View.VISIBLE
            overlayStatus.text = "Searching for AirRemote TVs on the local network…"

            // `found` flips true on the first resolved device. Both the timeout
            // and onStopped check it so a late teardown can't stomp a good result.
            var found = false

            // Fires if mDNS never delivers a result (NsdManager can fail to
            // re-report a service it already knows about). Counts the miss; after
            // two consecutive failures, reveal the manual IP row for troubleshooting.
            val timeout = Runnable {
                if (found) return@Runnable
                isDiscovering = false
                nsdDiscovery.stopDiscovery()
                discoverFailures++
                overlayStatus.visibility = View.VISIBLE
                // Auto-fallback: mDNS rides multicast, which the TV's WiFi can drop
                // when idle (screensaver) even though it's still reachable by unicast.
                // So if we have a last-known IP, just try connecting to it directly
                // instead of making the user type one in. The saved IP self-heals on
                // every successful Discover, so it's almost always still correct.
                val lastIp = prefs.getString(PREF_LAST_IP, null)
                if (lastIp != null) {
                    overlayStatus.text = "Couldn't find your TV — trying last address $lastIp…"
                    connectToIp(lastIp)
                } else {
                    // No IP to fall back to (first run) — point the user at manual entry.
                    overlayStatus.text = "No TV found. Enter its IP below and tap Connect."
                }
            }
            discoverTimeout = timeout
            mainHandler.postDelayed(timeout, DISCOVER_TIMEOUT_MS)

            nsdDiscovery.startDiscovery(
                onFound = { device ->
                    runOnUiThread {
                        if (found) return@runOnUiThread   // ignore extra announcements
                        found = true
                        isDiscovering = false
                        discoverFailures = 0
                        mainHandler.removeCallbacks(timeout)
                        nsdDiscovery.stopDiscovery()
                        // Remember this IP so a later failed scan has a fallback.
                        prefs.edit().putString(PREF_LAST_IP, device.ip).apply()
                        overlayStatus.visibility = View.VISIBLE
                        overlayStatus.text = "Found ${device.name} — tap it to connect."
                        showDiscoveredDevice(device)
                    }
                },
                onStopped = {
                    runOnUiThread {
                        // If we stopped while still "Searching…" with nothing found
                        // and no timeout copy yet, clear the in-overlay status.
                        if (!found && overlayStatus.text.startsWith("Searching")) {
                            overlayStatus.visibility = View.GONE
                        }
                    }
                },
            )
        }

        // ── Connect ────────────────────────────────────────────────────────
        connectBtn.setOnClickListener {
            val ip = ipField.text.toString().trim()
            // Start by labelling the TV with its IP; fetchDeviceName upgrades this to
            // the friendly name on a successful connect.
            tvLabel = ip
            setReady(false)
            // Stop any in-flight Discover, cancel its pending timeout (so it can't fire
            // a spurious auto-connect after we've committed here), and clear the guard
            // so Find TVs works again next time.
            nsdDiscovery.stopDiscovery()
            discoverTimeout?.let { mainHandler.removeCallbacks(it) }
            isDiscovering = false
            // Close the connect overlay — status now shows on the pill behind it.
            connectOverlay.visibility = View.GONE
            // Clear the discovered-device list once we commit to a connection.
            discoveredList.removeAllViews()
            discoveredList.visibility = View.GONE

            // Tear down any previous session (idempotent).
            driver?.disconnect()

            val newDriver: TvDriver = when (selectedPlatform) {
                TvPlatform.ANDROID_TV -> AndroidTvDriver(this)
                TvPlatform.SAMSUNG    -> TizenDriver(this)
            }
            driver = newDriver

            // Air-mouse (WebSocket) availability is tracked separately from the ADB
            // connection: the driver fires onReady as soon as ADB is up (keys work),
            // then brings up — and troubleshoots — the WS air-mouse in the background.
            // This listener enables/disables just the aim controls as the WS leg
            // comes and goes, and pushes the hover-focus pref once WS is actually up.
            newDriver.setAirMouseListener { available ->
                runOnUiThread {
                    if (driver !== newDriver) return@runOnUiThread
                    setAirMouseReady(available)
                    if (available) {
                        newDriver.sendHoverFocus(isHoverFocusEnabled())
                        newDriver.sendSwipeStrength(prefs.getInt(PREF_SWIPE_H, 50), prefs.getInt(PREF_SWIPE_V, 50))
                        // Pull the REAL icons for the custom-button apps from the TV
                        // (where they're actually installed) and cache them, so the
                        // buttons show official icons instead of letter tiles.
                        prefetchCustomIcons()
                    }
                    if (ready && newDriver.capabilities.airMouse) {
                        statusText.text =
                            if (available) "Connected to $tvLabel"
                            else "Connected to $tvLabel\nAir-mouse unavailable — retrying…"
                    }
                }
            }

            // Platform-specific status copy. Samsung users need to know to watch
            // their TV for the pairing dialog on first connect.
            statusText.text = when (selectedPlatform) {
                TvPlatform.ANDROID_TV -> "Connecting to $ip…"
                TvPlatform.SAMSUNG    ->
                    "Connecting to $ip…\nIf first time: accept the prompt on your TV."
            }

            newDriver.connect(
                ip = ip,
                onReady = {
                    runOnUiThread {
                        // Guard: a second Connect click replaces `driver` before this
                        // callback fires. Don't let the stale callback overwrite the
                        // UI state of the new connection attempt.
                        if (driver !== newDriver) return@runOnUiThread
                        // onReady = primary (ADB) transport up → basic remote works.
                        // The air-mouse WS connects in the background; the aim controls
                        // stay disabled until setAirMouseListener reports it's up.
                        statusText.text = if (newDriver.capabilities.airMouse)
                            "Connected to $tvLabel\nAir-mouse connecting…"
                        else
                            "Connected to $tvLabel"
                        setReady(true)
                        // Remember this IP now that we know it connects — next launch
                        // prefills it, and Discover's fallback can reuse it. apply()
                        // writes asynchronously off the main thread.
                        if (ip.isNotBlank()) prefs.edit().putString(PREF_LAST_IP, ip).apply()
                        // Authoritative capabilities from the connected driver.
                        applyCapabilities(newDriver.capabilities)
                        // Upgrade the status label from the IP to the TV's friendly
                        // name (e.g. "OnePlus TV") once it resolves over ADB. We swap
                        // only the first line so any air-mouse sub-status is preserved.
                        newDriver.fetchDeviceName { name ->
                            runOnUiThread {
                                if (driver !== newDriver || name.isNullOrBlank()) return@runOnUiThread
                                tvLabel = name
                                val cur = statusText.text.toString()
                                if (cur.startsWith("Connected to ")) {
                                    val rest = cur.substringAfter('\n', "")
                                    statusText.text = if (rest.isEmpty()) "Connected to $tvLabel"
                                        else "Connected to $tvLabel\n$rest"
                                }
                            }
                        }
                    }
                },
                onFailure = { msg ->
                    runOnUiThread {
                        if (driver !== newDriver) return@runOnUiThread
                        statusText.text = when (selectedPlatform) {
                            TvPlatform.ANDROID_TV ->
                                "Failed: $msg\nCheck TV for 'Allow debugging?' dialog."
                            TvPlatform.SAMSUNG ->
                                "Failed: $msg\nVerify the TV IP and that the TV is on."
                        }
                        setReady(false)
                    }
                },
            )
        }

        // Pressing Enter (the "Done" action — see imeOptions in the layout) in the
        // IP field connects, instead of just dismissing the keyboard.
        ipField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideSoftKeyboard(ipField)
                connectBtn.performClick()
                true
            } else false
        }

        // ── D-pad ──────────────────────────────────────────────────────────
        dpadView.onKey = DPadView.OnKeyListener { code ->
            if (!ready) return@OnKeyListener
            driver?.sendKey(code)
        }
        // Hold OK to open a tile's context menu (launchers like flauncher).
        // No-op on drivers without hold support (e.g. Samsung).
        dpadView.onLongOk = DPadView.OnLongOkListener {
            if (!ready) return@OnLongOkListener
            driver?.sendLongPress(KeyCode.OK)
        }

        wireKey(R.id.backButton,  KeyCode.BACK)
        wireKey(R.id.homeButton,  KeyCode.HOME)
        // Power supports a long-press: a held POWER triggers the TV's shutdown /
        // restart menu (the system global-actions dialog), same as holding the
        // hardware power button. A quick tap still sends a single POWER (sleep/wake).
        wireKeyWithLongPress(R.id.powerButton, KeyCode.POWER, POWER_LONG_PRESS_TIMEOUT_MS)

        // ── Volume arc (left arc around the D-pad, drawn by DPadView) ─────────
        // Android TV → absolute percent via the helper.
        // Samsung     → incremental KEY_VOLUP / KEY_VOLDOWN; see TizenDriver.setVolume().
        // DPadView only fires onVolume when the user actually changes the level
        // (knob-drag or double-tap), so a brush never moves volume. We throttle to
        // ~40ms to avoid flooding the transport during a fast drag.
        var lastVolumeDispatchMs = 0L
        dpadView.onVolume = onVolume@{ level ->
            if (!ready) return@onVolume
            val now = SystemClock.uptimeMillis()
            if (now - lastVolumeDispatchMs < 40L) return@onVolume
            lastVolumeDispatchMs = now
            driver?.setVolume(level)
        }

        // ── Keyboard typing UI (live mirror) ─────────────────────────────
        val textInputRow = findViewById<LinearLayout>(R.id.textInputRow)
        val textField    = findViewById<BackspaceAwareEditText>(R.id.textField)
        val enterBtn     = findViewById<Button>(R.id.sendButton)

        keyboardBtn.setOnClickListener {
            if (!ready) return@setOnClickListener
            Haptics.buttonPress(it)
            if (textInputRow.visibility == View.VISIBLE) {
                textInputRow.visibility = View.GONE
                hideSoftKeyboard(textField)
            } else {
                textInputRow.visibility = View.VISIBLE
                textField.requestFocus()
                showSoftKeyboard(textField)
            }
        }

        textField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!ready) return
                syncToTv(s?.toString() ?: "")
            }
        })

        textField.onEmptyBackspace = {
            if (ready) driver?.sendBackspaces(1)
        }

        val onEnter = {
            if (ready) {
                driver?.sendEnter()
                hideSoftKeyboard(textField)
            }
        }
        enterBtn.setOnClickListener { onEnter() }
        textField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { onEnter(); true } else false
        }

        // ── Custom app-shortcut buttons ─────────────────────────────────────
        // Each tap launches a TV app by package name (per-slot pref, defaults
        // above). monkey on the TV resolves the launcher activity, so a tap is all
        // it takes. Long-press to rebind lands with the app picker (Phase C).
        wireCustomAppButton(R.id.customButton1, slot = 1)
        wireCustomAppButton(R.id.customButton2, slot = 2)
        wireCustomAppButton(R.id.customButton3, slot = 3)

        // ── Air-mouse aim button ───────────────────────────────────────────
        // wireAimButton sets the touch listener once; applyCapabilities() controls
        // visibility. The listener is harmless when the button is GONE.
        syncAimButtonPosition(aimButton)
        wireAimButton()

        // ── Settings panel (overlay; replaces the old PopupMenu) ─────────────
        val settingsOverlay  = findViewById<View>(R.id.settingsOverlay)
        val hoverFocusSwitch = findViewById<Switch>(R.id.hoverFocusSwitch)
        val gyroSensSeek     = findViewById<SeekBar>(R.id.gyroSensSeek)
        val swipeHSeek       = findViewById<SeekBar>(R.id.swipeHSeek)
        val swipeVSeek       = findViewById<SeekBar>(R.id.swipeVSeek)

        hoverFocusSwitch.isChecked = isHoverFocusEnabled()
        hoverFocusSwitch.setOnCheckedChangeListener { _, checked ->
            setHoverFocusEnabled(checked)
            driver?.sendHoverFocus(checked)
        }

        val hapticsSwitch = findViewById<Switch>(R.id.hapticsSwitch)
        Haptics.enabled = prefs.getBoolean(PREF_HAPTICS, true)
        hapticsSwitch.isChecked = Haptics.enabled
        hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            Haptics.enabled = checked
            prefs.edit().putBoolean(PREF_HAPTICS, checked).apply()
        }

        gyroSensSeek.progress = prefs.getInt(PREF_GYRO_SENS, 50)
        gyroSensFactor = computeGyroFactor(gyroSensSeek.progress)
        gyroSensSeek.setOnSeekBarChangeListener(seekListener { value ->
            prefs.edit().putInt(PREF_GYRO_SENS, value).apply()
            gyroSensFactor = computeGyroFactor(value)
        })

        // Two swipe-strength sliders (horizontal / vertical). Each persists its value and
        // pushes BOTH axes together (the TV message carries both), reading the other axis
        // from prefs so a one-slider change doesn't reset the other.
        swipeHSeek.progress = prefs.getInt(PREF_SWIPE_H, 50)
        swipeHSeek.setOnSeekBarChangeListener(seekListener { value ->
            prefs.edit().putInt(PREF_SWIPE_H, value).apply()
            driver?.sendSwipeStrength(value, prefs.getInt(PREF_SWIPE_V, 50))
        })
        swipeVSeek.progress = prefs.getInt(PREF_SWIPE_V, 50)
        swipeVSeek.setOnSeekBarChangeListener(seekListener { value ->
            prefs.edit().putInt(PREF_SWIPE_V, value).apply()
            driver?.sendSwipeStrength(prefs.getInt(PREF_SWIPE_H, 50), value)
        })

        // Shortcut-button rows: each opens the picker for its slot. The same
        // openAppPicker the long-press uses, just surfaced as a tappable row so
        // it's discoverable. Labels are refreshed here and after every rebind.
        for (slot in 1..3) {
            customSlotRow(slot).setOnClickListener { openAppPicker(slot) }
        }
        refreshCustomSlotRows()

        // ── Updates row ─────────────────────────────────────────────────────
        // Show the current version up front; the row kicks off a GitHub check.
        findViewById<TextView>(R.id.updateStatus).text =
            getString(R.string.current_version, updater.currentVersion)
        findViewById<TextView>(R.id.checkUpdatesRow).setOnClickListener { checkForUpdates() }

        settingsButton.setOnClickListener { showSettingsPanel() }
        settingsOverlay.setOnClickListener { it.visibility = View.GONE }

        // ── Ready-gate ─────────────────────────────────────────────────────
        // Note: aimButton + settingsButton are NOT in this list — they're gated by
        // air-mouse (WebSocket) availability via setAirMouseReady(), independently of
        // the ADB-based controls below, which are enabled the moment ADB connects.
        // Note: the volume arc is part of dpadView, so it's gated implicitly when
        // dpadView is disabled (DPadView.onTouchEvent early-returns while !isEnabled).
        gatedControls.addAll(listOf(
            dpadView, backButton, homeButton, powerButton,
            keyboardBtn, textField, enterBtn,
        ))

        // Apply the capability preview for the default platform (ANDROID_TV).
        applyCapabilities(previewCapabilities())
        setReady(false)
    }

    // ── Capabilities ──────────────────────────────────────────────────────────

    // Expected capabilities before connecting, derived from the spinner selection.
    // After connect, applyCapabilities() is called again with driver.capabilities
    // as the authoritative source.
    private fun previewCapabilities(): TvCapabilities = when (selectedPlatform) {
        TvPlatform.ANDROID_TV -> TvCapabilities(airMouse = true,  freeText = true)
        TvPlatform.SAMSUNG    -> TvCapabilities(airMouse = false, freeText = false)
    }

    // Show or hide platform-specific UI controls based on what the connected
    // (or about-to-be-connected) platform supports.
    private fun applyCapabilities(caps: TvCapabilities) {
        // Air-mouse control: just the aim button now. The settings gear lives in the
        // top bar and is always visible (it holds general settings, not only the
        // air-mouse hover-focus toggle).
        aimButton.visibility = if (caps.airMouse) View.VISIBLE else View.GONE

        // Text injection: keyboard icon that opens the typing panel.
        keyboardBtn.visibility = if (caps.freeText) View.VISIBLE else View.GONE

        // If the aim button just became visible we need a layout pass before we
        // can measure the anchor; post() defers to after the current frame.
        if (caps.airMouse) syncAimButtonPosition(aimButton)
    }

    // ── Ready gate ────────────────────────────────────────────────────────────

    private fun setReady(value: Boolean) {
        ready = value
        val alpha = if (value) 1f else 0.4f
        gatedControls.forEach { v ->
            v.isEnabled = value
            v.alpha = alpha
        }
        // Status pill dot: green when connected, muted grey otherwise.
        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (value) R.color.status_online else R.color.text_secondary)
        )
        // Losing the primary (ADB) connection also drops the air-mouse. Becoming
        // ready does NOT auto-enable it — that waits for the WS to actually open.
        if (!value) setAirMouseReady(false)
    }

    // Enables/disables the air-mouse controls (aim button + settings gear) based on
    // whether the WebSocket leg is up. Separate from setReady() so the basic remote
    // is usable over ADB even while the air-mouse is still connecting or unavailable.
    private fun setAirMouseReady(available: Boolean) {
        airMouseReady = available
        val alpha = if (available) 1f else 0.4f
        aimButton.isEnabled = available
        aimButton.alpha = alpha
        // Settings stays accessible regardless of connection — it now holds the
        // sensitivity sliders + hover-focus toggle, all of which persist to prefs and
        // are pushed to the TV on (re)connect (the driver calls are null-safe).
    }

    // ── Text mirror helpers ───────────────────────────────────────────────────

    private fun syncToTv(newPhoneText: String) {
        val prefixLen  = longestCommonPrefixLength(tvText, newPhoneText)
        val backspaces = tvText.length - prefixLen
        val toType     = newPhoneText.substring(prefixLen)
        if (backspaces > 0) driver?.sendBackspaces(backspaces)
        if (toType.isNotEmpty()) driver?.sendText(toType)
        tvText = newPhoneText
    }

    private fun longestCommonPrefixLength(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        var i = 0
        while (i < minLen && a[i] == b[i]) i++
        return i
    }

    // ── Soft keyboard helpers ─────────────────────────────────────────────────

    private fun showSoftKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSoftKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun isHoverFocusEnabled(): Boolean = prefs.getBoolean(PREF_HOVER_FOCUS, false)

    private fun setHoverFocusEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_HOVER_FOCUS, enabled).apply()
    }

    private fun syncAimButtonPosition(aimButton: View) {
        val anchor = findViewById<View>(R.id.aimAnchor)
        val root   = aimButton.parent as? View ?: return
        anchor.post {
            val anchorLoc = IntArray(2)
            val rootLoc   = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            root.getLocationInWindow(rootLoc)
            val lp = aimButton.layoutParams as? FrameLayout.LayoutParams ?: return@post
            lp.gravity      = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin    = anchorLoc[1] - rootLoc[1]
            lp.bottomMargin = 0
            aimButton.layoutParams = lp
        }
    }

    // Show the settings overlay, expanding the panel from the top-right (the settings
    // icon) toward the bottom-left — like the icon unfolding into a panel.
    private fun showSettingsPanel() {
        findViewById<View>(R.id.settingsOverlay).visibility = View.VISIBLE
        findViewById<View>(R.id.settingsPanel).startAnimation(expandFrom(1f, 0f))
        // Fetch the curated list the first time the panel is opened (not at app start,
        // so we don't make a network call nobody asked for).
        loadSuggestedAppsOnce()
    }

    // ── Self-update (GitHub Releases) ───────────────────────────────────────────
    // Updater delivers every callback on the UI thread, so we touch views directly.
    private fun checkForUpdates() {
        val status = findViewById<TextView>(R.id.updateStatus)
        status.text = getString(R.string.checking_updates)
        updater.checkForUpdate { result ->
            when (result) {
                is UpdateCheck.UpToDate -> status.text = getString(R.string.up_to_date)
                is UpdateCheck.Failed   -> status.text = result.reason
                is UpdateCheck.Available -> {
                    status.text = getString(R.string.update_available, result.versionName)
                    // Confirm before downloading — show the release notes so the user
                    // knows what's changing.
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available, result.versionName))
                        .setMessage(result.notes)
                        .setPositiveButton(R.string.check_for_updates) { _, _ ->
                            startUpdateDownload(result, status)
                        }
                        .setNegativeButton(R.string.btn_close, null)
                        .show()
                }
            }
        }
    }

    private fun startUpdateDownload(update: UpdateCheck.Available, status: TextView) {
        status.text = getString(R.string.downloading_update)
        updater.downloadAndInstall(
            apkUrl = update.apkUrl,
            onProgress = { percent ->
                status.text = getString(R.string.downloading_update) +
                    if (percent >= 0) " $percent%" else ""
            },
            // onError covers both the "grant install permission" redirect and real failures.
            onError = { msg -> status.text = msg; toast(msg) },
        )
    }

    // ── Suggested-apps mini-store ───────────────────────────────────────────────
    // Curated apps the developer advertises, fetched once from a hosted JSON. Tapping a
    // row downloads its APK to the phone and installs it on the connected TV over ADB.
    private fun loadSuggestedAppsOnce() {
        if (suggestedAppsLoaded) return
        suggestedAppsLoaded = true
        val status = findViewById<TextView>(R.id.suggestedAppsStatus)
        status.text = getString(R.string.suggested_apps_loading)
        suggestedAppsRepo.fetch { result ->
            result.onSuccess { apps ->
                if (apps.isEmpty()) {
                    status.text = getString(R.string.suggested_apps_empty)
                } else {
                    status.text = getString(R.string.suggested_apps_hint)
                    renderSuggestedApps(apps)
                }
            }.onFailure { e ->
                // Permit a retry next time the panel opens (e.g. transient offline).
                suggestedAppsLoaded = false
                status.text = e.message ?: getString(R.string.suggested_apps_empty)
            }
        }
    }

    private fun renderSuggestedApps(apps: List<SuggestedApp>) {
        val container = findViewById<LinearLayout>(R.id.suggestedAppsContainer)
        container.removeAllViews()
        for (app in apps) container.addView(makeStoreRow(app))
    }

    // One tappable store row, styled like the custom-slot rows (ripple background,
    // trailing download glyph). Built in code because the list is dynamic.
    private fun makeStoreRow(app: SuggestedApp): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(8) }
        text = if (app.description.isBlank()) app.label else "${app.label} — ${app.description}"
        setTextColor(getColor(R.color.text_primary))
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        // Pull the platform's ripple drawable for ?attr/selectableItemBackground.
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = ta.getDrawable(0)
        ta.recycle()
        setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.stat_sys_download, 0)
        compoundDrawablePadding = dp(8)
        setOnClickListener { installSuggestedApp(app, this) }
    }

    private fun installSuggestedApp(app: SuggestedApp, row: TextView) {
        val d = driver
        if (d == null || !ready) {
            toast(getString(R.string.suggested_apps_need_tv))
            return
        }
        row.isEnabled = false
        row.text = getString(R.string.installing_app, app.label)
        // 1) download the APK to the phone, then 2) push + install it on the TV.
        suggestedAppsRepo.downloadApk(
            app,
            onProgress = { percent ->
                row.text = getString(R.string.installing_app, app.label) +
                    if (percent >= 0) " $percent%" else ""
            },
            onResult = { result ->
                result.onSuccess { file ->
                    // installApkOnTv calls back on a BACKGROUND thread → marshal to UI.
                    d.installApkOnTv(file) { success, message ->
                        runOnUiThread {
                            row.isEnabled = true
                            row.text = if (success) getString(R.string.installed_app, app.label)
                                       else "${app.label} — $message"
                            if (!success) toast(message)
                        }
                    }
                }.onFailure { e ->
                    row.isEnabled = true
                    row.text = app.label
                    toast(e.message ?: "Download failed")
                }
            },
        )
    }

    // dp → px for views built in code.
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // A scale+fade "expand from a corner" animation. pivot 0..1 within the view:
    // (1,0) = top-right, (0.5,0) = top-centre, etc.
    private fun expandFrom(pivotX: Float, pivotY: Float): AnimationSet {
        val scale = ScaleAnimation(
            0.25f, 1f, 0.25f, 1f,
            Animation.RELATIVE_TO_SELF, pivotX,
            Animation.RELATIVE_TO_SELF, pivotY,
        )
        val fade = AlphaAnimation(0f, 1f)
        return AnimationSet(false).apply {
            interpolator = DecelerateInterpolator()
            duration = 190
            addAnimation(scale)
            addAnimation(fade)
        }
    }

    // Tiny adapter so callers only supply the onProgressChanged body. Fires only on
    // user-driven changes (not programmatic setProgress during setup).
    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // Map aim-sensitivity 0..100 to a gyro multiplier: 0 → 0.3x, 50 → 1.0x, 100 → 2.0x.
    private fun computeGyroFactor(sens: Int): Float {
        val s = sens.coerceIn(0, 100)
        return if (s <= 50) 0.3f + (1.0f - 0.3f) * s / 50f
               else 1.0f + (2.0f - 1.0f) * (s - 50) / 50f
    }

    // ── Touch wiring ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun wireKey(viewId: Int, code: KeyCode) {
        findViewById<View>(viewId).setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!ready) return@setOnTouchListener true
                driver?.sendKey(code)
                Haptics.buttonPress(v)
                v.performClick()
            }
            true
        }
    }

    // Like wireKey, but a held press sends a long-press instead of a tap. Same
    // tap-vs-hold logic as DPadView's OK: the tap is deferred to release so a hold
    // never also emits a tap. A scheduled runnable fires the long-press if the
    // button is still down past the timeout.
    @SuppressLint("ClickableViewAccessibility")
    private fun wireKeyWithLongPress(
        viewId: Int,
        code: KeyCode,
        longPressMs: Long = LONG_PRESS_TIMEOUT_MS,
    ) {
        val view = findViewById<View>(viewId)
        var longFired = false
        val longPressRunnable = Runnable {
            longFired = true
            Haptics.longPress(view)
            driver?.sendLongPress(code)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!ready) return@setOnTouchListener true
                    longFired = false
                    v.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    // Fire the tap only on a real release that wasn't a long-press.
                    if (ready && !longFired) {
                        driver?.sendKey(code)
                        Haptics.buttonPress(v)
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    true
                }
                else -> true
            }
        }
    }

    // The package a custom slot launches: the user's saved choice, or the default
    // for that slot. (`?:` elvis falls back when nothing is stored yet.)
    private fun customAppPackage(slot: Int): String =
        prefs.getString("$PREF_CUSTOM_APP_PREFIX$slot", null) ?: DEFAULT_CUSTOM_APPS[slot - 1]

    private fun customAppLabel(slot: Int): String =
        prefs.getString("$PREF_CUSTOM_APP_LABEL_PREFIX$slot", null) ?: DEFAULT_CUSTOM_LABELS[slot - 1]

    // Wire one custom shortcut button: TAP → launch its bound package; LONG-PRESS →
    // open the app picker to rebind it. Added to gatedControls so it's greyed/disabled
    // until connected. We also paint its initial icon (cached real icon or letter tile).
    private fun wireCustomAppButton(viewId: Int, slot: Int) {
        val view = findViewById<View>(viewId)
        view.setOnClickListener {
            if (!ready) return@setOnClickListener
            Haptics.buttonPress(view)
            driver?.launchApp(customAppPackage(slot))
        }
        view.setOnLongClickListener {
            openAppPicker(slot)
            true   // consumed — don't also fire the tap/launch
        }
        gatedControls.add(view)
        refreshCustomButtonIcon(slot)
    }

    // ── Custom app picker + icon (hybrid: cached TV icon → letter tile) ──────────

    private fun customIconView(slot: Int): ImageView = findViewById(
        when (slot) { 1 -> R.id.customIcon1; 2 -> R.id.customIcon2; else -> R.id.customIcon3 }
    )

    // The tappable row in the settings panel for one slot.
    private fun customSlotRow(slot: Int): TextView = findViewById(
        when (slot) { 1 -> R.id.customSlotRow1; 2 -> R.id.customSlotRow2; else -> R.id.customSlotRow3 }
    )

    // Repaint every settings row with its slot's currently-bound app label, e.g.
    // "Button 1 · YouTube". Called at startup and after any rebind.
    private fun refreshCustomSlotRows() {
        for (slot in 1..3) {
            customSlotRow(slot).text = getString(R.string.custom_slot_row, slot, customAppLabel(slot))
        }
    }

    // Show the slot's icon: the disk-cached real TV icon if we have one, else a
    // generated letter tile. Called at startup and whenever a binding changes.
    private fun refreshCustomButtonIcon(slot: Int) {
        val cache = iconCacheFile(customAppPackage(slot))
        val cached = if (cache.exists()) BitmapFactory.decodeFile(cache.absolutePath) else null
        customIconView(slot).setImageBitmap(cached ?: letterTile(customAppLabel(slot)))
    }

    // On connect, fetch the real icon for each custom slot whose icon we don't
    // already have cached, and paint it in. This is what makes the default
    // YouTube/Netflix/Prime buttons show their OFFICIAL icons (read from the TV
    // where those apps are installed) instead of letter tiles — no brand artwork
    // shipped in the app. A package that isn't installed returns an empty icon and
    // simply keeps its letter tile.
    private fun prefetchCustomIcons() {
        for (slot in 1..3) {
            val pkg = customAppPackage(slot)
            if (iconCacheFile(pkg).exists()) continue   // already have the real icon
            driver?.requestAppIcon(pkg) { p, b64 ->
                runOnUiThread {
                    if (b64.isEmpty()) return@runOnUiThread
                    saveIconToCache(p, b64)
                    if (customAppPackage(slot) == p) {
                        decodeIcon(b64)?.let { customIconView(slot).setImageBitmap(it) }
                    }
                }
            }
        }
    }

    // Long-press handler: ask the TV for its apps, then show a chooser. Requires the
    // air-mouse WebSocket (the app list travels over it), so gate on airMouseReady.
    private fun openAppPicker(slot: Int) {
        val d = driver
        if (d == null || !airMouseReady) {
            toast("Connect to your TV first to choose an app.")
            return
        }
        val loading = AlertDialog.Builder(this)
            .setMessage("Loading TV apps…")
            .setCancelable(true)
            .create()
        loading.show()
        // The reply can be lost if the TV side hiccups; cap the wait so the dialog
        // never hangs forever.
        val timeout = Runnable {
            if (loading.isShowing) { loading.dismiss(); toast("Couldn't load apps from the TV.") }
        }
        mainHandler.postDelayed(timeout, 6_000L)
        d.requestAppList { apps ->
            // requestAppList calls back on a background (OkHttp) thread.
            runOnUiThread {
                mainHandler.removeCallbacks(timeout)
                if (!loading.isShowing) return@runOnUiThread   // user dismissed it
                loading.dismiss()
                showAppChooser(slot, apps)
            }
        }
    }

    private fun showAppChooser(slot: Int, apps: List<AppInfo>) {
        if (apps.isEmpty()) { toast("No apps found on the TV."); return }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(labels) { _, which -> bindCustomApp(slot, apps[which]) }
            .show()
    }

    // Persist the binding, show a letter tile immediately, then fetch + cache the
    // real icon and swap it in when it arrives.
    private fun bindCustomApp(slot: Int, app: AppInfo) {
        prefs.edit()
            .putString("$PREF_CUSTOM_APP_PREFIX$slot", app.packageName)
            .putString("$PREF_CUSTOM_APP_LABEL_PREFIX$slot", app.label)
            .apply()
        refreshCustomButtonIcon(slot)   // letter tile now (cache miss for a new app)
        refreshCustomSlotRows()         // update the settings row label too
        driver?.requestAppIcon(app.packageName) { pkg, b64 ->
            runOnUiThread {
                if (b64.isEmpty()) return@runOnUiThread   // TV couldn't render — keep letter tile
                saveIconToCache(pkg, b64)
                // Guard against a race: the user may have rebound this slot to a
                // different app before this icon arrived.
                if (customAppPackage(slot) == pkg) {
                    decodeIcon(b64)?.let { customIconView(slot).setImageBitmap(it) }
                }
            }
        }
    }

    // Cache file for a package's icon. Sanitise the package into a safe filename.
    private fun iconCacheFile(pkg: String): File =
        File(filesDir, "appicon_${pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")

    private fun saveIconToCache(pkg: String, base64Png: String) {
        runCatching { iconCacheFile(pkg).writeBytes(Base64.decode(base64Png, Base64.DEFAULT)) }
            .onFailure { Log.w("CustomApp", "icon cache write failed for $pkg: ${it.message}") }
    }

    private fun decodeIcon(base64Png: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    // Generate a square rounded tile with the app's initial. The background hue is
    // derived from the label so each app gets a stable, distinct colour.
    private fun letterTile(label: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = ((label.hashCode() and 0xFFFFFF) % 360).toFloat()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f))
        }
        val r = size * 0.22f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val letter = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        // Vertically centre using font metrics (baseline math, not just size/2).
        val fm = textPaint.fontMetrics
        canvas.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        return bmp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @SuppressLint("ClickableViewAccessibility")
    private fun wireAimButton() {
        var withinBounds = true

        aimButton.setOnTouchListener { v, event ->
            // Gate on air-mouse readiness (WS up), not just `ready` (ADB). setOnTouchListener
            // bypasses View.isEnabled, so we must check here. Returning false on DOWN makes
            // Android drop the whole gesture (no MOVE/UP delivered).
            if (event.action == MotionEvent.ACTION_DOWN && !airMouseReady) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    withinBounds = true
                    aimDimOverlay.visibility = View.VISIBLE
                    driver?.cursorVisible(true)
                    gyroReader.start { dx, dy ->
                        // User aim sensitivity × hover-focus damping (when H2F is on the
                        // cursor moves slower so focus doesn't thrash across tiles).
                        val hoverScale = if (isHoverFocusEnabled()) HOVER_FOCUS_GYRO_SCALE else 1f
                        val scale = hoverScale * gyroSensFactor
                        driver?.cursorMove(
                            (dx * scale).toInt(),
                            (dy * scale).toInt(),
                        )
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    withinBounds = event.x in 0f..v.width.toFloat() &&
                                   event.y in 0f..v.height.toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Log.i("AimBtn", "UP withinBounds=$withinBounds")
                    if (withinBounds) {
                        driver?.sendClick()
                        Haptics.confirm(v)
                    }
                    gyroReader.stop()
                    driver?.cursorVisible(false)
                    aimDimOverlay.visibility = View.GONE
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    Log.i("AimBtn", "CANCEL")
                    gyroReader.stop()
                    driver?.cursorVisible(false)
                    aimDimOverlay.visibility = View.GONE
                    true
                }
                else -> false
            }
        }
    }

    // ── Background / lock reconnect ─────────────────────────────────────────────
    // The ADB socket + WebSocket don't survive the phone sleeping or being
    // backgrounded, but the Activity does — so `ready` used to stay true and the UI
    // falsely showed "Connected" while keys silently failed (writes to a dead
    // stream). We now drop the connection when we leave the foreground and
    // transparently reconnect when we return, using the IP still in the field.
    private var reconnectOnResume = false

    override fun onStop() {
        super.onStop()
        gyroReader.stop()
        // If we were connected, the transport is effectively dead now. Tear it down
        // and mark not-ready so we never show a stale "Connected"; reconnect onResume.
        if (ready) {
            reconnectOnResume = true
            driver?.disconnect()
            setReady(false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (reconnectOnResume) {
            reconnectOnResume = false
            // performClick re-runs the Connect handler, which reads the IP field
            // (prefilled with the last connected IP) and rebuilds the connection.
            findViewById<View>(R.id.connectButton).performClick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gyroReader.stop()
        discoverTimeout?.let { mainHandler.removeCallbacks(it) }
        nsdDiscovery.stopDiscovery()
        driver?.disconnect()
    }
}
