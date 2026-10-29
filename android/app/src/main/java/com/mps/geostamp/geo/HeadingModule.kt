package com.mps.geostamp.geo

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tilt-compensated compass.
 *
 * Uses the fused ROTATION_VECTOR sensor (gyro + accel + magnetometer) when available,
 * falling back to accelerometer + magnetometer. The heading is computed from the full
 * rotation matrix so it stays correct in every holding position:
 *  - Phone upright / tilted (taking a photo): heading = direction the BACK CAMERA points.
 *  - Phone lying flat: heading = direction the TOP EDGE of the phone points.
 * Portrait and landscape both work because the camera axis does not depend on roll.
 */
class HeadingModule(private val ctx: ReactApplicationContext) :
  ReactContextBaseJavaModule(ctx), SensorEventListener, LifecycleEventListener {

  override fun getName() = "GeoHeading"

  private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val rotationSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

  private var wanted = false
  private var registered = false

  private val r = FloatArray(9)
  private val gravity = FloatArray(3)
  private val geomag = FloatArray(3)
  private var hasGravity = false
  private var hasGeomag = false

  // Smoothed unit vector of heading (avoids 359->0 wrap problems)
  private var sx = 0.0
  private var sy = 0.0
  private var initialized = false
  private var smoothPitch = 0.0
  private var smoothRoll = 0.0

  private var flatMode = false
  private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
  private var fieldStrength = 0.0
  private var rvAccuracyDeg = -1.0
  private var lastEmit = 0L
  private var lastEmitted = Double.NaN

  init {
    ctx.addLifecycleEventListener(this)
  }

  @ReactMethod
  fun start() {
    wanted = true
    register()
  }

  @ReactMethod
  fun stop() {
    wanted = false
    unregister()
  }

  @ReactMethod
  fun isAvailable(promise: Promise) {
    val map = Arguments.createMap()
    map.putBoolean("rotationVector", rotationSensor != null)
    map.putBoolean("magnetometer", magSensor != null)
    map.putBoolean("available", rotationSensor != null || (accelSensor != null && magSensor != null))
    promise.resolve(map)
  }

  @ReactMethod fun addListener(eventName: String) {}
  @ReactMethod fun removeListeners(count: Double) {}

  private fun register() {
    if (registered) return
    val rate = SensorManager.SENSOR_DELAY_GAME
    if (rotationSensor != null) {
      sm.registerListener(this, rotationSensor, rate)
    } else {
      accelSensor?.let { sm.registerListener(this, it, rate) }
    }
    // Magnetometer always registered: needed for fallback + accuracy & interference detection
    magSensor?.let { sm.registerListener(this, it, rate) }
    registered = true
  }

  private fun unregister() {
    if (!registered) return
    sm.unregisterListener(this)
    registered = false
  }

  override fun onHostResume() { if (wanted) register() }
  override fun onHostPause() { unregister() }
  override fun onHostDestroy() { unregister() }

  override fun invalidate() {
    unregister()
    ctx.removeLifecycleEventListener(this)
    super.invalidate()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
  }

  override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
      Sensor.TYPE_ROTATION_VECTOR -> {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        if (event.values.size > 4 && event.values[4] >= 0f) {
          rvAccuracyDeg = Math.toDegrees(event.values[4].toDouble())
        }
        compute()
      }
      Sensor.TYPE_ACCELEROMETER -> {
        lowPass(event.values, gravity, 0.2f, hasGravity)
        hasGravity = true
        if (rotationSensor == null && hasGeomag && SensorManager.getRotationMatrix(r, null, gravity, geomag)) compute()
      }
      Sensor.TYPE_MAGNETIC_FIELD -> {
        magAccuracy = event.accuracy
        lowPass(event.values, geomag, 0.25f, hasGeomag)
        hasGeomag = true
        fieldStrength = sqrt((geomag[0] * geomag[0] + geomag[1] * geomag[1] + geomag[2] * geomag[2]).toDouble())
      }
    }
  }

  private fun lowPass(input: FloatArray, out: FloatArray, a: Float, init: Boolean) {
    for (i in 0..2) out[i] = if (!init) input[i] else out[i] + a * (input[i] - out[i])
  }

  private fun compute() {
    // Up-component of device Z axis (screen normal). |r[8]| ~ 1 => flat, ~0 => upright
    val zUp = r[8].toDouble()
    flatMode = if (flatMode) abs(zUp) > 0.70 else abs(zUp) > 0.86

    val east: Double
    val north: Double
    if (flatMode) {
      // Direction of the phone's top edge (+Y axis)
      east = r[1].toDouble(); north = r[4].toDouble()
    } else {
      // Direction the back camera looks (-Z axis)
      east = -r[2].toDouble(); north = -r[5].toDouble()
    }
    val len = hypot(east, north)
    if (len < 1e-6) return
    val ux = east / len
    val uy = north / len

    if (!initialized) {
      sx = ux; sy = uy; initialized = true
    } else {
      // Adaptive smoothing: steady when still, fast when turning
      val dot = (sx * ux + sy * uy).coerceIn(-1.0, 1.0)
      val diff = Math.toDegrees(kotlin.math.acos(dot))
      val alpha = when {
        diff > 30 -> 0.55
        diff > 8 -> 0.30
        diff > 2 -> 0.15
        else -> 0.08
      }
      sx += alpha * (ux - sx)
      sy += alpha * (uy - sy)
      val n = hypot(sx, sy)
      if (n > 1e-9) { sx /= n; sy /= n }
    }

    var magnetic = Math.toDegrees(atan2(sx, sy))
    if (magnetic < 0) magnetic += 360.0
    var trueH = magnetic + SensorHub.declination
    trueH = ((trueH % 360.0) + 360.0) % 360.0

    // Camera elevation (+ looking up), and roll (horizon tilt) from gravity in device frame
    val pitch = Math.toDegrees(asin((-zUp).coerceIn(-1.0, 1.0)))
    val roll = Math.toDegrees(atan2(r[6].toDouble(), r[7].toDouble()))
    smoothPitch += 0.25 * (pitch - smoothPitch)
    smoothRoll = smoothAngle(smoothRoll, roll, 0.25)

    val now = SystemClock.elapsedRealtime()
    if (now - lastEmit < 50) return
    if (!lastEmitted.isNaN() && abs(angleDiff(lastEmitted, magnetic)) < 0.2 && now - lastEmit < 300) return
    lastEmit = now
    lastEmitted = magnetic

    // Compare measured field with the field expected at this GPS location: >12% off = local metal/magnet distortion
    val expected = SensorHub.expectedField.toDouble()
    val interference = magSensor != null && fieldStrength > 0 &&
      (if (expected > 0) kotlin.math.abs(fieldStrength - expected) / expected > 0.12 else (fieldStrength < 22.0 || fieldStrength > 70.0))

    val map = Arguments.createMap()
    map.putDouble("magnetic", magnetic)
    map.putDouble("true", trueH)
    map.putDouble("declination", SensorHub.declination.toDouble())
    map.putDouble("pitch", smoothPitch)
    map.putDouble("roll", smoothRoll)
    map.putBoolean("flat", flatMode)
    map.putInt("accuracy", magAccuracy)
    // Some vendors report pi (180°) meaning "unknown"; treat anything >= 60° as not available
    map.putDouble("accuracyDeg", if (rvAccuracyDeg in 0.0..60.0) rvAccuracyDeg else -1.0)
    map.putDouble("expectedField", SensorHub.expectedField.toDouble())
    map.putDouble("field", fieldStrength)
    map.putBoolean("interference", interference)
    map.putBoolean("fused", rotationSensor != null)
    Emitter.emit(ctx, "GeoHeading", map)
  }

  private fun angleDiff(a: Double, b: Double): Double {
    var d = (b - a) % 360.0
    if (d > 180) d -= 360.0
    if (d < -180) d += 360.0
    return d
  }

  private fun smoothAngle(cur: Double, target: Double, a: Double): Double {
    val c = Math.toRadians(cur)
    val t = Math.toRadians(target)
    val x = cos(c) + a * (cos(t) - cos(c))
    val y = sin(c) + a * (sin(t) - sin(c))
    return Math.toDegrees(atan2(y, x))
  }

  companion object {
    fun updateDeclination(lat: Double, lon: Double, alt: Double) {
      try {
        val f = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis())
        SensorHub.declination = f.declination
        SensorHub.expectedField = f.fieldStrength / 1000f // nT -> µT
      } catch (_: Throwable) {
      }
    }
  }
}
