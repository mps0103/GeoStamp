package com.mps.geostamp.geo

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap

/** Shared helpers for emitting events to JS safely. */
object Emitter {
  fun emit(ctx: ReactApplicationContext, name: String, data: WritableMap) {
    try {
      if (!ctx.hasActiveReactInstance()) return
      ctx.emitDeviceEvent(name, data)
    } catch (_: Throwable) {
    }
  }
}

/** Latest sensor values shared between native modules. */
object SensorHub {
  @Volatile var declination: Float = 0f
  /** Earth field strength expected at this location (µT), from the World Magnetic Model. */
  @Volatile var expectedField: Float = 0f
  @Volatile var hasLocation: Boolean = false
  @Volatile var latitude: Double = 0.0
  @Volatile var longitude: Double = 0.0
  @Volatile var altitude: Double = 0.0
}
