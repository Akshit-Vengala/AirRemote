package com.airremote.phone

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * EditText that fires `onEmptyBackspace` when the user presses backspace
 * while the field is already empty.
 *
 * Why this exists: in live-mirror mode the phone EditText is the source of truth
 * for what's on the TV — typing/backspacing on the phone propagates to TV through
 * a TextWatcher diff. But TextWatcher only fires when the EditText's text actually
 * changes. Backspace on an empty field doesn't change anything locally, so without
 * intercepting it the user has no way to delete text that exists ONLY on TV (e.g.
 * a search box that retained text from before they opened the keyboard).
 *
 * How: when the IME wants to delete text, it calls one of two methods on the
 * InputConnection — `deleteSurroundingText` (the modern path, used by Gboard et al.)
 * or `sendKeyEvent(KEYCODE_DEL)` (the legacy path). We wrap the default
 * InputConnection and intercept both, firing the callback when the field is empty.
 *
 * This is the same pattern WhatsApp/Telegram use for "backspace on empty input →
 * delete the previous message bubble."
 */
class BackspaceAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    // Lambda fired when the user attempts a backspace while text is empty.
    // The owning Activity sets this; we don't do anything by default.
    var onEmptyBackspace: (() -> Unit)? = null

    // onCreateInputConnection is called by the framework when the EditText gains
    // focus and the IME needs a channel to send characters/commands into the view.
    // We let the parent build the default InputConnection, then wrap it so our
    // overrides see every IME call.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // InputConnectionWrapper(target, mutable): wraps `target` and forwards any
        // method we don't override. `mutable=true` means we may also chain wrappers
        // on top of this one. We won't, but it's the conventional value.
        return BackspaceConnection(base, true)
    }

    // `inner` makes this class hold an implicit reference to its enclosing
    // BackspaceAwareEditText, so we can read `text` and call `onEmptyBackspace` directly.
    private inner class BackspaceConnection(
        target: InputConnection,
        mutable: Boolean,
    ) : InputConnectionWrapper(target, mutable) {

        // Modern path: most soft keyboards call this for backspace. `beforeLength`
        // is how many chars before the cursor to delete; `afterLength` is after.
        // If the field is empty AND beforeLength > 0, the framework would no-op —
        // that's our signal that the user pressed backspace with nothing to remove.
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (text.isNullOrEmpty() && beforeLength > 0) {
                onEmptyBackspace?.invoke()
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        // Legacy path: some older IMEs / hardware-style keyboard simulators send a
        // raw KEYCODE_DEL via this method instead of using deleteSurroundingText.
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_DEL &&
                text.isNullOrEmpty()
            ) {
                onEmptyBackspace?.invoke()
            }
            return super.sendKeyEvent(event)
        }
    }
}
