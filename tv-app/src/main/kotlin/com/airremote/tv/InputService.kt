package com.airremote.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.airremote.protocol.TouchSwipeMessage

private const val TAG = "InputService"
// Click is a 40 ms down→up tap. Long enough to register, short enough to feel instant.
private const val CLICK_DURATION_MS = 40L

// Hover-to-scroll polling cadence while an aim session is active.
// 120 ms = ~8 ticks/sec — responsive in the edge zone, not runaway. Each tick
// is a tree walk + one performAction, so we keep it well above 60 Hz to leave
// budget for gyro moveBy() on the same main-thread handler.
private const val SCROLL_POLL_MS = 120L
// Edge-zone size as a fraction of the scrollable container's visible bounds.
// 15% means a 1920-wide row has ~288px zones on each side — wide enough that
// the user doesn't have to pixel-hunt, narrow enough that they can still aim
// at items near the edges without triggering a scroll.
private const val SCROLL_EDGE_FRACTION = 0.15
// Pixel floor for the edge zone. Keeps small scrollables (e.g. a short
// sidebar) from collapsing to an unusably thin trigger area.
private const val SCROLL_EDGE_MIN_PX = 60
// Minimum gap between two ACTION_SCROLL_* fires. Decouples scroll cadence
// from poll cadence: poll stays at 120 ms so edge entry is instant, but
// repeats throttle to ~3/sec while the cursor lingers. Without this, a 2D
// GridView (which scrolls by whole pages) pages 8×/sec — feels like a slot
// machine. 300 ms ≈ one page per third of a second, which matches the
// scroll animation's natural settle time.
private const val SCROLL_MIN_INTERVAL_MS = 300L
// Bounds the scroll-sensitivity slider maps between (ms between scroll fires).
// Higher sensitivity → shorter interval → faster scroll; 50 (default) ≈ the old
// fixed SCROLL_MIN_INTERVAL_MS.
private const val SCROLL_INTERVAL_SLOW_MS = 700L   // sensitivity 0
private const val SCROLL_INTERVAL_FAST_MS = 120L   // sensitivity 100
// How long the cursor must dwell in a scrollable's edge zone before the FIRST scroll
// fires. Stops a quick pass-through — or H2F focus jitter near the edge — from
// kicking off a runaway scroll (the "autoscroll at the screen edge" complaint).
private const val EDGE_DWELL_MS = 200L

// ── Adaptive scroll (gesture swipe vs a11y) ─────────────────────────────────
// Many touch-origin apps (CloudStream, browsers) only advertise the page-sized
// ACTION_SCROLL_FORWARD/BACKWARD, so a11y scrolling jumps a whole viewport and
// can't land on a specific row. For those we drive scrolling with a real touch
// swipe (dispatchGesture) whose distance WE control. Pure D-pad/leanback apps
// (Projectivy) ignore touch, so they keep the a11y path. We decide per-app by
// probing once: swipe, then watch for a TYPE_VIEW_SCROLLED event from the same
// container — present → touch works, absent → fall back to a11y. Cached per pkg.
// Swipe distance is a FRACTION of the scrolled container's span (per axis), driven by
// the matching strength slider. Fraction (not fixed px) makes "strong" scale with the
// container: ~90% of a row's width reveals a fresh screen of items; ~90% of the feed's
// height pages down hard. 0.20 floor keeps the lightest setting usable. Replaces the old
// fixed 110..430px, which felt too small — especially vertically.
private const val SWIPE_FRACTION_MIN = 0.20f   // strength 0
private const val SWIPE_FRACTION_MAX = 0.90f   // strength 100
// Drag speed: ms per pixel. A slowish drag keeps the scroll roughly proportional to the
// swipe length; the higher MAX cap lets the (now larger) strong swipes still complete
// without being clipped so short they fling unpredictably.
private const val SWIPE_MS_PER_PX = 1.2
private const val SWIPE_DURATION_MIN_MS = 140L
private const val SWIPE_DURATION_MAX_MS = 700L
// How long after a probe swipe we wait for the confirming scroll event before
// concluding the app ignores touch and falling back to a11y. The swipe now travels
// TV → phone → helper → injection (a small round-trip) before the app scrolls, so
// this is generous enough to cover that latency plus the start of the drag; the
// confirming TYPE_VIEW_SCROLLED fires early in the swipe, well inside this window.
private const val SCROLL_PROBE_WINDOW_MS = 450L
// Tolerance (px) when matching the scroll event's source bounds to the container
// we probed — rejects scroll events from a *different* view (e.g. an auto-cycling
// hero carousel) that happen to land inside the probe window.
private const val SCROLL_PROBE_BOUNDS_TOL_PX = 32
// Re-probe an app that came back A11Y at most this many times (once per aim
// session) before trusting the A11Y verdict. Covers a probe that ran before the
// app's content finished loading, without making real D-pad apps re-probe forever.
private const val MAX_A11Y_REPROBES = 3

// Minimum gap between two ACTION_FOCUS fires. Hover-focus is cheaper than
// scrolling and wants to feel "live" — the highlight should track the dot
// closely — so this is tighter than the scroll throttle. But it's still
// throttled so a slow gyro drift across a dense row doesn't fire a focus on
// every poll tick. 150 ms ≈ 6–7/sec: responsive without thrashing tiles.
private const val FOCUS_MIN_INTERVAL_MS = 150L
// Minimum cursor travel (px) before re-firing ACTION_FOCUS on a different
// node. Stops micro-jitter from hopping focus between adjacent tiles.
private const val FOCUS_MIN_MOVE_PX = 12
// After focusing a new node, how long (ms) to wait before switching to a
// *different* node. Leanback's focus-scale animation (~200 ms) temporarily
// expands the focused tile's a11y bounds, pushing adjacent tiles rightward.
// The cursor can drift into the adjacent tile's region mid-animation and fire
// an immediate focus-jump to the next tile before the user intended it. This
// cooldown absorbs that animation window.
private const val FOCUS_NODE_SWITCH_COOLDOWN_MS = 300L
// Manhattan displacement (px) from the last focus point that bypasses the
// node-switch cooldown. A large, deliberate swipe signals clear intent to
// navigate quickly — let it through even during the animation window.
private const val FOCUS_FAST_MOVE_PX = 60
// Window after an ACTION_FOCUS within which a TYPE_VIEW_SCROLLED from the same
// app is attributed to leanback's align-on-focus (it scrolls the row so the
// just-focused tile snaps to the anchor). A scroll arriving this soon after our
// focus is almost certainly the align, not a user gesture. Drives the
// align-scroll damper that stops the focus→align→re-focus runaway.
private const val FOCUS_ALIGN_WINDOW_MS = 250L

// AccessibilityService kept for air-mouse cursor click support.
// Key injection (D-pad, back, home, etc.) is handled entirely via ADB on the
// phone side — the phone connects to the TV's ADB daemon on port 5555 and
// sends `input keyevent <code>` as the shell user, which has INJECT_EVENTS
// permission. This service's only future role is clickAt(x, y): find the
// accessibility node whose bounds contain the cursor position and ACTION_CLICK it.
// That method will be added in the air-mouse phase.
class InputService : AccessibilityService() {

    // `companion object` — Kotlin's replacement for Java static members.
    // WsServer will reach this as `InputService.instance` without holding a
    // reference to the service itself.
    companion object {
        @Volatile
        var instance: InputService? = null
            private set
    }

    // Owned by the service so its lifetime matches the a11y binding.
    // `lateinit` defers initialization until onServiceConnected (we need a
    // Context that's fully wired before touching WindowManager).
    lateinit var cursor: CursorOverlay
        private set

    // Cursor position as Doubles so sub-pixel deltas from the gyro don't keep
    // rounding to zero. We clamp + truncate to Int only when we hand off to
    // the WindowManager.
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var screenW = 0
    private var screenH = 0

    // Handler bound to the main Looper. WindowManager + dispatchGesture both
    // require the main thread; WebSocket callbacks arrive on the I/O thread,
    // so every UI/gesture call is hopped through this handler.
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        cursor = CursorOverlay(this)
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        cursorX = screenW / 2.0
        cursorY = screenH / 2.0
        // Don't show the cursor on connect — only while an aim session is
        // active. Phone sends CursorVisibilityMessage(true) on aim-button DOWN
        // and (false) on UP/CANCEL. Position is still primed to screen center
        // so the first frame after show() lands somewhere reasonable.
        cursor.moveTo(cursorX.toInt(), cursorY.toInt())
        Log.i(TAG, "AccessibilityService connected; cursor primed at ${cursorX.toInt()},${cursorY.toInt()} (${screenW}x${screenH})")

        // Boot-start path that survives the OEM autostart block. heytap TV firmware
        // does NOT deliver BOOT_COMPLETED to BootReceiver, so MainService (WebSocket
        // + mDNS) never came up at boot — Discover couldn't find a cold TV. But the
        // system binds enabled AccessibilityServices itself during boot, bypassing
        // that block. So we start MainService here: every cold boot now advertises
        // mDNS, no phone connect required. Idempotent — if MainService is already
        // running this just hits onStartCommand again (mDNS registers once in onCreate).
        ensureMainServiceRunning()
    }

    // Start MainService (idempotent). Called from onServiceConnected so the
    // WebSocket server + mDNS advertisement come up at boot via the a11y bind,
    // which the OEM autostart filter doesn't block (unlike BootReceiver).
    private fun ensureMainServiceRunning() {
        runCatching { startForegroundService(Intent(this, MainService::class.java)) }
            .onSuccess { Log.i(TAG, "MainService start requested from a11y bind") }
            .onFailure { Log.e(TAG, "MainService start from a11y bind FAILED: ${it.message}") }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        if (::cursor.isInitialized) cursor.hide()
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    // We only listen for TYPE_VIEW_SCROLLED, and only while a scroll-method probe
    // is in flight: it's the signal that the app honoured our test swipe (so it's
    // touch-capable and we should keep using gesture swipes). Everything else is
    // ignored — the click path reads rootInActiveWindow on demand and keys ride ADB.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val evtPkg = event.packageName?.toString()

        // Align-scroll detection (H2F only). A scroll from the app we just
        // ACTION_FOCUS'd, arriving within FOCUS_ALIGN_WINDOW_MS, is leanback
        // aligning the focused row — not a user gesture. Engage the damper so
        // maybeFocus() stops chasing the content that slid under the cursor.
        if (hoverFocusEnabled && evtPkg != null && evtPkg == lastFocusActionPkg &&
            android.os.SystemClock.uptimeMillis() - lastFocusActionMs <= FOCUS_ALIGN_WINDOW_MS
        ) {
            alignScrollActive = true
            alignAnchorX = cursorX.toInt()
            alignAnchorY = cursorY.toInt()
            Log.i(TAG, "align-scroll detected from $evtPkg ${android.os.SystemClock.uptimeMillis() - lastFocusActionMs}ms after focus; damping re-focus at ($alignAnchorX,$alignAnchorY)")
        }

        // Probe handling (separate concern from align detection above).
        val pkg = probePkg ?: return
        if (evtPkg != pkg) return
        // Reject a scroll from a *different* view than the one we swiped (e.g. a
        // self-cycling hero carousel firing its own scroll events) by matching the
        // event source's bounds to the probed container, when a source is present.
        event.source?.let { src ->
            val r = Rect()
            src.getBoundsInScreen(r)
            val close =
                kotlin.math.abs(r.left - probeBounds.left) <= SCROLL_PROBE_BOUNDS_TOL_PX &&
                kotlin.math.abs(r.top - probeBounds.top) <= SCROLL_PROBE_BOUNDS_TOL_PX &&
                kotlin.math.abs(r.right - probeBounds.right) <= SCROLL_PROBE_BOUNDS_TOL_PX &&
                kotlin.math.abs(r.bottom - probeBounds.bottom) <= SCROLL_PROBE_BOUNDS_TOL_PX
            if (!close) return
        }
        scrollMethodCache[pkg] = ScrollMethod.TOUCH
        probeTimeout?.let { mainHandler.removeCallbacks(it) }
        probeTimeout = null
        probePkg = null
        Log.i(TAG, "scroll probe: '$pkg' honoured touch → using gesture swipes")
    }

    override fun onInterrupt() = Unit

    // Gyro coalescing state. moveBy() is called on the WS I/O thread at sensor rate
    // (~60-200 Hz); the actual cursor move + overlay relayout must run on the main
    // thread. Posting one Runnable per sample means that under main-thread congestion
    // (heavy app loading) the posts pile up and replay one-by-one, so the cursor
    // crawls through a stale backlog instead of jumping to where the user is now.
    // Instead we accumulate the pending delta under a lock and keep at most ONE drain
    // queued — when it runs it applies the whole accumulated delta at once. moveLock
    // guards the three fields across the I/O and main threads.
    private val moveLock = Any()
    private var pendingDx = 0.0
    private var pendingDy = 0.0
    private var movePosted = false
    private val moveRunnable = Runnable {
        var dx = 0.0
        var dy = 0.0
        synchronized(moveLock) {
            movePosted = false
            dx = pendingDx; dy = pendingDy
            pendingDx = 0.0; pendingDy = 0.0
        }
        // cursorX/cursorY are touched only on the main thread (here + setCursorVisible),
        // so they need no synchronisation — only the pending accumulators cross threads.
        cursorX = (cursorX + dx).coerceIn(0.0, (screenW - 1).toDouble())
        cursorY = (cursorY + dy).coerceIn(0.0, (screenH - 1).toDouble())
        cursor.moveTo(cursorX.toInt(), cursorY.toInt())
    }

    // Apply a gyro delta. WsServer calls this on every GyroMessage. We just accumulate
    // and ensure a single drain is queued (see moveRunnable). In the common (unloaded)
    // case the drain runs almost immediately, so this behaves like one move per sample;
    // only under congestion does coalescing kick in.
    fun moveBy(dx: Double, dy: Double) {
        synchronized(moveLock) {
            pendingDx += dx
            pendingDy += dy
            if (!movePosted) {
                movePosted = true
                mainHandler.post(moveRunnable)
            }
        }
    }

    // Show/hide the overlay. Called from WsServer on CursorVisibilityMessage.
    // CursorOverlay.show/hide are idempotent so repeat calls are safe. We also
    // recenter the cursor on show, so each aim session starts at the middle of
    // the screen rather than wherever the previous session ended — gives the
    // user a predictable starting point and avoids cursor drift across sessions.
    //
    // Aim-session lifecycle also owns scroll polling: we start the
    // hover-to-scroll Runnable on show() and remove it on hide(). Scrolling
    // outside an active aim session would feel like the app is moving on its
    // own when the user isn't pointing at anything.
    fun setCursorVisible(visible: Boolean) {
        mainHandler.post {
            if (visible) {
                cursorX = screenW / 2.0
                cursorY = screenH / 2.0
                cursor.moveTo(cursorX.toInt(), cursorY.toInt())
                cursor.show()
                onAimSessionStart()
                mainHandler.postDelayed(scrollPollRunnable, SCROLL_POLL_MS)
            } else {
                cursor.hide()
                mainHandler.removeCallbacks(scrollPollRunnable)
            }
        }
    }

    // Hover-to-scroll polling loop. Reposts itself; removed when cursor hides.
    // `object : Runnable` is Kotlin's anonymous-object syntax for "make me a
    // singleton implementing this interface" — equivalent to a Java named
    // inner class with a private constructor. We keep a single instance so
    // removeCallbacks() identifies exactly what to cancel.
    private val scrollPollRunnable = object : Runnable {
        override fun run() {
            // Hover-to-focus is opt-in (phone-side checkbox under settings).
            //   ON  → focus follows cursor every tick (maybeFocus always runs), plus
            //         screen-edge scroll.
            //   OFF → screen-edge scroll only, no focus.
            // Either way the user scrolls by pushing the dot to a SCREEN edge.
            //
            // Cheap screen-edge proximity check done BEFORE any a11y tree walk.
            // maybeScroll()/maybeFocus() each do rootInActiveWindow + recursive
            // getChild() — binder IPC into the foreground app's process. When that app
            // is busy (CloudStream cold-start, first media decode), those calls answer
            // slowly, and because they share this main thread with cursor moves, they
            // stall the cursor → the "everything lags while it loads" symptom.
            //
            // Scrolling is always driven by pushing the cursor to a SCREEN edge, and the
            // containers that matter (full-screen feeds, full-width rows) have their
            // scroll edges at the screen edges anyway, so gating maybeScroll() on this
            // costs nothing in practice while skipping the tree walk on ~every mid-screen
            // tick. (The H2F path already gated scroll this way; this extends the same
            // gate to the non-H2F path, which previously walked the tree every tick.)
            val x = cursorX.toInt()
            val y = cursorY.toInt()
            val atScreenEdge = x <= SCROLL_EDGE_MIN_PX ||
                    x >= screenW - 1 - SCROLL_EDGE_MIN_PX ||
                    y <= SCROLL_EDGE_MIN_PX ||
                    y >= screenH - 1 - SCROLL_EDGE_MIN_PX
            if (hoverFocusEnabled) maybeFocus()
            if (atScreenEdge) maybeScroll() else edgeEnterMs = 0L  // left edges → reset dwell
            mainHandler.postDelayed(this, SCROLL_POLL_MS)
        }
    }

    // User preference, pushed from the phone over the WS (HoverFocusMessage):
    // on toggle and once per connect. @Volatile because WsServer writes it on
    // the I/O thread while the poll Runnable reads it on the main thread.
    // Default false — the feature is opt-in; the phone sends the real value on
    // connect before any aim session is likely to start.
    @Volatile
    private var hoverFocusEnabled = false

    fun setHoverFocusEnabled(enabled: Boolean) {
        hoverFocusEnabled = enabled
        // Drop the thrash-guard memory so re-enabling re-focuses cleanly.
        lastFocusedKey = null
        lastFocusCursorX = -1
        lastFocusCursorY = -1
        lastFocusSwitchedMs = 0L
        alignScrollActive = false
        alignAnchorX = -1
        alignAnchorY = -1
        lastFocusActionPkg = null
    }

    // Wall-clock ms of the last ACTION_FOCUS fire, for FOCUS_MIN_INTERVAL_MS
    // throttling. Same pattern as lastScrollFiredMs. Initial 0 → first eligible
    // tick focuses immediately.
    private var lastFocusFiredMs: Long = 0L

    // Identity (class + screen bounds) of the node we last successfully focused.
    // Used to skip re-focusing the same node every tick (thrash guard).
    // AccessibilityNodeInfo equality isn't reliable across re-fetches, so we key
    // on a cheap string of class + flattened bounds.
    private var lastFocusedKey: String? = null
    private var lastFocusCursorX = -1
    private var lastFocusCursorY = -1
    // Wall-clock ms when we last switched to a different node (ok=true on a new
    // key). Distinct from lastFocusFiredMs — used for the node-switch cooldown,
    // not for the per-tick re-fire throttle.
    private var lastFocusSwitchedMs: Long = 0L

    // Align-scroll damper state. When an ACTION_FOCUS succeeds we stamp the time
    // + app package; if a TYPE_VIEW_SCROLLED from that package arrives within
    // FOCUS_ALIGN_WINDOW_MS (onAccessibilityEvent), that's leanback aligning the
    // row, and we engage the damper: re-focus is frozen until the cursor moves a
    // deliberate FOCUS_FAST_MOVE_PX from where the align landed. All main-thread:
    // a11y events, the poll loop, and moveBy() all run on the main thread.
    private var lastFocusActionMs: Long = 0L
    private var lastFocusActionPkg: String? = null
    private var alignScrollActive = false
    private var alignAnchorX = -1
    private var alignAnchorY = -1

    // Move the focus highlight to whatever focusable item sits under the cursor.
    // Called every poll tick while an aim session is active (H2F mode only).
    //
    // This is the in-process "hover-to-focus" path: ACTION_FOCUS moves the
    // system focus to the node, so the launcher/app draws its own focus
    // highlight on it — the dot and the highlight travel together. It only ever
    // moves focus; activation still happens on aim-release via click().
    //
    // Returns true when ACTION_FOCUS succeeded on the found node (ok=true).
    // Return value is informational; the poll loop no longer uses it to gate scroll.
    private fun maybeFocus(): Boolean {
        val root = rootInActiveWindow ?: return false
        val x = cursorX.toInt()
        val y = cursorY.toInt()
        // findSmallestFocusableContaining excludes near-full-screen containers,
        // so a non-null target means we're genuinely over an item (a tile), not
        // the root FrameLayout / outer GridView. That's the scroll-gate signal.
        val target = findSmallestFocusableContaining(root, x, y) ?: return false

        val now = android.os.SystemClock.uptimeMillis()
        // Over an item but throttled — still gate scroll off, just don't refire.
        if (now - lastFocusFiredMs < FOCUS_MIN_INTERVAL_MS) return true
        lastFocusFiredMs = now

        // Same-node thrash guard: if the cursor is still over the node we last
        // focused, don't re-fire ACTION_FOCUS (it spams focus IPCs and can
        // retrigger the app's focus animation). Still return true to keep scroll
        // suppressed. Key on class + bounds (node identity is unreliable across
        // fetches).
        target.getBoundsInScreen(hoverRect)
        val key = "${target.className}@${hoverRect.flattenToString()}"
        if (key == lastFocusedKey) return true
        if (lastFocusCursorX >= 0) {
            val moved = kotlin.math.abs(x - lastFocusCursorX) + kotlin.math.abs(y - lastFocusCursorY)
            if (moved < FOCUS_MIN_MOVE_PX) return true
        }
        // Node-switch cooldown: after focusing tile B, don't immediately switch
        // to tile C within FOCUS_NODE_SWITCH_COOLDOWN_MS. Leanback's focus
        // animation expands B's a11y bounds temporarily, pushing C outward — the
        // cursor can appear to land on C mid-animation and cause an instant B→C
        // jump the user didn't intend.
        // Bypass: if the cursor has travelled FOCUS_FAST_MOVE_PX+ from the focus
        // point, the user is swiping deliberately — let the switch through now.
        if (lastFocusedKey != null) {
            val displacement = if (lastFocusCursorX >= 0)
                kotlin.math.abs(x - lastFocusCursorX) + kotlin.math.abs(y - lastFocusCursorY)
            else Int.MAX_VALUE
            if (displacement < FOCUS_FAST_MOVE_PX && now - lastFocusSwitchedMs < FOCUS_NODE_SWITCH_COOLDOWN_MS) {
                return true
            }
        }
        // Align-scroll damper: our previous focus made leanback align-scroll the
        // row (detected in onAccessibilityEvent), sliding a different tile under a
        // near-stationary cursor. Re-focusing it would align again → runaway, and
        // the highlight would chase the moving content instead of the dot. Hold
        // focus until the user makes a deliberate move (≥ FOCUS_FAST_MOVE_PX from
        // where the align landed), then release the damper and allow one fresh
        // focus. Same-node holds never reach here (guarded by the key check above).
        if (alignScrollActive) {
            val movedSinceAlign = if (alignAnchorX >= 0)
                kotlin.math.abs(x - alignAnchorX) + kotlin.math.abs(y - alignAnchorY)
            else Int.MAX_VALUE
            if (movedSinceAlign < FOCUS_FAST_MOVE_PX) return true
            alignScrollActive = false
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (ok) {
            lastFocusedKey = key
            lastFocusCursorX = x
            lastFocusCursorY = y
            lastFocusSwitchedMs = now
            // Arm align-scroll detection: a scroll from this app within the next
            // FOCUS_ALIGN_WINDOW_MS is leanback aligning the row we just focused.
            lastFocusActionMs = now
            lastFocusActionPkg = target.packageName?.toString()
        }
        Log.i(TAG, "hover-focus at ($x,$y) on '${target.className}' bounds=$hoverRect pkg=${target.packageName} ok=$ok")
        // Only suppress edge-scroll when focus actually landed (ok=true).
        // If the node rejected ACTION_FOCUS (ok=false), fall through to maybeScroll()
        // so the user isn't stuck when leanback silently refuses programmatic focus.
        return ok
    }

    // Hover-to-scroll speed, 0..100 (50 = default), pushed from the phone over WS
    // (ScrollSensitivityMessage) on change and once per connect. @Volatile: written on
    // the I/O thread, read by the poll loop on the main thread.
    @Volatile private var scrollSensitivity = 50

    fun setScrollSensitivity(value: Int) {
        scrollSensitivity = value.coerceIn(0, 100)
    }

    // Per-axis touch-swipe strength (0..100, 50 default), pushed from the phone over WS
    // (SwipeStrengthMessage) on change and once per connect. Drives swipeFraction() →
    // swipe distance. @Volatile: written on the I/O thread, read by the scroll path on the
    // main thread. The vertical value also feeds the a11y page-scroll cadence (so the
    // rare non-touch fallback stays roughly tunable without a separate slider).
    @Volatile private var swipeStrengthH = 50
    @Volatile private var swipeStrengthV = 50

    fun setSwipeStrength(horizontal: Int, vertical: Int) {
        swipeStrengthH = horizontal.coerceIn(0, 100)
        swipeStrengthV = vertical.coerceIn(0, 100)
        scrollSensitivity = swipeStrengthV
    }

    // Map a per-axis strength (0..100) to a fraction of the container's span. See the
    // SWIPE_FRACTION_* constants for why distance is container-relative.
    private fun swipeFraction(strength: Int): Float {
        val s = strength.coerceIn(0, 100)
        return SWIPE_FRACTION_MIN + (SWIPE_FRACTION_MAX - SWIPE_FRACTION_MIN) * s / 100f
    }

    // Wall-clock ms the cursor first entered the CURRENT edge zone (0 = not in one).
    // Drives the EDGE_DWELL_MS gate so an accidental edge brush doesn't scroll.
    private var edgeEnterMs = 0L

    // Last scroll direction we attempted, so we can log only on direction
    // CHANGE (entering a new edge zone) instead of every poll tick. 0 = none.
    private var lastScrollActionId: Int = 0
    // Wall-clock ms of the last fired scroll action. Used to throttle repeats
    // to SCROLL_MIN_INTERVAL_MS. Initial 0 means the first edge-entry fires
    // immediately (now - 0 ≫ interval).
    private var lastScrollFiredMs: Long = 0L

    // How we scroll a given app, decided per package by probing once.
    //   UNKNOWN  → not yet probed (the map simply has no entry).
    //   PROBING  → a probe swipe is in flight; await the verdict (event or timeout).
    //   TOUCH    → app honours touch → drive scrolling with gesture swipes.
    //   A11Y     → app ignores touch → use ACTION_SCROLL_*.
    private enum class ScrollMethod { PROBING, TOUCH, A11Y }
    // package name → resolved method. Main-thread only (maybeScroll + a11y events
    // both run on the main thread), so a plain map needs no synchronisation.
    private val scrollMethodCache = HashMap<String, ScrollMethod>()
    // How many times an app has come back A11Y. A negative verdict can be a false
    // negative — e.g. CloudStream fetches its feed asynchronously, so an early probe
    // swipes an empty list that doesn't scroll and looks like "ignores touch". So we
    // re-probe A11Y apps at the start of each aim session (see onAimSessionStart)
    // until either touch is confirmed or this counter hits MAX_A11Y_REPROBES, at
    // which point we trust A11Y (a genuine D-pad app) and stop paying the probe cost.
    private val a11yAttempts = HashMap<String, Int>()
    // Last (dir, method) we logged, so we log only on change, not every tick.
    private var lastScrollMethod: ScrollMethod? = null

    // In-flight probe state. probePkg non-null means we're waiting for a
    // confirming scroll event; probeBounds is the container we swiped, used to
    // reject scroll events from other views (auto-cycling carousels).
    private var probePkg: String? = null
    private val probeBounds = Rect()
    private var probeTimeout: Runnable? = null
    private val swipeRect = Rect()

    // Direction the cursor is signaling, derived from its position inside a
    // scrollable's edge zone. Used internally only — never crosses the wire.
    private enum class ScrollDir { LEFT, RIGHT, UP, DOWN }

    // A scrollable that's a viable scroll target: cursor is in one of its edge
    // zones AND it advertises an action that matches that direction. We pick
    // the smallest such candidate to scroll the most-specific container the
    // user is actually pointing at.
    private data class ScrollCandidate(
        val node: AccessibilityNodeInfo,
        val dir: ScrollDir,
        val actionId: Int,
        val area: Long,
    )

    // If the cursor is in the edge zone of a scrollable container, request a
    // scroll in the matching direction. Called on the main thread every
    // SCROLL_POLL_MS while aiming.
    //
    // Two-pass strategy:
    //   1. Pick the smallest scrollable that EXPLICITLY ADVERTISES an action
    //      for the cursor's edge direction (directional like ACTION_SCROLL_LEFT,
    //      or FWD/BACK mapped via the container's orientation heuristic).
    //   2. If nothing advertises, fall back to the smallest scrollable in the
    //      direction's edge zone and just try the directional action. Catches
    //      cases like Projectivy's outer GridView, which advertises only [D]
    //      but accepts ACTION_SCROLL_RIGHT through some internal handler.
    //
    // The two-pass approach fixes the "left rail hijacks left-scroll" bug: the
    // 37px-wide left rail is smallest-containing at x=0..37 but only advertises
    // [D], so pass 1 skips it and finds the row that actually advertises [L].
    //
    // Candidates also include scrollables whose APPROACH zone (just outside
    // one edge) contains the cursor — see edgeDirection. Without that, a fast
    // gyro flick can skip the row's inside-LEFT zone in one poll interval and
    // land in the rail, leaving no row candidate even when the user is
    // obviously still trying to scroll the row left.
    private fun maybeScroll() {
        val root = rootInActiveWindow ?: return
        val x = cursorX.toInt()
        val y = cursorY.toInt()

        // Pass 1: only candidates that advertise an action for their edge direction.
        var candidate = findBestScrollCandidate(root, x, y, requireAdvertised = true)
        var advertisedHit = candidate != null
        // Pass 2: any scrollable in an edge zone (legacy behavior).
        if (candidate == null) {
            candidate = findBestScrollCandidate(root, x, y, requireAdvertised = false)
        }
        if (candidate == null) {
            lastScrollActionId = 0
            edgeEnterMs = 0L          // left every edge zone → reset the dwell timer
            return
        }

        val now = android.os.SystemClock.uptimeMillis()

        // Edge dwell: require the cursor to linger briefly in the edge zone before the
        // FIRST scroll, so a quick pass-through or H2F focus jitter near the edge can't
        // kick off a runaway scroll.
        if (edgeEnterMs == 0L) edgeEnterMs = now
        if (now - edgeEnterMs < EDGE_DWELL_MS) return

        // Throttle by sensitivity + tile size: small tiles scroll slower so the user
        // can land on a specific one. Replaces the old fixed SCROLL_MIN_INTERVAL_MS.
        val interval = effectiveScrollIntervalMs(tileSizeFactor(root, x, y))
        if (now - lastScrollFiredMs < interval) return
        lastScrollFiredMs = now

        // Dispatch by the per-app scroll method (probed once, then cached).
        val pkg = root.packageName?.toString().orEmpty()
        val method = scrollMethodCache[pkg]
        val ok: Boolean
        val via: String
        when (method) {
            ScrollMethod.PROBING -> return     // probe in flight; await its verdict
            ScrollMethod.TOUCH   -> { ok = performScrollSwipe(candidate.node, candidate.dir); via = "swipe" }
            ScrollMethod.A11Y    -> { ok = candidate.node.performAction(candidate.actionId); via = a11yVia(candidate.actionId) }
            null                 -> {          // first scroll in this app → probe it
                startScrollProbe(pkg, candidate.node)
                ok = performScrollSwipe(candidate.node, candidate.dir)
                via = "probe"
            }
        }

        if (candidate.actionId != lastScrollActionId || method != lastScrollMethod) {
            val pass = if (advertisedHit) "advertised" else "best-effort"
            candidate.node.getBoundsInScreen(hitTestRect)
            val axis = scrollAxis(candidate.node, hitTestRect)
            val advertised = candidate.node.actionList
                .map { it.id }
                .mapNotNull { id ->
                    when (id) {
                        AccessibilityAction.ACTION_SCROLL_FORWARD.id  -> "FWD"
                        AccessibilityAction.ACTION_SCROLL_BACKWARD.id -> "BACK"
                        AccessibilityAction.ACTION_SCROLL_LEFT.id     -> "L"
                        AccessibilityAction.ACTION_SCROLL_RIGHT.id    -> "R"
                        AccessibilityAction.ACTION_SCROLL_UP.id       -> "U"
                        AccessibilityAction.ACTION_SCROLL_DOWN.id     -> "D"
                        else -> null
                    }
                }
                .joinToString(",")
            Log.i(TAG, "scroll ${candidate.dir.name} ok=$ok via=$via method=${method ?: "PROBE"} pass=$pass axis=$axis cur=($x,$y) bounds=$hitTestRect advertises=[$advertised] on '${candidate.node.className}' pkg=$pkg")
            lastScrollActionId = candidate.actionId
            lastScrollMethod = method
        }
    }

    private fun a11yVia(actionId: Int): String = when (actionId) {
        AccessibilityAction.ACTION_SCROLL_LEFT.id,
        AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        AccessibilityAction.ACTION_SCROLL_UP.id,
        AccessibilityAction.ACTION_SCROLL_DOWN.id -> "directional"
        else -> "FWD/BACK"
    }

    // Drive a scroll with a real touch swipe over `node`, in the cursor's signalled
    // direction. To reveal content toward `dir` the finger travels the OPPOSITE way
    // (to see lower content, drag up). Distance is sensitivity-driven; duration is a
    // slow drag (SWIPE_MS_PER_PX) so it scrolls ~by the swipe length instead of
    // flinging past it.
    //
    // We do NOT use AccessibilityService.dispatchGesture here: on this TV the input
    // dispatcher drops a11y-dispatched gestures, so they never scroll (proven — the
    // probe always came back "ignored touch", yet a real `input touchscreen swipe`
    // works). Instead we hand the computed swipe to the phone (TouchSwipeMessage over
    // the WS), which relays it to the warm tv-helper for REAL touch injection as the
    // shell user. Returns true if the message was handed off; the probe's scroll-event
    // check is what actually confirms the app honoured the touch. If the WS isn't up,
    // we return false and the probe falls back to a11y ACTION_SCROLL (today's behaviour).
    private fun performScrollSwipe(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        node.getBoundsInScreen(swipeRect)
        val b = swipeRect
        if (b.width() < 24 || b.height() < 24) return false
        val cx = cursorX.toInt().coerceIn(b.left + 8, b.right - 8)
        val cy = cursorY.toInt().coerceIn(b.top + 8, b.bottom - 8)
        val dist: Int
        val x1: Int; val y1: Int; val x2: Int; val y2: Int
        when (dir) {
            ScrollDir.DOWN, ScrollDir.UP -> {
                dist = (b.height() * swipeFraction(swipeStrengthV)).toInt().coerceIn(20, b.height() - 16)
                val top = (b.centerY() - dist / 2).coerceAtLeast(b.top + 8)
                val bottom = top + dist
                if (dir == ScrollDir.DOWN) { x1 = cx; y1 = bottom; x2 = cx; y2 = top }
                else                       { x1 = cx; y1 = top;    x2 = cx; y2 = bottom }
            }
            ScrollDir.LEFT, ScrollDir.RIGHT -> {
                dist = (b.width() * swipeFraction(swipeStrengthH)).toInt().coerceIn(20, b.width() - 16)
                val left = (b.centerX() - dist / 2).coerceAtLeast(b.left + 8)
                val right = left + dist
                if (dir == ScrollDir.RIGHT) { x1 = right; y1 = cy; x2 = left;  y2 = cy }
                else                        { x1 = left;  y1 = cy; x2 = right; y2 = cy }
            }
        }
        val duration = (dist * SWIPE_MS_PER_PX).toLong().coerceIn(SWIPE_DURATION_MIN_MS, SWIPE_DURATION_MAX_MS)
        val server = WsServer.instance ?: return false
        server.sendToPhone(TouchSwipeMessage(x1, y1, x2, y2, duration.toInt()))
        return true
    }

    // Called when an aim session begins (cursor shown). Re-opens the scroll-method
    // question for apps that previously came back A11Y but haven't exhausted their
    // re-probe budget — the earlier verdict may have been taken before the app's
    // content loaded. TOUCH verdicts (reliable) and capped-out A11Y verdicts (real
    // D-pad apps) are kept. Also clears any stale in-flight probe from a prior session.
    private fun onAimSessionStart() {
        val it = scrollMethodCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            when (e.value) {
                ScrollMethod.TOUCH   -> { /* reliable — keep */ }
                ScrollMethod.A11Y    -> if ((a11yAttempts[e.key] ?: 0) < MAX_A11Y_REPROBES) it.remove()
                ScrollMethod.PROBING -> it.remove()   // stale probe from a prior session
            }
        }
        probeTimeout?.let { mainHandler.removeCallbacks(it) }
        probeTimeout = null
        probePkg = null
        lastScrollMethod = null
    }

    // Begin a touch-vs-a11y probe for `pkg`: mark PROBING, remember the container
    // we're about to swipe, and arm the fallback verdict. The companion swipe is
    // fired by the caller. If a matching TYPE_VIEW_SCROLLED arrives first
    // (onAccessibilityEvent) we conclude TOUCH; if the window lapses with none, A11Y.
    private fun startScrollProbe(pkg: String, node: AccessibilityNodeInfo) {
        scrollMethodCache[pkg] = ScrollMethod.PROBING
        probePkg = pkg
        node.getBoundsInScreen(probeBounds)
        probeTimeout?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (scrollMethodCache[pkg] == ScrollMethod.PROBING) {
                scrollMethodCache[pkg] = ScrollMethod.A11Y
                val attempts = (a11yAttempts[pkg] ?: 0) + 1
                a11yAttempts[pkg] = attempts
                Log.i(TAG, "scroll probe: '$pkg' ignored touch (attempt $attempts) → a11y ACTION_SCROLL")
            }
            probePkg = null
            probeTimeout = null
        }
        probeTimeout = r
        mainHandler.postDelayed(r, SCROLL_PROBE_WINDOW_MS)
    }

    // Map sensitivity (0..100) to a base scroll interval, then lengthen it by
    // tileFactor (>= 1) for small tiles. Piecewise-linear around the 50 default so the
    // slider feels natural: 0 → slow, 50 → ~old default, 100 → fast.
    private fun effectiveScrollIntervalMs(tileFactor: Float): Long {
        val s = scrollSensitivity.coerceIn(0, 100)
        val base = if (s <= 50) {
            SCROLL_INTERVAL_SLOW_MS + (SCROLL_MIN_INTERVAL_MS - SCROLL_INTERVAL_SLOW_MS) * s / 50
        } else {
            SCROLL_MIN_INTERVAL_MS + (SCROLL_INTERVAL_FAST_MS - SCROLL_MIN_INTERVAL_MS) * (s - 50) / 50
        }
        return (base * tileFactor).toLong().coerceIn(SCROLL_INTERVAL_FAST_MS, 1200L)
    }

    // Slow scrolling over small tiles so the user can land on a specific one. Uses the
    // smallest focusable node under the cursor as the "tile"; returns 1f when none is
    // found (e.g. the cursor is pinned to the screen edge, where this can't help — the
    // sensitivity slider + edge dwell cover that case). Tiles smaller than ~22% of the
    // screen's shorter side scale the interval up to 1.8x.
    private fun tileSizeFactor(root: AccessibilityNodeInfo, x: Int, y: Int): Float {
        val tile = findSmallestFocusableContaining(root, x, y) ?: return 1f
        tile.getBoundsInScreen(hoverRect)
        val tileMin = minOf(hoverRect.width(), hoverRect.height()).toFloat()
        val screenMin = minOf(screenW, screenH).toFloat()
        if (tileMin <= 0f || screenMin <= 0f) return 1f
        return (0.22f / (tileMin / screenMin)).coerceIn(1f, 1.8f)
    }

    // Walk the a11y tree, find the smallest visible scrollable whose cursor
    // position is in either an inside-edge zone or an outside approach zone
    // (see edgeDirection). If requireAdvertised is true, only consider nodes
    // that advertise an action for that direction (skips the left rail, lets
    // the row win even when cursor has crossed outside the row's bounds).
    // If false, any directional action ID is allowed (best-effort fallback).
    private fun findBestScrollCandidate(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        requireAdvertised: Boolean,
    ): ScrollCandidate? {
        var best: ScrollCandidate? = null
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(rect)
            // Prune: children's bounds are a subset of the parent's, so if the
            // cursor is more than SCROLL_EDGE_MIN_PX outside this parent's
            // bounds, no descendant's inside-edge or approach zone can match
            // either. Exception: when the cursor is pinned to the screen edge
            // in some direction, skip pruning on that side — a descendant's
            // approach zone may legitimately extend all the way to the screen
            // edge (the rail is 37px wide so cursor at x=0 is only 27px from
            // the row's approach zone, which fits, but for wider edge widgets
            // a fixed approach distance can fall short).
            val pruneLeft   = x > 0           && x < rect.left   - SCROLL_EDGE_MIN_PX
            val pruneRight  = x < screenW - 1 && x > rect.right  + SCROLL_EDGE_MIN_PX
            val pruneTop    = y > 0           && y < rect.top    - SCROLL_EDGE_MIN_PX
            val pruneBottom = y < screenH - 1 && y > rect.bottom + SCROLL_EDGE_MIN_PX
            if (pruneLeft || pruneRight || pruneTop || pruneBottom) return
            if (!n.isVisibleToUser) return
            if (n.isScrollable) {
                val dir = edgeDirection(rect, x, y)
                if (dir != null) {
                    val actionId = pickAction(n, dir, rect, requireAdvertised)
                    if (actionId != null) {
                        val area = rect.width().toLong() * rect.height().toLong()
                        if (best == null || area < best!!.area) {
                            best = ScrollCandidate(n, dir, actionId, area)
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
    }

    // Which scroll direction (if any) the cursor is signaling for these bounds.
    // Two cases:
    //   - Inside-edge zone: cursor is inside the bounds, near one of the four
    //     edges. SCROLL_EDGE_FRACTION/MIN_PX defines how thick the edge band is,
    //     capped so top+bottom (or left+right) bands never overlap on short
    //     containers — e.g. a 114px-tall row with a 60px floor would otherwise
    //     trigger UP/DOWN scroll on every point in the row.
    //     Diagonal ties broken by picking the closest edge.
    //   - Outside approach zone: cursor is OUTSIDE the bounds but within
    //     SCROLL_EDGE_MIN_PX of one edge, and within the perpendicular band of
    //     that edge (e.g. for a horizontal row, cursor's y must still be inside
    //     the row's vertical range). Rescues the common case where gyro flings
    //     the cursor past a row's left edge in a single frame — the user still
    //     clearly wants to scroll the row, not whatever container the cursor
    //     happens to have landed in (the left rail, the outer GridView, etc).
    private fun edgeBandPx(span: Int): Int {
        val fraction = (span * SCROLL_EDGE_FRACTION).toInt()
        val desired = maxOf(fraction, SCROLL_EDGE_MIN_PX)
        // Leave at least 40% of the span as a middle zone with no scroll trigger.
        return minOf(desired, span * 3 / 10).coerceAtLeast(1)
    }

    private fun edgeDirection(r: Rect, x: Int, y: Int): ScrollDir? {
        if (r.contains(x, y)) {
            val edgeX = edgeBandPx(r.width())
            val edgeY = edgeBandPx(r.height())
            val dLeft   = x - r.left
            val dRight  = r.right - x
            val dTop    = y - r.top
            val dBottom = r.bottom - y
            return when {
                dLeft < edgeX && dLeft <= dRight && dLeft <= dTop && dLeft <= dBottom -> ScrollDir.LEFT
                dRight < edgeX && dRight <= dTop && dRight <= dBottom -> ScrollDir.RIGHT
                dTop < edgeY && dTop <= dBottom -> ScrollDir.UP
                dBottom < edgeY -> ScrollDir.DOWN
                else -> null
            }
        }
        // Outside approach: cursor is past one edge by <= approach, and within
        // the perpendicular band of that edge. When the cursor is pinned to a
        // screen edge in that direction, the approach distance is unbounded —
        // the user has pushed as far as they can, intent is unambiguous, and
        // a static approach distance may not even reach (e.g. cursor stuck at
        // x=0 behind a 37px rail can only get within 27px of a row at x=64,
        // and a 60px approach falls short by 4px when the screen edge blocks).
        val approach = SCROLL_EDGE_MIN_PX
        val atLeft   = x <= 0
        val atRight  = x >= screenW - 1
        val atTop    = y <= 0
        val atBottom = y >= screenH - 1
        return when {
            x < r.left   && (atLeft   || x >= r.left   - approach) && y in r.top..r.bottom -> ScrollDir.LEFT
            x > r.right  && (atRight  || x <= r.right  + approach) && y in r.top..r.bottom -> ScrollDir.RIGHT
            y < r.top    && (atTop    || y >= r.top    - approach) && x in r.left..r.right -> ScrollDir.UP
            y > r.bottom && (atBottom || y <= r.bottom + approach) && x in r.left..r.right -> ScrollDir.DOWN
            else -> null
        }
    }

    // Choose the action ID for (node, dir). When requireAdvertised, only
    // return an action that's actually in node.actionList — the FWD/BACK
    // fallback maps to direction via an orientation heuristic on bounds
    // (wider than tall → horizontal; taller than wide → vertical). When the
    // shape is roughly square (2D grid) FWD/BACK is ambiguous and we skip the
    // fallback. When !requireAdvertised, just return the raw directional ID
    // and let performAction lie or work as it will.
    private fun pickAction(
        n: AccessibilityNodeInfo,
        dir: ScrollDir,
        bounds: Rect,
        requireAdvertised: Boolean,
    ): Int? {
        val directional = when (dir) {
            ScrollDir.LEFT  -> AccessibilityAction.ACTION_SCROLL_LEFT.id
            ScrollDir.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            ScrollDir.UP    -> AccessibilityAction.ACTION_SCROLL_UP.id
            ScrollDir.DOWN  -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        }
        if (!requireAdvertised) return directional

        val advertised = n.actionList.map { it.id }.toSet()
        if (directional in advertised) return directional

        // FWD/BACK fallback — only safe when we can determine the container's
        // orientation. RecyclerView-based containers vary on whether they
        // advertise the directional variants; many only expose FWD/BACK (e.g.
        // CloudStream's full-screen vertical feed advertises only [FWD]). We use
        // scrollAxis() — collectionInfo first, bounds shape as fallback — instead
        // of bounds alone, because a full-screen vertical list isn't "2× taller
        // than wide" and the old shape-only test misclassified it as ambiguous,
        // so DOWN/UP never mapped to FWD/BACK and the page wouldn't scroll.
        val axis = scrollAxis(n, bounds)
        val horizontal = axis == ScrollAxis.HORIZONTAL
        val vertical   = axis == ScrollAxis.VERTICAL
        val fallback = when (dir) {
            ScrollDir.LEFT  -> if (horizontal) AccessibilityAction.ACTION_SCROLL_BACKWARD.id else null
            ScrollDir.RIGHT -> if (horizontal) AccessibilityAction.ACTION_SCROLL_FORWARD.id  else null
            ScrollDir.UP    -> if (vertical)   AccessibilityAction.ACTION_SCROLL_BACKWARD.id else null
            ScrollDir.DOWN  -> if (vertical)   AccessibilityAction.ACTION_SCROLL_FORWARD.id  else null
        }
        return if (fallback != null && fallback in advertised) fallback else null
    }

    // Orientation of a scrollable, used to map a directional intent (UP/DOWN/
    // LEFT/RIGHT) onto the ACTION_SCROLL_FORWARD/BACKWARD actions that many
    // containers advertise *instead* of the directional ones.
    private enum class ScrollAxis { HORIZONTAL, VERTICAL, UNKNOWN }

    // collectionInfo is the reliable signal: a vertical list reports
    // columnCount == 1, a horizontal list rowCount == 1; a 2D grid reports both
    // > 1 (genuinely ambiguous — leave it to the directional pass). Some nodes
    // report -1 for an unknown count, hence the `> 0` guards. When collectionInfo
    // is absent or ambiguous, fall back to bounds shape: clearly wider →
    // horizontal, clearly taller → vertical, otherwise UNKNOWN.
    //
    // This is what lets CloudStream's full-screen vertical feed (which is NOT
    // "2× taller than wide", so the old bounds-only test gave UNKNOWN) be
    // recognised as vertical, so DOWN maps to its advertised FWD action.
    private fun scrollAxis(n: AccessibilityNodeInfo, bounds: Rect): ScrollAxis {
        n.collectionInfo?.let { ci ->
            val rows = ci.rowCount
            val cols = ci.columnCount
            if (rows > 0 && cols > 0) {
                if (cols == 1 && rows != 1) return ScrollAxis.VERTICAL
                if (rows == 1 && cols != 1) return ScrollAxis.HORIZONTAL
                // both > 1 → genuine 2D grid → ambiguous; don't guess (preserves
                // the tuned Projectivy grid behaviour — fall through to the 2×
                // shape test, which returns UNKNOWN for a square grid).
                if (rows > 1 && cols > 1) {
                    val w = bounds.width(); val h = bounds.height()
                    return when {
                        w >= h * 2 -> ScrollAxis.HORIZONTAL
                        h >= w * 2 -> ScrollAxis.VERTICAL
                        else       -> ScrollAxis.UNKNOWN
                    }
                }
            }
        }
        val w = bounds.width()
        val h = bounds.height()
        return when {
            // Clear cases first: a wide-short strip is a horizontal carousel/row;
            // a tall-narrow strip is a vertical list.
            w >= h * 2 -> ScrollAxis.HORIZONTAL
            h >= w * 2 -> ScrollAxis.VERTICAL
            // Ambiguous aspect (e.g. a full-screen feed: wider than tall on a
            // landscape TV but NOT 2× wide). Aspect ratio can't tell a full-screen
            // vertical feed from a horizontal strip — both are wider than tall. But
            // a horizontal carousel is SHORT (one row of cards), whereas a vertical
            // feed fills most of the screen height. So: a scrollable spanning most
            // of the screen vertically is almost certainly a vertical feed. The
            // clearly-horizontal hero (w≥2h) was already returned above, so it
            // can't reach here.
            h >= (screenH * 0.6).toInt() -> ScrollAxis.VERTICAL
            else -> ScrollAxis.UNKNOWN
        }
    }

    // Click at the current cursor position.
    //
    // Most Android TV apps are D-pad driven, not touch driven, so dispatchGesture
    // lands in the void on launchers / streaming apps. But Projectivy and similar
    // expose two distinct kinds of interactive node in their a11y tree:
    //   - Tiles in a row: BOTH isClickable and isFocusable.
    //   - Action-bar icons (settings, search): isClickable only — the wrapping
    //     GridView/Row owns focus, child icons are visual+clickable.
    //
    // Strategy: pick the smallest visible node under the cursor that's either
    // clickable or focusable; prefer clickable when sizes tie. Then:
    //   - if it's clickable → ACTION_CLICK fires its OnClickListener cross-process.
    //     This is the most reliable activation path: it bypasses focus traversal
    //     entirely, so leanback's focus-trap behaviour (refusing cross-container
    //     focus moves) doesn't matter.
    //   - else (focusable-only, rare) → ACTION_FOCUS. Won't activate by itself;
    //     v1 accepts this as a known gap. Real fix would re-introduce DPAD_CENTER
    //     synthesis via the phone-side ADB helper conditional on this branch.
    //
    // Fallback: if nothing interactive is under the cursor (Chrome viewport,
    // games, anything that handles raw touch), dispatchGesture for a synthetic
    // tap.
    fun click() {
        mainHandler.post {
            val x = cursorX.toInt()
            val y = cursorY.toInt()
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            val target = root?.let { findSmallestInteractiveContaining(it, x, y) }
            if (target != null) {
                val action = if (target.isClickable)
                    AccessibilityNodeInfo.ACTION_CLICK
                else
                    AccessibilityNodeInfo.ACTION_FOCUS
                val ok = target.performAction(action)
                val tag = if (target.isClickable) "click" else "focus"
                // Re-fetch bounds into hitTestRect (the walk left it holding the
                // last-visited node, not necessarily the chosen target).
                target.getBoundsInScreen(hitTestRect)
                Log.i(TAG, "$tag at ($x,$y) on '${target.className}' bounds=$hitTestRect pkg=${target.packageName} ok=$ok")
            } else {
                Log.i(TAG, "no interactive at ($x,$y); root pkg=${root?.packageName} cls=${root?.className}")
                val path = Path().apply { moveTo(cursorX.toFloat(), cursorY.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val accepted = dispatchGesture(gesture, null, null)
                Log.i(TAG, "gesture at ($x, $y) accepted=$accepted")
            }
        }
    }

    // Find the SMALLEST visible node containing (x, y) that's clickable or
    // focusable. "Interactive" = either flag set. Smallest = most specific
    // target (the tile, not the row; the button, not the GridView).
    //
    // Tie-breaker: when two nodes have equal area, prefer clickable — because
    // ACTION_CLICK activates directly while ACTION_FOCUS only moves focus, and
    // we no longer follow with DPAD_CENTER.
    //
    // We walk every descendant containing the cursor (not just the chain
    // above the deepest leaf) so that siblings like header icons aren't
    // missed because the deepest-visible-leaf happens to be a co-located
    // ImageView/TextView that's neither clickable nor focusable.
    //
    // Reusing one Rect across the walk avoids per-node allocation.
    private val hitTestRect = Rect()
    private fun findSmallestInteractiveContaining(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var bestClickable = false
        // Local recursive function — captures the four `best*` vars by closure
        // over the enclosing method's frame, so updates inside the recursion
        // are visible after walk() returns.
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(hitTestRect)
            if (!hitTestRect.contains(x, y)) return
            if (!n.isVisibleToUser) return
            if (n.isClickable || n.isFocusable) {
                // Skip a focusable-only node whose bounds touch the SAME screen
                // edge the cursor is parked against. When the user shoves the
                // cursor left to edge-scroll a row, it settles in the edge band
                // (a few px in — gyro release doesn't pin it exactly at 0).
                // Releasing the aim button fires a click here; without this guard
                // it lands on the launcher's left rail (a focusable-only GridView
                // at left=0) and ACTION_FOCUS pops the leftbar open. We use the
                // scroll edge-zone width (EDGE_MARGIN) because a click released
                // inside that band is almost always the tail of a scroll gesture,
                // not a deliberate click. A *clickable* node at the edge is still
                // allowed (its OnClickListener is intentional); the user can still
                // target a focusable-only widget by aiming away from the edge.
                val focusableOnly = !n.isClickable
                val m = SCROLL_EDGE_MIN_PX
                val sharesScreenEdge =
                    (x <= m && hitTestRect.left <= 0) ||
                    (x >= screenW - 1 - m && hitTestRect.right >= screenW) ||
                    (y <= m && hitTestRect.top <= 0) ||
                    (y >= screenH - 1 - m && hitTestRect.bottom >= screenH)
                if (!(focusableOnly && sharesScreenEdge)) {
                    val area = hitTestRect.width().toLong() * hitTestRect.height().toLong()
                    // strictly smaller wins; same area with clickable beats focusable-only.
                    val better = best == null ||
                        area < bestArea ||
                        (area == bestArea && n.isClickable && !bestClickable)
                    if (better) {
                        best = n
                        bestArea = area
                        bestClickable = n.isClickable
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
    }

    // Find the SMALLEST visible *focusable* node containing (x, y).
    //
    // This is the hover-to-focus counterpart of findSmallestInteractiveContaining.
    // Difference: click cared about clickable-OR-focusable (ACTION_CLICK is the
    // universal activator); hover-focus can only ever move the highlight, so we
    // only consider nodes that actually accept focus. `isFocusable` is the gate.
    //
    // Smallest-area wins for the same reason as the click path: the tile, not
    // the row that contains it. We reuse hoverRect (a second scratch Rect) so we
    // don't stomp hitTestRect, which the click path owns.
    //
    // Screen-edge guard (mirrors the click path's S-2 fix): a focusable node
    // whose bounds touch the same screen edge the cursor is pinned against is
    // skipped. Projectivy's left rail is a full-height GridView at left=0;
    // focusing it expands the leftbar. When the user shoves the cursor to the
    // left edge (to edge-scroll a row), the cursor settles in the edge band and
    // we'd otherwise focus the rail and pop the leftbar open. Same for all four
    // edges. Tradeoff (same as S-2): the rail is unreachable via hover — open it
    // with D-pad LEFT instead.
    private val hoverRect = Rect()
    private fun findSmallestFocusableContaining(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(hoverRect)
            if (!hoverRect.contains(x, y)) return
            if (!n.isVisibleToUser) return
            if (n.isFocusable) {
                val m = SCROLL_EDGE_MIN_PX
                val sharesScreenEdge =
                    (x <= m && hoverRect.left <= 0) ||
                    (x >= screenW - 1 - m && hoverRect.right >= screenW) ||
                    (y <= m && hoverRect.top <= 0) ||
                    (y >= screenH - 1 - m && hoverRect.bottom >= screenH)
                // Exclude near-full-screen containers (root FrameLayout, outer
                // GridView). They're focusable and contain every point, but the
                // user never aims AT them — and treating them as a focus target
                // would (a) waste a failing ACTION_FOCUS and (b) suppress the
                // edge-scroll fallback everywhere via the scroll gate. 90% of the
                // screen area is the cutoff: real items are far smaller.
                val area = hoverRect.width().toLong() * hoverRect.height().toLong()
                val screenArea = screenW.toLong() * screenH.toLong()
                val nearFullScreen = area >= screenArea * 9 / 10
                if (!sharesScreenEdge && !nearFullScreen) {
                    if (area < bestArea) {
                        best = n
                        bestArea = area
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
    }
}
