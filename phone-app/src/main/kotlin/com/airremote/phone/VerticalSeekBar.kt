package com.airremote.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

// Custom vertical slider. Android's stock SeekBar is horizontal-only; rotating
// it via android:rotation="270" preserves the pre-rotation bounding box (so the
// view measures wide-and-short while drawing tall-and-narrow), which fights
// every layout that contains it. A Canvas-drawn custom View is cleaner and
// matches the existing DPadView pattern in this project.
//
// @JvmOverloads — Android inflates custom Views from XML via reflection and
// always calls the (Context, AttributeSet) constructor. @JvmOverloads tells
// the Kotlin compiler to generate that two-arg overload (and a one-arg) from
// the primary constructor's default arguments. Without it, the View crashes
// on inflate. Same trick DPadView uses.
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Listener mirrors the SeekBar.OnSeekBarChangeListener contract so wiring
    // in MainActivity reads almost identically to the horizontal version.
    // `interface` (not `fun interface`) because there are three abstract methods.
    interface OnSeekBarChangeListener {
        fun onProgressChanged(view: VerticalSeekBar, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(view: VerticalSeekBar)
        fun onStopTrackingTouch(view: VerticalSeekBar)
    }

    var max: Int = 100
    // Custom setter: clamps assignments and invalidates the view so it redraws.
    // `field` is Kotlin's reserved name for the backing field inside an accessor.
    var progress: Int = 30
        set(value) {
            field = value.coerceIn(0, max)
            invalidate()
        }
    var listener: OnSeekBarChangeListener? = null

    // dp → px conversion. `displayMetrics.density` is a float like 2.0 (xhdpi)
    // or 3.0 (xxhdpi) — multiply dp values by it to get raw pixels for Canvas.
    private val density = resources.displayMetrics.density
    private val trackWidthPx = 6f * density
    private val thumbRadiusPx = 14f * density

    // Allocate Paints once. Re-creating them on every onDraw is a classic
    // Android jank cause — Paint construction triggers GC under touch interaction.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F8EFC")  // same blue DPadView uses for highlights
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F8EFC")
        style = Paint.Style.FILL
    }

    // Force a narrow column. If the parent gives us EXACTLY some width, use it;
    // otherwise pick a sensible default (thumb diameter + small margin).
    // Height takes whatever the parent provides — we want to fill a vertical slot.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (thumbRadiusPx * 2 + 8 * density).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        // Default height (200dp) only kicks in if parent says UNSPECIFIED; in any
        // LinearLayout/FrameLayout slot the parent will pass EXACTLY or AT_MOST.
        val height = resolveSize((200 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        // The track stops short of the top/bottom edges by one thumb radius so
        // the thumb never clips off-view at the extremes.
        val top = thumbRadiusPx
        val bottom = h - thumbRadiusPx
        val trackLeft = cx - trackWidthPx / 2f
        val trackRight = cx + trackWidthPx / 2f

        // Full-length background track.
        canvas.drawRect(trackLeft, top, trackRight, bottom, trackPaint)

        // Filled portion: from the thumb down to the bottom (because volume grows
        // upward — the user expects the FILL to represent "how much volume").
        val frac = progress.toFloat() / max
        val thumbY = bottom - (bottom - top) * frac
        canvas.drawRect(trackLeft, thumbY, trackRight, bottom, fillPaint)

        // Thumb (filled circle on top of everything).
        canvas.drawCircle(cx, thumbY, thumbRadiusPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Order matters: fire onStartTrackingTouch BEFORE the position
                // update so the listener can do its "force-sync" using the
                // *current* progress (not the freshly-touched position). The
                // user explicitly wanted bare-touch-no-drag to push the current
                // slider position to TV; onProgressChanged then refines it.
                listener?.onStartTrackingTouch(this)
                updateFromY(event.y, fromUser = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromY(event.y, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener?.onStopTrackingTouch(this)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromY(y: Float, fromUser: Boolean) {
        val top = thumbRadiusPx
        val bottom = height - thumbRadiusPx
        // Clamp so touches above/below the track still produce a sensible value
        // at the extremes rather than going negative or >max.
        val clamped = y.coerceIn(top, bottom)
        // Screen y grows downward; volume grows upward — so invert: y=top→max,
        // y=bottom→0. The (bottom - clamped) / (bottom - top) flip handles it.
        val frac = (bottom - clamped) / (bottom - top)
        val newProgress = (frac * max).toInt().coerceIn(0, max)
        if (newProgress != progress) {
            progress = newProgress  // setter invalidates for redraw
            listener?.onProgressChanged(this, newProgress, fromUser)
        }
    }
}
