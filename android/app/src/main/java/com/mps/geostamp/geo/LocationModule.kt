package com.mps.geostamp.geo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Location from GPS + network + fused providers (no Google Play Services needed),
 * plus offline-cached reverse geocoding.
 */
class LocationModule(private val ctx: ReactApplicationContext) :
  ReactContextBaseJavaModule(ctx), LocationListener, LifecycleEventListener {

  override fun getName() = "GeoLocation"

  private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private val geoExec = Executors.newSingleThreadExecutor()
  private var best: Location? = null
  private var wanted = false
  private var registered = false
  private var lastGeocoded: Location? = null
  private var geocoding = false
  private var address: Bundle? = null

  init {
    ctx.addLifecycleEventListener(this)
  }

  @ReactMethod fun addListener(eventName: String) {}
  @ReactMethod fun removeListeners(count: Double) {}

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
  fun isEnabled(promise: Promise) {
    val gps = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { false }
    val net = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { false }
    val map = Arguments.createMap()
    map.putBoolean("gps", gps)
    map.putBoolean("network", net)
    map.putBoolean("enabled", gps || net)
    promise.resolve(map)
  }

  @ReactMethod
  fun openLocationSettings() {
    try {
      val i = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    } catch (_: Throwable) {
    }
  }

  @ReactMethod
  fun refreshAddress() {
    best?.let { geocode(it, true) }
  }

  @SuppressLint("MissingPermission")
  private fun register() {
    if (registered) return
    try {
      val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers.add(LocationManager.FUSED_PROVIDER)
      for (p in providers) {
        if (!lm.allProviders.contains(p)) continue
        try {
          lm.getLastKnownLocation(p)?.let { consider(it) }
          lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
      }
      registered = true
    } catch (_: Throwable) {
    }
  }

  private fun unregister() {
    if (!registered) return
    try { lm.removeUpdates(this) } catch (_: Throwable) {}
    registered = false
  }

  override fun onHostResume() { if (wanted) register() }
  override fun onHostPause() { unregister() }
  override fun onHostDestroy() { unregister() }

  override fun invalidate() {
    unregister()
    ctx.removeLifecycleEventListener(this)
    geoExec.shutdown()
    super.invalidate()
  }

  override fun onLocationChanged(location: Location) { consider(location) }
  @Deprecated("Deprecated in Java")
  override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
  override fun onProviderEnabled(provider: String) {}
  override fun onProviderDisabled(provider: String) {}

  private fun isBetter(loc: Location, cur: Location?): Boolean {
    if (cur == null) return true
    val dt = loc.time - cur.time
    if (dt > 15000) return true
    if (dt < -15000) return false
    val da = loc.accuracy - cur.accuracy
    if (da < 0) return true
    if (dt > 0 && da <= 10) return true
    if (dt > 3000 && da <= 50 && loc.provider == cur.provider) return true
    return false
  }

  private fun consider(loc: Location) {
    if (!isBetter(loc, best)) return
    best = loc
    SensorHub.hasLocation = true
    SensorHub.latitude = loc.latitude
    SensorHub.longitude = loc.longitude
    SensorHub.altitude = loc.altitude
    HeadingModule.updateDeclination(loc.latitude, loc.longitude, loc.altitude)
    emitLocation()
    geocode(loc, false)
  }

  private fun emitLocation() {
    val loc = best ?: return
    val map = Arguments.createMap()
    map.putDouble("latitude", loc.latitude)
    map.putDouble("longitude", loc.longitude)
    map.putDouble("accuracy", loc.accuracy.toDouble())
    map.putBoolean("hasAltitude", loc.hasAltitude())
    map.putDouble("altitude", loc.altitude)
    map.putDouble("speed", if (loc.hasSpeed()) loc.speed.toDouble() else -1.0)
    map.putDouble("time", loc.time.toDouble())
    map.putString("provider", loc.provider ?: "")
    address?.let { a ->
      val am = Arguments.createMap()
      for (k in a.keySet()) am.putString(k, a.getString(k) ?: "")
      map.putMap("address", am)
    }
    Emitter.emit(ctx, "GeoLocation", map)
  }

  private fun geocode(loc: Location, force: Boolean) {
    if (!Geocoder.isPresent() || geocoding) return
    val prev = lastGeocoded
    if (!force && prev != null && prev.distanceTo(loc) < 25f) return
    geocoding = true
    lastGeocoded = loc
    geoExec.execute {
      try {
        val geocoder = Geocoder(ctx, Locale.getDefault())
        @Suppress("DEPRECATION")
        val list: List<Address>? = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
        val a = list?.firstOrNull()
        if (a != null) {
          val b = Bundle()
          val lines = mutableListOf<String>()
          for (i in 0..a.maxAddressLineIndex) a.getAddressLine(i)?.let { lines.add(it) }
          b.putString("full", lines.joinToString(", "))
          b.putString("featureName", a.featureName ?: "")
          b.putString("thoroughfare", a.thoroughfare ?: "")
          b.putString("subLocality", a.subLocality ?: "")
          b.putString("locality", a.locality ?: "")
          b.putString("subAdminArea", a.subAdminArea ?: "")
          b.putString("adminArea", a.adminArea ?: "")
          b.putString("postalCode", a.postalCode ?: "")
          b.putString("countryName", a.countryName ?: "")
          b.putString("countryCode", a.countryCode ?: "")
          address = b
          ctx.runOnUiQueueThread { emitLocation() }
        }
      } catch (_: Throwable) {
        lastGeocoded = null // retry next fix (e.g. offline)
      } finally {
        geocoding = false
      }
    }
  }
}
