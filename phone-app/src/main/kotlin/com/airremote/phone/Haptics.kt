package com.airremote.phone

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Tiny haptic-feedback helper.
 *
 * We use [View.performHapticFeedback] rather than the Vibrator API on purpose:
 *  - no permission needed,
 *  - it RESPECTS the user's system haptic/touch-feedback setting automatically, and
 *  - it routes to the device's own haptic engine, so it's crisp on phones with good
 *    actuators and a plain buzz on weaker ones — the "hybrid" with zero extra code.
 *
 * [enabled] is the app-level toggle (a settings switch), layered on top of the system
 * setting. It's a singleton flag because haptics is a cross-cutting concern fired from
 * both DPadView and MainActivity; MainActivity keeps it in sync with the pref.
 */
object Haptics {

    @Volatile
    var enabled = true

    /** Light tap — D-pad direction / OK presses. */
    fun keyTap(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Standard on-screen button press — back/home/power/keyboard/custom. */
    fun buttonPress(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Stronger pulse for a held press crossing into a long-press. */
    fun longPress(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Very light click — one per volume step on the arc (a "notched dial" feel). */
    fun tick(v: View) {
        if (enabled) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Success cue — the air-mouse click actually landing. CONFIRM is API 30+. */
    fun confirm(v: View) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.CONFIRM
        else
            HapticFeedbackConstants.VIRTUAL_KEY
        v.performHapticFeedback(constant)
    }
}
