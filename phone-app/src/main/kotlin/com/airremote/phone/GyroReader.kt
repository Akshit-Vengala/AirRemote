package com.airremote.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

private const val TAG = "GyroReader"

// Sensitivity. Multiplies the small yaw/pitch (in radians) from one sample to
// the next into a pixel delta. Hand-tune end-to-end on the TV (Phase 5); 1500
// is the plan's starting guess for 1080p.
//
// Intuition: at 1500 px/rad, a 1° wrist twist (~0.0175 rad) moves the cursor
// ~26 px. A 90° sweep (~1.57 rad) traverses ~2350 px — comfortably more than
// 1920, so the user never has to fully rotate the phone to reach screen edges.
private const val PIXELS_PER_RADIAN = 1500f

// Minimum gap between callbacks we forward to the consumer. Game rotation
// vector nominally runs at SENSOR_DELAY_GAME (~20ms), but some vendors push
// up to 200Hz — left unchecked, that would flood the ADB stream. 8ms = 125Hz
// ceiling, plenty smooth for cursor movement.
private const val MIN_EMIT_GAP_NS = 8_000_000L  // 8 ms in nanoseconds

/**
 * Reads the phone's fused-orientation sensor and emits pixel deltas suitable
 * for driving a remote cursor.
 *
 * Lifecycle: [start] registers the listener, [stop] unregisters. Holds a
 * reference to the Activity Context — caller must call [stop] in onPause /
 * onDestroy to avoid a leak.
 *
 * Threading: SensorEventListener callbacks arrive on the Sensor's HandlerThread
 * (NOT the main thread). The `onDelta` lambda runs on that thread; if the
 * consumer touches UI it must marshal back to the main thread itself. We hand
 * off to AdbManager.sendMouseMove, which is internally thread-safe (its
 * executor swallows the work), so no marshalling is needed in our case.
 */
class GyroReader(context: Context) {

    // getSystemService returns Any? in Kotlin (was Object in Java) — cast and
    // !! because a phone without a SensorManager doesn't exist. Holding only
    // applicationContext, not the Activity, would also be safe; the sensor
    // service lives in the system process so the Context choice doesn't matter
    // for leak avoidance — what matters is calling stop().
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // getDefaultSensor returns null if the device lacks this sensor (rare on
    // modern phones; absent on emulators without sensor virtualisation). We
    // tolerate null by no-op'ing start(); the cursor just won't move.
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Last quaternion we received, kept to compute the relative rotation on
    // the next sample. null = first sample of the current session; we use it
    // to seed the state and emit a zero delta (no jump on hold-start).
    //
    // FloatArray (not Array<Float>) — primitive array, no boxing per element.
    // Matters here because sensor callbacks run at 50–200Hz.
    private var qPrev: FloatArray? = null

    // Last time we invoked onDelta, for the throttle. SystemClock.elapsedRealtimeNanos
    // would also work; we use System.nanoTime() because it's monotonic and slightly
    // cheaper, and we only ever subtract — absolute values don't matter.
    private var lastEmitNs: Long = 0L

    // The listener instance — kept as a field so stop() can unregister exactly
    // what start() registered (SensorManager keys on object identity).
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // GAME_ROTATION_VECTOR returns 3 or 4 floats:
            //   values[0] = x*sin(θ/2),  values[1] = y*sin(θ/2),  values[2] = z*sin(θ/2)
            //   values[3] = cos(θ/2) — the scalar component w (added in API 18)
            // Older firmwares only fill the first three; w must be reconstructed
            // from the unit-length constraint w² + x² + y² + z² = 1.
            val v = event.values
            val x = v[0]
            val y = v[1]
            val z = v[2]
            val w = if (v.size >= 4) {
                v[3]
            } else {
                // 1 - x²-y²-z² can go slightly negative due to float error; coerceAtLeast(0)
                // guards against NaN from sqrt(negative).
                sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
            }
            val curr = floatArrayOf(w, x, y, z)

            val prev = qPrev
            if (prev == null) {
                // First sample of the session: seed and emit nothing. User's
                // current orientation becomes the implicit origin.
                qPrev = curr
                return
            }

            // ─── Relative quaternion in DEVICE frame: qRel = prev⁻¹ * curr ──
            // Earlier version computed (curr * prev⁻¹) which gives the
            // rotation expressed in the WORLD frame. The components of that
            // quaternion are then "rotation about world X / Y / Z", which is
            // useless to us because the user's "yaw" intent is rotation
            // about WORLD VERTICAL, but world's horizontal axes (X,Y) are
            // arbitrary for TYPE_GAME_ROTATION_VECTOR — they have no fixed
            // relation to the user's facing direction. Worse, ROLL (twist
            // about the phone's long axis) bled into the X/Y components
            // because it's a device-axis rotation projected onto world axes.
            //
            // Left-multiplying by the inverse instead gives qRel in the
            // PREVIOUS device frame, so the components are now "rotation
            // about device X / Y / Z" — fixed-meaning regardless of how the
            // user is facing.
            //
            // Quaternion double-cover (q and -q encode the same rotation but
            // multiply differently): if dot(curr, prev) < 0 they're on
            // opposite hemispheres — negate curr first so the delta is the
            // short way around. Without this, a sample crossing the
            // hemisphere boundary produces a spurious ~360° spike.
            val dot = curr[0]*prev[0] + curr[1]*prev[1] + curr[2]*prev[2] + curr[3]*prev[3]
            val cw: Float; val cx: Float; val cy: Float; val cz: Float
            if (dot < 0f) {
                cw = -curr[0]; cx = -curr[1]; cy = -curr[2]; cz = -curr[3]
            } else {
                cw =  curr[0]; cx =  curr[1]; cy =  curr[2]; cz =  curr[3]
            }
            // prev⁻¹ = conjugate of unit quaternion = (w, -x, -y, -z).
            val pw =  prev[0]; val px = -prev[1]; val py = -prev[2]; val pz = -prev[3]
            // Hamilton product qRel = (pw,px,py,pz) * (cw,cx,cy,cz):
            //   x' = pw·cx + px·cw + py·cz − pz·cy
            //   y' = pw·cy − px·cz + py·cw + pz·cx
            //   z' = pw·cz + px·cy − py·cx + pz·cw
            // (w' omitted — we never read it.)
            val rx = pw*cx + px*cw + py*cz - pz*cy
            val ry = pw*cy - px*cz + py*cw + pz*cx
            val rz = pw*cz + px*cy - py*cx + pz*cw

            // ─── Wand-style hold assumed (top of phone pointing at TV) ───
            //   device X axis ─ horizontal, across the screen ─ pitch axis
            //   device Y axis ─ along the long edge, pointing at TV ─ ROLL axis (discard)
            //   device Z axis ─ out of the screen back ─ vertical → yaw axis
            //
            // Small-angle approximation for a near-identity quaternion:
            // axis-angle radians ≈ 2·(x, y, z) component. Signs chosen below
            // so:
            //   yaw RIGHT (top of phone swings right) → dx POSITIVE (cursor right)
            //   pitch UP (top of phone tilts up)      → dy NEGATIVE (cursor up; screen Y is positive-down)
            // We'll empirically flip if the test says otherwise.
            val yawRad   = 2f * rz
            val pitchRad = 2f * rx
            val dx = (-yawRad   * PIXELS_PER_RADIAN).toInt()
            val dy = (-pitchRad * PIXELS_PER_RADIAN).toInt()
            @Suppress("UNUSED_VARIABLE") val rollDiscarded = ry

            qPrev = curr

            // Throttle: only emit at most every MIN_EMIT_GAP_NS.
            // Note: dropping a sample doesn't lose motion — qPrev still
            // advances, so the NEXT delta integrates over the longer gap.
            val now = System.nanoTime()
            if (now - lastEmitNs < MIN_EMIT_GAP_NS) return
            lastEmitNs = now

            onDeltaRef?.invoke(dx, dy)
        }

        // Required by the interface; we don't care about accuracy changes
        // (the fused sensor's accuracy fluctuates on its own and there's
        // nothing useful for the app to do).
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // The callback lives in a field rather than a parameter on listener so
    // start() can rebind it without re-allocating the SensorEventListener.
    private var onDeltaRef: ((dx: Int, dy: Int) -> Unit)? = null

    fun start(onDelta: (dx: Int, dy: Int) -> Unit) {
        val s = sensor
        if (s == null) {
            Log.w(TAG, "no TYPE_GAME_ROTATION_VECTOR on this device")
            return
        }
        onDeltaRef = onDelta
        qPrev = null           // discard any state from a prior session
        lastEmitNs = 0L
        // SENSOR_DELAY_GAME ≈ 20 ms target. The OS treats it as a hint; vendor
        // implementations may run faster (hence our 125Hz throttle inside onSensorChanged).
        sensorManager.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        onDeltaRef = null
        qPrev = null
    }
}
