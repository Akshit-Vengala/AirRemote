package com.airremote.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.airremote.protocol.KeyCode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Hold-to-repeat tuning. Matches Android's stock long-press / auto-repeat feel —
// the gap before repeat starts is intentionally longer than the inter-repeat
// gap so a quick tap never accidentally registers as multiple presses.
private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 100L

// How long OK must be held before it counts as a long-press (open context menu)
// instead of a tap. Matches Android's stock long-press feel (~500ms).
private const val LONG_PRESS_TIMEOUT_MS = 500L

// ── Volume arc tuning ───────────────────────────────────────────────────────
// The arc lives on the LEFT side of the ring, ~40% of the circumference. Angles
// are degrees, clockwise from 3 o'clock (Android's drawArc convention). 110°→250°
// sweeps through 180° (9 o'clock): the bottom end (110°, ~7–8 o'clock) is volume 0,
// the top end (250°, ~10–11 o'clock) is volume 100, so dragging up = louder.
private const val ARC_START = 110f
private const val ARC_SWEEP = 140f
// How close (in degrees) the touch must land to the knob to count as "grabbing" it.
// Volume only changes by dragging the knob — brushing the arc elsewhere does nothing.
private const val KNOB_GRAB_DEG = 22f
// Double-tap-to-set: two taps within this time, at roughly the same angle, jump the
// volume straight to that point on the arc (no need to find the knob first).
private const val DOUBLE_TAP_MS = 300L
private const val DOUBLE_TAP_DEG = 22f

// Custom View — we extend `View` directly and take over drawing + touch handling.
// Lives inside the Activity's view tree exactly like Button or TextView.
//
// @JvmOverloads — Android inflates custom Views from XML via reflection and ALWAYS
// calls the (Context, AttributeSet) constructor. @JvmOverloads tells the Kotlin
// compiler to generate that two-arg overload (and a one-arg) automatically from the
// primary constructor's default arguments. Without it the View would crash on inflate.
class DPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // `fun interface` — a single-abstract-method interface. Kotlin lets callers
    // assign a plain lambda where one is expected:
    //     dpadView.onKey = OnKeyListener { code -> adbManager.sendKey(code) }
    fun interface OnKeyListener {
        fun onKey(code: KeyCode)
    }

    var onKey: OnKeyListener? = null

    // Fired when the centre OK button is held past LONG_PRESS_TIMEOUT_MS. When this
    // fires, the normal OK tap is suppressed on release (so a long-press sends ONLY
    // the long-press, never an extra tap).
    fun interface OnLongOkListener {
        fun onLongOk()
    }

    var onLongOk: OnLongOkListener? = null

    // Fired (with the new 0..100 level) when the user changes volume via the arc.
    // null-safe lambda; MainActivity throttles + forwards to the driver.
    var onVolume: ((Int) -> Unit)? = null

    // Current volume level the arc renders. Set externally to position the knob;
    // changed internally on drag/double-tap (which also fires onVolume).
    private var volume = 30

    /** Position the knob without firing onVolume (e.g. syncing from elsewhere). */
    fun setVolumeLevel(level: Int) {
        volume = level.coerceIn(0, 100)
        invalidate()
    }

    // setShadowLayer (used below for the raised dome look) is only honoured on a
    // software layer; without this the shadows simply don't draw under hardware
    // acceleration. The D-pad isn't animation-heavy, so a software layer is fine.
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // Drag distance (px) the knob must move before volume starts changing — guards
    // against an accidental brush nudging the level. From the platform's touch slop.
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Paint objects are expensive to allocate. Create once, reuse on every onDraw().
    // GC pressure during touch interactions is a classic Android jank cause.
    //
    // ringPaint/centerPaint get a top→bottom gradient shader (set in onSizeChanged)
    // plus a soft drop shadow, so they read as raised "domed" surfaces like the ref.
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(22f, 0f, 12f, Color.parseColor("#80000000"))
    }
    private val ringPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C8DFF")  // accent blue when a quadrant is held
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(14f, 0f, 6f, Color.parseColor("#66000000"))
    }
    private val centerPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4A78")  // pushed-in tint when OK is held
        style = Paint.Style.FILL
    }
    // Thin chevron arrows (stroked "^" shapes), like the reference D-pad.
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AEBEDD")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // ── Volume arc paints ───────────────────────────────────────────────────
    // The unfilled groove (full arc span), the accent fill (0 → current level),
    // and the raised knob (light fill + soft shadow + accent ring).
    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#10162C")
    }
    private val arcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4C8DFF")
    }
    private val arcKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E8EEFF")
        setShadowLayer(10f, 0f, 3f, Color.parseColor("#80000000"))
    }
    private val arcKnobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4C8DFF")
    }

    // Reusable Path / RectF buffers — same allocate-once pattern as Paints.
    private val highlightPath = Path()
    private val arrowPath = Path()
    private val arcRect = RectF()

    // Which quadrant (or OK) is currently held, if any. Drives the press highlight.
    private var pressedKey: KeyCode? = null

    // Hold-to-repeat: every REPEAT_INTERVAL_MS while a directional arrow is held,
    // re-fire onKey with the same code, then re-schedule ourselves. Cancelled on
    // ACTION_UP / ACTION_CANCEL. `object : Runnable` (anonymous class) lets us
    // call `postDelayed(this, ...)` from inside the run() body to chain repeats.
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            onKey?.onKey(key)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // Set true once a held OK has fired its long-press, so ACTION_UP knows to
    // suppress the would-be tap. Reset on every fresh OK press (ACTION_DOWN).
    private var longOkFired = false

    // Posted on OK press; if it runs (i.e. OK wasn't released in time) it fires the
    // long-press and marks longOkFired so release won't also send a tap.
    private val longPressRunnable = Runnable {
        if (pressedKey == KeyCode.OK) {
            longOkFired = true
            Haptics.longPress(this)
            onLongOk?.onLongOk()
        }
    }

    // ── Volume-arc gesture state ──────────────────────────────────────────────
    private var arcActive = false      // this gesture started in the arc band
    private var arcAdjusting = false   // committed to changing volume (knob grabbed + moved, or double-tap)
    private var grabbedKnob = false    // DOWN landed on the knob
    private var arcDownX = 0f
    private var arcDownY = 0f
    private var lastArcTapMs = 0L
    private var lastArcTapAngle = 0f

    // Geometry cached after layout. Computed in onSizeChanged, consumed in onDraw / onTouchEvent.
    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    // Volume-arc radii: the stroke centreline (arcMid) plus inner/outer touch bounds.
    private var arcMid = 0f
    private var arcInner = 0f
    private var arcOuter = 0f
    private var arcStroke = 0f
    private var knobRadius = 0f

    // Force a square shape: use the smaller of width and height for both dimensions.
    // Without this, a parent that gives us a wide-but-short space would produce a flat
    // oval D-pad — which would still draw but with stretched arc math.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    // Called once after measure/layout completes and again on any size change (rotation, etc.).
    // We cache geometry here instead of recomputing on every onDraw frame.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        val s = min(w, h) / 2f

        // Volume arc occupies the outer band; the D-pad ring sits inside it with a
        // gap, so a normal D-pad touch can never land on the arc (anti-accidental).
        arcOuter  = s * 0.98f
        arcStroke = s * 0.13f
        arcMid    = arcOuter - arcStroke / 2f
        arcInner  = arcOuter - arcStroke
        knobRadius = arcStroke * 0.62f

        val gap = s * 0.06f
        outerRadius = arcInner - gap          // D-pad ring outer edge
        innerRadius = outerRadius * 0.40f     // OK button radius

        arcTrackPaint.strokeWidth = arcStroke
        arcFillPaint.strokeWidth  = arcStroke * 0.72f
        arcKnobRingPaint.strokeWidth = arcStroke * 0.10f

        // Domed gradients: light at the top, dark at the bottom.
        ringPaint.shader = LinearGradient(
            cx, cy - outerRadius, cx, cy + outerRadius,
            Color.parseColor("#2E3E6C"), Color.parseColor("#131B36"),
            Shader.TileMode.CLAMP,
        )
        centerPaint.shader = LinearGradient(
            cx, cy - innerRadius, cx, cy + innerRadius,
            Color.parseColor("#FFFFFF"), Color.parseColor("#D7E2FA"),
            Shader.TileMode.CLAMP,
        )

        arcRect.set(cx - arcMid, cy - arcMid, cx + arcMid, cy + arcMid)
        chevronPaint.strokeWidth = outerRadius * 0.05f
    }

    override fun onDraw(canvas: Canvas) {
        // Draw order is back-to-front (painter's algorithm):

        // 0. Volume arc (sits in the outer band, behind nothing else it overlaps).
        drawVolumeArc(canvas)

        // 1. Domed ring background (gradient + soft shadow)
        canvas.drawCircle(cx, cy, outerRadius, ringPaint)

        // 2. Pressed-quadrant highlight (a 90° pie slice on top of the ring)
        //    `?.let` — null-safe: only runs the block if pressedKey != null.
        pressedKey?.let { key ->
            if (key != KeyCode.OK) drawQuadrantHighlight(canvas, key)
        }

        // 3. Four directional chevrons (the pressed one turns white for contrast)
        drawChevron(canvas, KeyCode.DPAD_UP)
        drawChevron(canvas, KeyCode.DPAD_DOWN)
        drawChevron(canvas, KeyCode.DPAD_LEFT)
        drawChevron(canvas, KeyCode.DPAD_RIGHT)

        // 4. Raised center OK button (drawn last so it sits on top of everything).
        //    Left clean — no "OK" label per design.
        val centerColor = if (pressedKey == KeyCode.OK) centerPressedPaint else centerPaint
        canvas.drawCircle(cx, cy, innerRadius, centerColor)
    }

    private fun drawVolumeArc(canvas: Canvas) {
        // Groove (full span) then accent fill from the bottom up to the current level.
        canvas.drawArc(arcRect, ARC_START, ARC_SWEEP, false, arcTrackPaint)
        val fillSweep = volume / 100f * ARC_SWEEP
        if (fillSweep > 0f) {
            canvas.drawArc(arcRect, ARC_START, fillSweep, false, arcFillPaint)
        }
        // Knob at the current level's angle.
        val knobAngle = Math.toRadians((ARC_START + fillSweep).toDouble())
        val kx = cx + arcMid * cos(knobAngle).toFloat()
        val ky = cy + arcMid * sin(knobAngle).toFloat()
        canvas.drawCircle(kx, ky, knobRadius, arcKnobPaint)
        canvas.drawCircle(kx, ky, knobRadius, arcKnobRingPaint)
    }

    private fun drawQuadrantHighlight(canvas: Canvas, key: KeyCode) {
        // Angles in Android's drawArc/arcTo are measured in degrees,
        // clockwise from 3 o'clock (the +x axis).
        //   3 o'clock = 0°,  6 o'clock = 90°,  9 o'clock = 180°,  12 o'clock = 270°.
        // Each cardinal quadrant occupies a 90° arc centred on its direction.
        val startAngle = when (key) {
            KeyCode.DPAD_RIGHT -> 315f
            KeyCode.DPAD_DOWN  ->  45f
            KeyCode.DPAD_LEFT  -> 135f
            KeyCode.DPAD_UP    -> 225f
            else -> return
        }
        highlightPath.reset()
        highlightPath.moveTo(cx, cy)
        highlightPath.arcTo(
            cx - outerRadius, cy - outerRadius,
            cx + outerRadius, cy + outerRadius,
            startAngle, 90f, false,
        )
        highlightPath.close()
        canvas.drawPath(highlightPath, ringPressedPaint)
    }

    private fun drawChevron(canvas: Canvas, dir: KeyCode) {
        // Each arrow is a thin stroked chevron ("^") pointing outward, sitting
        // between the OK button and the ring's outer edge.
        val apexR = outerRadius * 0.82f   // tip distance from centre (pushed outward)
        val arm   = outerRadius * 0.12f   // half-spread of the chevron arms

        arrowPath.reset()
        when (dir) {
            KeyCode.DPAD_UP -> {
                arrowPath.moveTo(cx - arm, cy - apexR + arm)
                arrowPath.lineTo(cx, cy - apexR)
                arrowPath.lineTo(cx + arm, cy - apexR + arm)
            }
            KeyCode.DPAD_DOWN -> {
                arrowPath.moveTo(cx - arm, cy + apexR - arm)
                arrowPath.lineTo(cx, cy + apexR)
                arrowPath.lineTo(cx + arm, cy + apexR - arm)
            }
            KeyCode.DPAD_LEFT -> {
                arrowPath.moveTo(cx - apexR + arm, cy - arm)
                arrowPath.lineTo(cx - apexR, cy)
                arrowPath.lineTo(cx - apexR + arm, cy + arm)
            }
            KeyCode.DPAD_RIGHT -> {
                arrowPath.moveTo(cx + apexR - arm, cy - arm)
                arrowPath.lineTo(cx + apexR, cy)
                arrowPath.lineTo(cx + apexR - arm, cy + arm)
            }
            else -> return
        }
        chevronPaint.color = if (pressedKey == dir)
            Color.parseColor("#FFFFFF") else Color.parseColor("#AEBEDD")
        canvas.drawPath(arrowPath, chevronPaint)
    }

    // Touch handling. Return true to claim the gesture — otherwise Android stops
    // delivering subsequent events (ACTION_MOVE / ACTION_UP) from this gesture.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // View.dispatchTouchEvent doesn't auto-check isEnabled (only standard
        // widgets like Button do, via their own click handling). For a custom
        // View we have to gate explicitly — without this the D-pad would still
        // highlight and fire onKey even when the activity disables it. Gating here
        // also covers the volume arc (it's part of this same view).
        if (!isEnabled) return false

        val dx = event.x - cx
        val dy = event.y - cy
        val distance = hypot(dx, dy)
        // atan2 → degrees, normalised to [0,360), clockwise from 3 o'clock.
        val angle = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()

        // ── Volume arc takes priority when the touch is in its band ──────────
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (inArcBand(distance, angle)) {
                    arcActive = true
                    arcAdjusting = false
                    arcDownX = event.x
                    arcDownY = event.y
                    val knobAngle = ARC_START + volume / 100f * ARC_SWEEP
                    grabbedKnob = abs(angle - knobAngle) <= KNOB_GRAB_DEG

                    val now = SystemClock.uptimeMillis()
                    if (now - lastArcTapMs <= DOUBLE_TAP_MS &&
                        abs(angle - lastArcTapAngle) <= DOUBLE_TAP_DEG
                    ) {
                        // Second tap on (roughly) the same spot → jump volume there,
                        // and allow this gesture to keep dragging from that point.
                        updateVolumeFromAngle(angle)
                        arcAdjusting = true
                        lastArcTapMs = 0L
                    } else {
                        lastArcTapMs = now
                        lastArcTapAngle = angle
                    }
                    return true
                }
                // not in the arc → fall through to D-pad DOWN below
            }
            MotionEvent.ACTION_MOVE -> {
                if (arcActive) {
                    if (!arcAdjusting && grabbedKnob &&
                        hypot(event.x - arcDownX, event.y - arcDownY) > touchSlopPx
                    ) {
                        arcAdjusting = true   // knob grabbed and dragged past the slop
                    }
                    if (arcAdjusting) updateVolumeFromAngle(angle)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (arcActive) {
                    arcActive = false
                    arcAdjusting = false
                    grabbedKnob = false
                    return true
                }
            }
        }

        // ── D-pad ────────────────────────────────────────────────────────────
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = keyForPosition(dx, dy, distance) ?: return false
                pressedKey = key
                if (key == KeyCode.OK) {
                    // OK is special: we DON'T fire the tap on press. We wait — if the
                    // user releases quickly it's a tap (fired in ACTION_UP); if they
                    // hold past the timeout, longPressRunnable fires the long-press
                    // and ACTION_UP then suppresses the tap. This keeps a long-press
                    // from also emitting a tap.
                    longOkFired = false
                    postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                } else {
                    // Directional arrows fire immediately and auto-repeat while held,
                    // mirroring a hardware remote's D-pad. (No long-press on arrows.)
                    onKey?.onKey(key)
                    Haptics.keyTap(this)   // initial press only — NOT on auto-repeat
                    postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
                }
                invalidate()  // schedules onDraw on the next frame
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Cancel any pending callbacks BEFORE clearing pressedKey, so a tick
                // that fires between these two lines still sees the right key
                // (defensive — the Handler is on this same thread, so in practice it
                // can't run mid-method, but cheap to be safe).
                removeCallbacks(repeatRunnable)
                removeCallbacks(longPressRunnable)
                val wasOk = pressedKey == KeyCode.OK
                if (pressedKey != null) {
                    pressedKey = null
                    invalidate()
                }
                // Fire the deferred OK tap only on a genuine release (ACTION_UP, not
                // CANCEL) that didn't already become a long-press.
                if (wasOk &&
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    !longOkFired
                ) {
                    onKey?.onKey(KeyCode.OK)
                    Haptics.keyTap(this)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // True when (distance, angle) falls inside the volume arc's band. A little radial
    // slop on each side makes the knob comfortably grabbable.
    private fun inArcBand(distance: Float, angle: Float): Boolean {
        val slop = arcStroke * 0.35f
        return distance in (arcInner - slop)..(arcOuter + slop) &&
            angle in ARC_START..(ARC_START + ARC_SWEEP)
    }

    // Maps a touch angle (clamped to the arc span) to a 0..100 level, fires onVolume
    // and repaints only when the level actually changes.
    private fun updateVolumeFromAngle(angle: Float) {
        val clamped = angle.coerceIn(ARC_START, ARC_START + ARC_SWEEP)
        val level = ((clamped - ARC_START) / ARC_SWEEP * 100f).roundToInt().coerceIn(0, 100)
        if (level != volume) {
            volume = level
            Haptics.tick(this)
            onVolume?.invoke(level)
            invalidate()
        }
    }

    private fun keyForPosition(dx: Float, dy: Float, distance: Float): KeyCode? {
        if (distance > outerRadius) return null              // outside the ring
        if (distance <= innerRadius) return KeyCode.OK       // inside OK button

        // atan2(dy, dx) returns radians in (-π, π] with 0 along the +x axis (3 o'clock),
        // increasing CLOCKWISE in screen coordinates (because +y is down on screen).
        // Normalise to [0, 360):
        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360

        // Same 90° wedges as drawQuadrantHighlight — keep these in sync.
        return when (angle) {
            in  45.0..135.0 -> KeyCode.DPAD_DOWN
            in 135.0..225.0 -> KeyCode.DPAD_LEFT
            in 225.0..315.0 -> KeyCode.DPAD_UP
            else            -> KeyCode.DPAD_RIGHT  // 315..360 ∪ 0..45
        }
    }
}
