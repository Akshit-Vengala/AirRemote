package com.airremote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// `sealed class` — a restricted class hierarchy. Only subclasses declared in this
// file can extend it (Kotlin 1.5+: same package). The compiler knows the complete
// set, so a `when (msg)` expression can be exhaustive: if you add a subclass and
// forget to handle it in a `when`, the compiler flags it as an error.
//
// @Serializable triggers compile-time serializer generation for this whole hierarchy.
// kotlinx-serialization uses a discriminator field ("type") in the JSON to record
// which subclass was serialized, so the decoder can reconstruct the right type.
//
// Note: KeyMessage was removed — key events are now injected via ADB on the phone
// side (as the shell user, which has INJECT_EVENTS permission). Only the gyro stream,
// cursor click, and text input cross the WebSocket wire.
@Serializable
sealed class RemoteMessage

@Serializable
@SerialName("gyro")
data class GyroMessage(
    val dx: Double, // Double (64-bit) not Float — JSON numbers are 64-bit by spec;
    val dy: Double, // Float would lose precision on encode/decode round-trips.
) : RemoteMessage()

// `object` declares a singleton — exactly one ClickMessage instance exists in the JVM.
// No fields needed; the presence of the message is the entire signal.
@Serializable
@SerialName("click")
object ClickMessage : RemoteMessage()

@Serializable
@SerialName("text")
data class TextMessage(
    val value: String,
) : RemoteMessage()

// Show / hide the on-screen cursor overlay. Phone sends true on aim-button DOWN
// (start of an aim session) and false on UP/CANCEL (end). Decoupled from gyro
// so the overlay doesn't have to infer "session start" from the first delta or
// "session end" from an idle timer.
@Serializable
@SerialName("cursor_visibility")
data class CursorVisibilityMessage(
    val visible: Boolean,
) : RemoteMessage()

// Enable / disable the "hover-to-focus" behaviour on the TV. The phone owns the
// user's preference (a checkbox under the settings button) and pushes the
// current value (a) immediately when toggled and (b) once on every WS connect,
// so the TV doesn't need its own persistence. When false, the TV reverts to the
// original edge-scroll-only air-mouse; when true, hovering moves focus and
// edge-scroll is gated behind it. See InputService.setHoverFocusEnabled.
@Serializable
@SerialName("hover_focus")
data class HoverFocusMessage(
    val enabled: Boolean,
) : RemoteMessage()

// ── App shortcuts (custom buttons) ──────────────────────────────────────────
// These are the FIRST messages that travel TV → phone (everything before this is
// phone → TV). The TV's WsServer replies on the same socket; the phone's WsClient
// gains an onMessage handler to receive them.

// Phone → TV: "list your launchable apps." Reply is AppListMessage. An `object`
// (singleton) because the request carries no parameters.
@Serializable
@SerialName("request_app_list")
object RequestAppListMessage : RemoteMessage()

// TV → phone: the launchable apps. NAMES ONLY (no icons) so the list stays light
// even with 40+ apps; the icon for a chosen app is fetched on demand below.
@Serializable
@SerialName("app_list")
data class AppListMessage(
    val apps: List<AppInfo>,
) : RemoteMessage()

// One launchable app. `label` is the human name shown in the picker; `packageName`
// is what the phone passes to launchApp(). Not a RemoteMessage itself — it's a
// nested serializable carried inside AppListMessage.
@Serializable
data class AppInfo(
    val packageName: String,
    val label: String,
)

// Phone → TV: "give me the icon for this one app" — sent only when the user binds
// an app to a custom button, so we never ship icons we won't display.
@Serializable
@SerialName("request_app_icon")
data class RequestAppIconMessage(
    val packageName: String,
) : RemoteMessage()

// TV → phone: the requested icon as a base64-encoded PNG (no data: prefix). Empty
// string if the TV couldn't render it (e.g. package uninstalled meanwhile).
@Serializable
@SerialName("app_icon")
data class AppIconMessage(
    val packageName: String,
    val pngBase64: String,
) : RemoteMessage()

// Phone → TV: hover-to-scroll speed preference, 0..100 (50 = default). The phone
// owns the user's setting (a slider under the settings panel) and pushes it (a) when
// changed and (b) once per WS connect, mirroring HoverFocusMessage. The TV maps it to
// its scroll cadence; higher = faster. See InputService.setScrollSensitivity.
@Serializable
@SerialName("scroll_sensitivity")
data class ScrollSensitivityMessage(
    val value: Int,
) : RemoteMessage()

// TV → phone: perform a REAL touchscreen swipe. The TV's InputService decides the
// geometry (it knows the cursor position + which container to scroll) but cannot
// inject touch itself — it's a regular-app-UID AccessibilityService without
// INJECT_EVENTS, and this TV's input dispatcher drops AccessibilityService
// dispatchGesture for scrolling. So it hands the swipe to the phone, which relays it
// to the warm tv-helper (shell UID, INJECT_EVENTS) over ADB for actual injection.
// Coordinates are absolute screen pixels; durationMs paces the drag (longer = slower,
// so the list scrolls BY the swipe length instead of flinging past it). This is what
// finally gives distance-controlled, non-overshooting scrolling on touch-handling apps.
@Serializable
@SerialName("touch_swipe")
data class TouchSwipeMessage(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Int,
) : RemoteMessage()

// Phone → TV: per-axis swipe STRENGTH, 0..100 each (50 = default). Separate from
// ScrollSensitivityMessage (which tunes the a11y page-scroll cadence) because the real
// touch-swipe path scrolls by a controllable DISTANCE, and the user wants that distance
// tuned independently per axis — e.g. long horizontal swipes to reveal a fresh screen of
// row items, gentler vertical swipes to land on a row. The TV maps each 0..100 to a
// fraction of the scrolled container's span. Pushed on change and once per connect, same
// pattern as the other preference messages. See InputService.setSwipeStrength.
@Serializable
@SerialName("swipe_strength")
data class SwipeStrengthMessage(
    val horizontal: Int,
    val vertical: Int,
) : RemoteMessage()

// Canonical JSON configuration shared by both apps.
val ProtocolJson: Json = Json {
    classDiscriminator = "type"
}
