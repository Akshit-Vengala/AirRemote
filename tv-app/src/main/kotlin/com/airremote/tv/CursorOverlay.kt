package com.airremote.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

private const val TAG = "CursorOverlay"

// On-screen dot rendered by an AccessibilityService.
//
// Two things make this work without any "Display over other apps" prompt:
//  1. We use TYPE_ACCESSIBILITY_OVERLAY — a window type only AccessibilityServices
//     may add, granted implicitly when the user enables the service.
//  2. FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE means the dot never steals focus
//     or eats touches — apps below it behave as if it isn't there.
//
// Coordinate system:
//  - Gravity.TOP or START makes LayoutParams.x/y measure from the top-left.
//  - We re-add the view only once; subsequent moves call updateViewLayout,
//    which is cheap (no re-inflation, just a relayout of one window).
class CursorOverlay(context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // The dot itself: a plain View whose background is a circular GradientDrawable.
    // 24dp converted to px using the display metrics' density factor.
    private val sizePx = (24 * context.resources.displayMetrics.density).toInt()

    private val dot: View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FF3B30")) // iOS-red, easy to spot
            setStroke(
                (2 * context.resources.displayMetrics.density).toInt(),
                Color.WHITE
            )
        }
    }

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private var added = false

    // show() is idempotent — calling it twice won't add the view twice.
    fun show() {
        if (added) return
        try {
            windowManager.addView(dot, params)
            added = true
            Log.i(TAG, "cursor shown at (${params.x}, ${params.y})")
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
        }
    }

    fun hide() {
        if (!added) return
        try {
            windowManager.removeView(dot)
        } catch (t: Throwable) {
            Log.e(TAG, "removeView failed", t)
        }
        added = false
    }

    // Move the dot to absolute screen pixel (x, y). The dot's top-left
    // corner sits at that point; we subtract half its size so the *center*
    // tracks the coordinate instead.
    fun moveTo(x: Int, y: Int) {
        params.x = x - sizePx / 2
        params.y = y - sizePx / 2
        if (added) {
            try {
                windowManager.updateViewLayout(dot, params)
            } catch (t: Throwable) {
                Log.e(TAG, "updateViewLayout failed", t)
            }
        }
    }

    // Center of the dot in screen pixels — handy for dispatchGesture later.
    val centerX: Int get() = params.x + sizePx / 2
    val centerY: Int get() = params.y + sizePx / 2
}
