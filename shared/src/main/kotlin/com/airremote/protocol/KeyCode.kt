package com.airremote.protocol

import kotlinx.serialization.Serializable

// `enum class` — a fixed set of named constants. The compiler knows every possible
// value, so exhaustive `when` branches are checked at compile time.
//
// @Serializable on an enum encodes each entry as its name string by default:
//   DPAD_UP → "DPAD_UP"
// Add @SerialName("up") to an entry to override that string.
@Serializable
enum class KeyCode {
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    OK,           // DPAD_CENTER / select / confirm
    BACK,
    HOME,
    VOLUME_UP,
    VOLUME_DOWN,
    POWER,
}
