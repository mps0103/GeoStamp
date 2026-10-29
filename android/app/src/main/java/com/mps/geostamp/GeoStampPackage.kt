package com.mps.geostamp

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.mps.geostamp.camera.GeoCameraModule
import com.mps.geostamp.camera.GeoCameraViewManager
import com.mps.geostamp.geo.HeadingModule
import com.mps.geostamp.geo.LocationModule
import com.mps.geostamp.store.StoreModule

class GeoStampPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(
    HeadingModule(reactContext),
    LocationModule(reactContext),
    GeoCameraModule(reactContext),
    StoreModule(reactContext),
  )

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<in Nothing, in Nothing>> =
    listOf(GeoCameraViewManager())
}
