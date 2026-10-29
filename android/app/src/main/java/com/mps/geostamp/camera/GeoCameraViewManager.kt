package com.mps.geostamp.camera

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class GeoCameraViewManager : SimpleViewManager<GeoCameraView>() {
  override fun getName() = "GeoCameraView"

  override fun createViewInstance(context: ThemedReactContext) = GeoCameraView(context)

  @ReactProp(name = "lens")
  fun setLens(view: GeoCameraView, value: String?) = view.setLens(value)

  @ReactProp(name = "flash")
  fun setFlash(view: GeoCameraView, value: String?) = view.setFlash(value)

  @ReactProp(name = "torch", defaultBoolean = false)
  fun setTorch(view: GeoCameraView, value: Boolean) = view.setTorch(value)

  @ReactProp(name = "aspect")
  fun setAspect(view: GeoCameraView, value: String?) = view.setAspect(value)

  @ReactProp(name = "zoom", defaultFloat = 1f)
  fun setZoom(view: GeoCameraView, value: Float) = view.setZoom(value)

  @ReactProp(name = "active", defaultBoolean = true)
  fun setActive(view: GeoCameraView, value: Boolean) = view.setActive(value)

  override fun onDropViewInstance(view: GeoCameraView) {
    view.release()
    super.onDropViewInstance(view)
  }
}
