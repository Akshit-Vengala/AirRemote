package com.airremote.helper

import android.graphics.Point
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

// `InputManager.injectInputEvent(InputEvent, int)` is a hidden API (@hide).
// The `mode` parameter has three values defined in the framework source:
//   0 = INJECT_INPUT_EVENT_MODE_ASYNC       — fire-and-forget, returns immediately
//   1 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
//   2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
// We want ASYNC: minimum latency, no need to wait for the framework to dispatch.
private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

// AudioManager constants. We don't `import` the framework values because nothing
// else in this file depends on them, and copying the two ints keeps the helper's
// dependency surface minimal.
// How long to "hold" a key for a synthetic long-press before releasing it.
// The framework's default long-press timeout is ViewConfiguration.getLongPressTimeout()
// ≈ 500ms; 600 gives a safety margin so views with a slightly longer threshold
// still register the hold. See injectLongPress().
private const val LONG_PRESS_HOLD_MS = 600L

private const val STREAM_MUSIC = 3   // AudioManager.STREAM_MUSIC
private const val FLAG_SHOW_UI = 1   // AudioManager.FLAG_SHOW_UI — triggers the
                                     // native volume OSD overlay, same one the
                                     // hardware remote and `cmd media_session` show.

// Bundles the audio-related reflection handles so handle() doesn't grow a long
// arg list. `private class` at top level = file-private (Kotlin's default for
// top-level declarations is `public`; `private` here restricts to this file).
private class AudioHandles(
    val service: Any,
    val setStreamVolume: java.lang.reflect.Method,
    // Queried once at boot via getStreamMaxVolume(STREAM_MUSIC). Constant per
    // device — typically 15 on stock Android TV, 30 on some OnePlus builds.
    val streamMax: Int,
)

// Cursor state. The phone sends *deltas* (`mouse dx dy`), so the helper has to
// remember where the cursor was last time and accumulate. `var` (mutable) here
// because they're updated every motion sample. Top-level file-private — only
// the command loop touches them, no concurrency issues (stdin is single-threaded).
private var cursorX: Float = 0f
private var cursorY: Float = 0f
private var screenW: Int = 1920   // overwritten at boot from the real display
private var screenH: Int = 1080

/**
 * Standalone helper run on the TV via `app_process`. Reads stdin line-by-line and
 * injects input events directly through the framework's InputManager, bypassing the
 * per-call JVM cold-start that `input keyevent` pays. See Plan 3 in the plan file.
 *
 * Protocol (text, one command per line):
 *   key <int>     — inject a keyevent (ACTION_DOWN then ACTION_UP) for keycode <int>
 *   longpress <int> — inject a held keyevent (DOWN, long-press repeat, UP) for keycode <int>
 *   text <str>    — type a string via KeyCharacterMap-expanded keystrokes
 *   vol <0..100>  — set STREAM_MUSIC volume to <percent>% of the device's max
 *   swipe <x1> <y1> <x2> <y2> <durMs> — inject a real touchscreen drag (DOWN, MOVEs, UP).
 *                   Used by tv-app for cursor-driven scrolling: dispatchGesture is
 *                   dropped by this TV's dispatcher, but shell-injected touch works.
 *   mouse <dx> <dy> — move the system mouse pointer by a delta (HOVER_MOVE)
 *   click         — primary click at the current pointer position (DOWN/UP)
 *   ping          — reply "pong\n" on stdout (health check)
 *   <blank>       — ignored
 *   <unknown>     — logged to stderr, ignored
 *
 * Exits cleanly on stdin EOF (i.e. when the ADB shell stream is dropped).
 */
fun main() {
    // ─── Reflection bootstrap ─────────────────────────────────────────────────
    // We can't `import android.hardware.input.InputManager` and call its methods
    // directly because both `getInstance()` and `injectInputEvent()` are @hide —
    // they exist on the device but aren't in the public SDK we compile against.
    // Reflection sidesteps the compile-time check, and the runtime check (Android's
    // hidden-API enforcement) is bypassed for processes launched by the shell user.

    val inputManagerClass = Class.forName("android.hardware.input.InputManager")
    // getDeclaredMethod (not getMethod) — getInstance() is static; declared on this class.
    val inputManager = inputManagerClass.getDeclaredMethod("getInstance").invoke(null)
    // getMethod is fine for injectInputEvent — it's public (just @hide-annotated).
    val injectInputEvent = inputManagerClass.getMethod(
        "injectInputEvent",
        Class.forName("android.view.InputEvent"),
        Int::class.javaPrimitiveType,
    )

    // ─── Audio reflection bootstrap ──────────────────────────────────────────
    // Same dodge as InputManager above: IAudioService is @hide. We obtain it
    // via ServiceManager.getService("audio") (returns an IBinder) and wrap that
    // with IAudioService$Stub.asInterface. The `\$` escapes Kotlin's string
    // interpolation so the dollar reaches Class.forName as a literal — required
    // because Java inner classes are named `Outer$Inner` at the bytecode level.
    //
    // Permission: shell user (UID 2000) can already drive this service via
    // `cmd media_session volume --set …`, so the same calls succeed from here.
    val serviceManager = Class.forName("android.os.ServiceManager")
    val audioBinder = serviceManager.getMethod("getService", String::class.java)
        .invoke(null, "audio")  // null = static method, no receiver instance
    val audioStub = Class.forName("android.media.IAudioService\$Stub")
    val audioService = audioStub.getMethod("asInterface", android.os.IBinder::class.java)
        .invoke(null, audioBinder)
        ?: error("IAudioService.asInterface returned null") // ?: = Elvis; error() throws IllegalStateException

    // API 30 signature: setStreamVolume(int streamType, int index, int flags, String callingPackage).
    // `Int::class.javaPrimitiveType` is Kotlin's way of getting `int.class` (primitive),
    // distinct from `Int::class.java` which is `Integer.class` (boxed). The framework
    // method takes primitive ints, so we must match exactly or getMethod throws NSME.
    val setStreamVolume = audioService.javaClass.getMethod(
        "setStreamVolume",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, String::class.java,
    )
    val getStreamMaxVolume = audioService.javaClass.getMethod(
        "getStreamMaxVolume", Int::class.javaPrimitiveType,
    )
    // The return value is a boxed Integer from reflection; `as Int` unboxes.
    // `coerceAtLeast(1)` is defensive — never let our percent-to-index divisor
    // be zero (would crash with ArithmeticException on the first vol command).
    val streamMax = (getStreamMaxVolume.invoke(audioService, STREAM_MUSIC) as Int)
        .coerceAtLeast(1)
    val audio = AudioHandles(audioService, setStreamVolume, streamMax)

    // ─── Screen size lookup ──────────────────────────────────────────────────
    // We need the real display size (not just the app-visible area) so the cursor
    // can travel edge-to-edge. DisplayManagerGlobal.getInstance().getRealDisplay
    // (DEFAULT_DISPLAY) returns a Display object whose getRealSize(Point) fills
    // physical pixel dimensions including any system bars.
    //
    // All @hide, so reflection again. `getRealSize(Point)` mutates the Point in
    // place (no return value) — Java idiom, predates value types.
    val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
    val dmg = dmgClass.getMethod("getInstance").invoke(null)
    val realDisplay = dmgClass.getMethod("getRealDisplay", Int::class.javaPrimitiveType)
        .invoke(dmg, Display.DEFAULT_DISPLAY) as Display
    val size = Point()
    realDisplay.getRealSize(size)
    screenW = size.x
    screenH = size.y
    // Start cursor centred — first `mouse dx dy` moves from here.
    cursorX = screenW / 2f
    cursorY = screenH / 2f

    // ─── stderr is unbuffered, visible via the ADB shell-v2 protocol's STDERR
    //     packets (see dadb.AdbShellPacket.StdError). Useful for diagnostics
    //     even when stdout is captured by the controller. ────────────────────
    System.err.println("airremote-helper: ready (pid=${android.os.Process.myPid()}, streamMax=$streamMax, screen=${screenW}x${screenH})")

    // ─── Command loop ─────────────────────────────────────────────────────────
    // BufferedReader wraps stdin so readLine() works (raw System.`in` is byte-level).
    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        // readLine returns null on EOF — the ADB shell stream was closed by the
        // controller (or by the device going to sleep). We exit cleanly so adbd
        // can reap us; the controller will re-push + re-launch on next connect.
        //
        // Do NOT trim — trailing whitespace is meaningful (e.g. `text  ` means
        // "type a single space character", and trimming would mangle it).
        // readLine already strips the terminating \n / \r\n.
        val line = reader.readLine() ?: break
        if (line.isEmpty()) continue
        try {
            handle(line, inputManager, injectInputEvent, audio)
        } catch (t: Throwable) {
            // Don't die on a single bad command — log and keep looping.
            System.err.println("airremote-helper: error '$line': ${t::class.java.simpleName}: ${t.message}")
        }
    }
    System.err.println("airremote-helper: stdin closed, exiting")
}

private fun handle(
    line: String,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
    audio: AudioHandles,
) {
    val parts = line.split(' ')
    when (parts[0]) {
        "key" -> injectKey(parts[1].toInt(), inputManager, injectInputEvent)
        "longpress" -> injectLongPress(parts[1].toInt(), inputManager, injectInputEvent)
        // For `text`, everything after the first space is the literal string to type —
        // including any further spaces. `substringAfter("text ")` preserves them; a
        // `split(' ')` + join would collapse runs of whitespace.
        //
        // missingDelimiterValue="" — if the line isn't actually a well-formed
        // `text <stuff>` command (no space after "text"), return empty rather
        // than the default behavior of returning the whole input string (which
        // would inject the literal word "text" — the exact bug we just fixed).
        "text" -> injectText(line.substringAfter("text ", missingDelimiterValue = ""), inputManager, injectInputEvent)
        "vol" -> injectVolume(parts[1].toInt(), audio)
        "swipe" -> injectSwipe(parts, inputManager, injectInputEvent)
        "mouse" -> injectMouseMove(parts[1].toInt(), parts[2].toInt(), inputManager, injectInputEvent)
        "click" -> injectMouseClick(inputManager, injectInputEvent)
        "ping" -> {
            println("pong")
            System.out.flush() // stdout is buffered — flush so the reply arrives now
        }
        else -> System.err.println("airremote-helper: unknown command '${parts[0]}'")
    }
}

private fun injectText(
    text: String,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    // KeyCharacterMap is the framework's char→keystrokes translator. It knows
    // e.g. "A" requires SHIFT_DOWN + KEYCODE_A_DOWN + KEYCODE_A_UP + SHIFT_UP.
    // VIRTUAL_KEYBOARD is the deviceId for synthesised input (-1) — pairs with
    // the deviceId we already pass to KeyEvent in injectKey.
    val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    // getEvents returns the DOWN/UP sequence to type the chars, or null if ANY
    // char in the input can't be produced by this layout (most Unicode, emoji,
    // etc.). We drop the whole batch in that case rather than type a half-string.
    val events: Array<KeyEvent>? = kcm.getEvents(text.toCharArray())
    if (events == null) {
        System.err.println("airremote-helper: text has unsupported chars: '$text'")
        return
    }
    for (e in events) {
        // Events from KeyCharacterMap come with source=0 (UNKNOWN). Some focused
        // EditTexts (and most fullscreen players) ignore source-less events.
        // Setting SOURCE_KEYBOARD matches what a hardware keyboard reports —
        // same trick injectKey uses. `e.source =` is Kotlin property syntax for
        // the inherited InputEvent.setSource(int) setter.
        if (e.source == 0) e.source = InputDevice.SOURCE_KEYBOARD
        injectInputEvent.invoke(inputManager, e, INJECT_INPUT_EVENT_MODE_ASYNC)
    }
}

// Builds one KeyEvent with the fields Android TV's input dispatcher expects.
//
// KeyEvent's full constructor — modelled after scrcpy's wrappers. The shorter
// `KeyEvent(action, code)` overload defaults `source` to UNKNOWN (0), which some
// apps (especially fullscreen video players) reject. Setting source to
// SOURCE_KEYBOARD makes the event look like it came from a hardware keyboard.
//
// downTime = when the gesture's first DOWN happened; eventTime = now. For a
// single tap the two are equal; for a long-press every event in the DOWN→UP
// sequence shares ONE downTime so the framework groups them as one press.
// deviceId=VIRTUAL_KEYBOARD (-1) marks this synthesised. `repeat` is the
// auto-repeat counter (0 = first press); `flags` carries e.g. FLAG_LONG_PRESS.
private fun makeKey(downTime: Long, action: Int, code: Int, repeat: Int, flags: Int): KeyEvent {
    return KeyEvent(
        /* downTime  */ downTime,
        /* eventTime */ SystemClock.uptimeMillis(),
        /* action    */ action,
        /* code      */ code,
        /* repeat    */ repeat,
        /* metaState */ 0,
        /* deviceId  */ KeyCharacterMap.VIRTUAL_KEYBOARD,
        /* scancode  */ 0,
        /* flags     */ flags,
        /* source    */ InputDevice.SOURCE_KEYBOARD,
    )
}

private fun injectKey(
    code: Int,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    val now = SystemClock.uptimeMillis()
    injectInputEvent.invoke(inputManager, makeKey(now, KeyEvent.ACTION_DOWN, code, 0, 0), INJECT_INPUT_EVENT_MODE_ASYNC)
    injectInputEvent.invoke(inputManager, makeKey(now, KeyEvent.ACTION_UP, code, 0, 0), INJECT_INPUT_EVENT_MODE_ASYNC)
}

private fun injectLongPress(
    code: Int,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    // Replicate what the framework emits for a physically HELD key, so the
    // receiving view's long-press logic fires (this is how launchers like
    // flauncher open a tile's context menu):
    //   1. ACTION_DOWN, repeat=0          — press begins
    //   2. wait past the long-press timeout
    //   3. ACTION_DOWN, repeat=1, FLAG_LONG_PRESS — triggers onKeyLongPress() for
    //      handlers using KeyEvent.startTracking(); the elapsed hold also satisfies
    //      View.setOnLongClickListener's own timer
    //   4. ACTION_UP                       — release
    //
    // All four share ONE downTime so the framework treats them as a single press.
    //
    // Run on a throwaway Thread: the ~600ms Thread.sleep would otherwise block the
    // single-threaded stdin command loop, freezing every other key/cursor command
    // for the duration of the hold. A Thread here keeps the loop responsive.
    Thread {
        val downTime = SystemClock.uptimeMillis()
        injectInputEvent.invoke(inputManager, makeKey(downTime, KeyEvent.ACTION_DOWN, code, 0, 0), INJECT_INPUT_EVENT_MODE_ASYNC)
        Thread.sleep(LONG_PRESS_HOLD_MS)
        injectInputEvent.invoke(inputManager, makeKey(downTime, KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS), INJECT_INPUT_EVENT_MODE_ASYNC)
        injectInputEvent.invoke(inputManager, makeKey(downTime, KeyEvent.ACTION_UP, code, 0, 0), INJECT_INPUT_EVENT_MODE_ASYNC)
    }.start()
}

private fun injectMouseMove(
    dx: Int,
    dy: Int,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    // Accumulate the delta into the persistent cursor position, then clamp to the
    // screen rectangle so the cursor can't be lost off-edge. coerceIn(min, max) is
    // Kotlin's clamp; the `- 1` on the max accounts for the half-open coordinate
    // range (a 1920-wide screen has valid x in [0, 1919]).
    cursorX = (cursorX + dx).coerceIn(0f, (screenW - 1).toFloat())
    cursorY = (cursorY + dy).coerceIn(0f, (screenH - 1).toFloat())

    // Build a HOVER_MOVE event at the new cursor position. SOURCE_MOUSE +
    // TOOL_TYPE_MOUSE is what makes the framework treat this as the system
    // pointer (show the cursor, route HOVER_* to focused views) rather than
    // as a stray touch. See buildMouseEvent for the full field rationale.
    //
    // For hover events downTime is the same as eventTime — there's no "press
    // started at" reference; we pass `now` for both.
    val now = SystemClock.uptimeMillis()
    val event = buildMouseEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0f, now)
    injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
    // MotionEvent uses a native pool — recycle() returns it. Skipping this won't
    // crash but slowly leaks native memory; at 125Hz it adds up.
    event.recycle()
}

// Shared MotionEvent factory. Pulled out so mouse-move and click build events
// with identical pointer/source/device fields — the framework drops events
// whose pointer identity doesn't match a prior ENTER, so consistency matters.
//
// `action` picks the gesture (HOVER_MOVE, HOVER_EXIT, ACTION_DOWN, etc.).
// `buttonState` is 0 for hover/up, BUTTON_PRIMARY (=1) for the held DOWN +
// the matching UP — the framework uses it to know which button was released.
// `pressure` is 0 for hover (no contact), 1 for down (contact made).
private fun buildMouseEvent(action: Int, buttonState: Int, pressure: Float, downTime: Long): MotionEvent {
    val props = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
    }
    val coords = MotionEvent.PointerCoords().apply {
        x = cursorX
        y = cursorY
        this.pressure = pressure
        size = 1f
    }
    val now = SystemClock.uptimeMillis()
    return MotionEvent.obtain(
        downTime, now, action, 1,
        arrayOf(props), arrayOf(coords),
        0, buttonState,
        1f, 1f, 0, 0,
        InputDevice.SOURCE_MOUSE, 0,
    )
}

private fun injectMouseClick(
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    // ─── Click sequence: HOVER_EXIT → ACTION_DOWN → ACTION_UP → HOVER_ENTER ──
    // The pointer must "exit hover" before it can register a DOWN, otherwise the
    // framework will be in a state where it thinks the mouse is simultaneously
    // hovering AND pressing — some views handle it, many drop the DOWN.
    //
    // downTime is shared across DOWN and UP (a press is one gesture); for the
    // surrounding HOVER events we just use `now` — they're independent.
    val downTime = SystemClock.uptimeMillis()

    val exit  = buildMouseEvent(MotionEvent.ACTION_HOVER_EXIT,  0, 0f, downTime)
    val down  = buildMouseEvent(MotionEvent.ACTION_DOWN,        MotionEvent.BUTTON_PRIMARY, 1f, downTime)
    val up    = buildMouseEvent(MotionEvent.ACTION_UP,          MotionEvent.BUTTON_PRIMARY, 0f, downTime)
    val enter = buildMouseEvent(MotionEvent.ACTION_HOVER_ENTER, 0, 0f, downTime)

    injectInputEvent.invoke(inputManager, exit,  INJECT_INPUT_EVENT_MODE_ASYNC)
    injectInputEvent.invoke(inputManager, down,  INJECT_INPUT_EVENT_MODE_ASYNC)
    injectInputEvent.invoke(inputManager, up,    INJECT_INPUT_EVENT_MODE_ASYNC)
    injectInputEvent.invoke(inputManager, enter, INJECT_INPUT_EVENT_MODE_ASYNC)

    exit.recycle(); down.recycle(); up.recycle(); enter.recycle()
}

// Drops overlapping swipes: each swipe runs ~150-500ms on its own thread, and two
// concurrent touch streams (two simultaneous "fingers") confuse the dispatcher. The
// tv-app side already throttles scrolls, so a rare drop just skips one scroll tick.
private val swipeInProgress = AtomicBoolean(false)

// Inject a REAL touchscreen drag: ACTION_DOWN at (x1,y1), a series of ACTION_MOVEs
// interpolating to (x2,y2) over durMs, then ACTION_UP. This is exactly what
// `input touchscreen swipe` does internally — the path we verified scrolls apps that
// ignore AccessibilityService.dispatchGesture on this TV. Coordinates are absolute
// screen pixels supplied by tv-app (it owns the cursor position + container geometry).
//
// Runs on a throwaway Thread (like injectLongPress) so the inter-move sleeps don't
// freeze the single-threaded stdin command loop (keys would stutter otherwise).
private fun injectSwipe(
    parts: List<String>,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    if (parts.size < 6) {
        System.err.println("airremote-helper: swipe needs 5 args, got ${parts.size - 1}")
        return
    }
    val x1 = parts[1].toFloat(); val y1 = parts[2].toFloat()
    val x2 = parts[3].toFloat(); val y2 = parts[4].toFloat()
    val durMs = parts[5].toLong().coerceAtLeast(1L)

    // Drop if a swipe is already running — see swipeInProgress.
    if (!swipeInProgress.compareAndSet(false, true)) return
    Thread {
        try {
            val downTime = SystemClock.uptimeMillis()
            injectTouch(MotionEvent.ACTION_DOWN, x1, y1, downTime, inputManager, injectInputEvent)
            // ~60 steps/sec, matching `input`'s swipe granularity: a smooth drag that
            // scrolls by the path length (a slow drag, not a fling).
            val steps = (durMs / 16L).toInt().coerceIn(2, 60)
            val stepSleep = durMs / steps
            for (i in 1..steps) {
                Thread.sleep(stepSleep)
                val t = i.toFloat() / steps
                injectTouch(
                    MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * t,
                    y1 + (y2 - y1) * t,
                    downTime, inputManager, injectInputEvent,
                )
            }
            injectTouch(MotionEvent.ACTION_UP, x2, y2, downTime, inputManager, injectInputEvent)
        } catch (t: Throwable) {
            System.err.println("airremote-helper: swipe error: ${t::class.java.simpleName}: ${t.message}")
        } finally {
            swipeInProgress.set(false)
        }
    }.start()
}

// One touch MotionEvent (single finger). SOURCE_TOUCHSCREEN + TOOL_TYPE_FINGER +
// pressure=1 is what a real finger reports and what `input touchscreen swipe` builds;
// apps that drove our old SOURCE_MOUSE events into the void DO honour these. All
// events in one drag share `downTime` so the framework groups them as one gesture.
private fun injectTouch(
    action: Int,
    x: Float,
    y: Float,
    downTime: Long,
    inputManager: Any,
    injectInputEvent: java.lang.reflect.Method,
) {
    val props = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    val coords = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }
    val event = MotionEvent.obtain(
        downTime, SystemClock.uptimeMillis(), action, 1,
        arrayOf(props), arrayOf(coords),
        0, 0,           // metaState, buttonState
        1f, 1f, 0, 0,   // xPrecision, yPrecision, deviceId, edgeFlags
        InputDevice.SOURCE_TOUCHSCREEN, 0,
    )
    injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
    event.recycle()
}

private fun injectVolume(percent: Int, audio: AudioHandles) {
    // Phone always sends 0..100 — we map to the device's actual STREAM_MUSIC index
    // here so the phone never has to know the TV's volume range. `coerceIn` is
    // Kotlin's clamp; safer than trusting the caller.
    val clamped = percent.coerceIn(0, 100)
    // Round-to-nearest: (p * max + 50) / 100. The +50 before /100 turns floor
    // division into round-half-up. Matters at the extremes — on a max=15 device,
    // percent=99 should land on 15, not 14.
    val index = (clamped * audio.streamMax + 50) / 100
    // setStreamVolume(int streamType, int index, int flags, String callingPackage).
    // FLAG_SHOW_UI makes the OS render its native volume OSD — same overlay the
    // hardware remote triggers, so the user gets familiar visual feedback on the
    // TV even though the command came from our app.
    audio.setStreamVolume.invoke(
        audio.service, STREAM_MUSIC, index, FLAG_SHOW_UI, "com.android.shell"
    )
}
