package com.mps.geostamp.camera

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.mps.geostamp.geo.Emitter
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

@SuppressLint("ViewConstructor")
class GeoCameraView(private val reactContext: ThemedReactContext) : FrameLayout(reactContext) {

  private val previewView = PreviewView(reactContext).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
  }
  private val focusRing = FocusRingView(reactContext)

  var imageCapture: ImageCapture? = null
    private set
  private var camera: Camera? = null
  private var provider: ProcessCameraProvider? = null
  private var bound = false
  private var ready = false

  var lensFacing = CameraSelector.LENS_FACING_BACK
    private set
  private var flashMode = ImageCapture.FLASH_MODE_OFF
  private var aspect = AspectRatio.RATIO_4_3
  private var torch = false
  private var zoomRatio = 1f
  private var active = true
  var currentRotation = Surface.ROTATION_0
    private set

  private val orientationListener = object : OrientationEventListener(reactContext) {
    override fun onOrientationChanged(o: Int) {
      if (o == ORIENTATION_UNKNOWN) return
      val rot = when (o) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
      }
      if (rot != currentRotation) {
        currentRotation = rot
        imageCapture?.targetRotation = rot
        val m = Arguments.createMap()
        m.putInt("rotation", when (rot) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 })
        Emitter.emit(reactContext.reactApplicationContext, "GeoOrientation", m)
      }
    }
  }

  private val scaleDetector = ScaleGestureDetector(reactContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(d: ScaleGestureDetector): Boolean {
      val cam = camera ?: return false
      val state = cam.cameraInfo.zoomState.value ?: return false
      val next = (state.zoomRatio * d.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
      cam.cameraControl.setZoomRatio(next)
      zoomRatio = next
      emitZoom(next, state.maxZoomRatio)
      return true
    }
  })

  private val tapDetector = GestureDetector(reactContext, object : GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: MotionEvent): Boolean {
      focusAt(e.x, e.y)
      return true
    }
  })

  init {
    setBackgroundColor(Color.BLACK)
    addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(focusRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    @Suppress("ClickableViewAccessibility")
    previewView.setOnTouchListener { _, ev ->
      scaleDetector.onTouchEvent(ev)
      if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(ev)
      true
    }
    ready = true
  }

  // React Native does not re-layout native children; do it manually so PreviewView fills.
  private val measureAndLayout = Runnable {
    measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    layout(left, top, right, bottom)
  }

  override fun requestLayout() {
    super.requestLayout()
    // requestLayout is called from the FrameLayout constructor before fields are initialised
    if (ready) post(measureAndLayout)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    activeView = WeakReference(this)
    if (orientationListener.canDetectOrientation()) orientationListener.enable()
    bind()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    orientationListener.disable()
    release()
  }

  fun release() {
    try { provider?.unbindAll() } catch (_: Throwable) {}
    bound = false
    camera = null
    imageCapture = null
    if (activeView?.get() === this) activeView = null
  }

  fun setLens(facing: String?) {
    val f = if (facing == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    if (f != lensFacing) { lensFacing = f; rebind() }
  }

  fun setFlash(mode: String?) {
    flashMode = when (mode) {
      "on" -> ImageCapture.FLASH_MODE_ON
      "auto" -> ImageCapture.FLASH_MODE_AUTO
      else -> ImageCapture.FLASH_MODE_OFF
    }
    imageCapture?.flashMode = flashMode
  }

  fun setTorch(on: Boolean) {
    torch = on
    camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(on) }
  }

  fun setAspect(a: String?) {
    val r = if (a == "16:9") AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
    if (r != aspect) { aspect = r; rebind() }
  }

  fun setZoom(z: Float) {
    zoomRatio = z
    val cam = camera ?: return
    val state = cam.cameraInfo.zoomState.value
    val clamped = if (state != null) z.coerceIn(state.minZoomRatio, state.maxZoomRatio) else z
    cam.cameraControl.setZoomRatio(clamped)
  }

  fun setActive(a: Boolean) {
    if (a == active) return
    active = a
    if (a) bind() else release()
  }

  private fun rebind() {
    if (!isAttachedToWindow || !active) return
    bound = false
    bind()
  }

  private fun bind() {
    if (bound || !active) return
    val owner = reactContext.currentActivity as? LifecycleOwner ?: return
    val future = ProcessCameraProvider.getInstance(reactContext)
    future.addListener({
      try {
        val p = future.get()
        provider = p
        var facing = lensFacing
        var selector = CameraSelector.Builder().requireLensFacing(facing).build()
        if (!p.hasCamera(selector)) {
          facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
          selector = CameraSelector.Builder().requireLensFacing(facing).build()
          lensFacing = facing
        }
        val aspectStrategy = if (aspect == AspectRatio.RATIO_16_9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        val previewSel = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build()
        val captureSel = ResolutionSelector.Builder()
          .setAspectRatioStrategy(aspectStrategy)
          .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
          .build()

        val preview = Preview.Builder().setResolutionSelector(previewSel).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val ic = ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
          .setFlashMode(flashMode)
          .setResolutionSelector(captureSel)
          .setTargetRotation(currentRotation)
          .build()

        p.unbindAll()
        val cam = p.bindToLifecycle(owner, selector, preview, ic)
        camera = cam
        imageCapture = ic
        bound = true
        if (torch && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
        val state = cam.cameraInfo.zoomState.value
        if (state != null) {
          val z = zoomRatio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
          cam.cameraControl.setZoomRatio(z)
        }
        val m = Arguments.createMap()
        m.putBoolean("hasFlash", cam.cameraInfo.hasFlashUnit())
        m.putString("lens", if (facing == CameraSelector.LENS_FACING_FRONT) "front" else "back")
        m.putDouble("maxZoom", (state?.maxZoomRatio ?: 1f).toDouble())
        m.putDouble("minZoom", (state?.minZoomRatio ?: 1f).toDouble())
        Emitter.emit(reactContext.reactApplicationContext, "GeoCameraReady", m)
      } catch (t: Throwable) {
        val m = Arguments.createMap()
        m.putString("message", t.message ?: "Camera failed to start")
        Emitter.emit(reactContext.reactApplicationContext, "GeoCameraError", m)
      }
    }, ContextCompat.getMainExecutor(reactContext))
  }

  private fun focusAt(x: Float, y: Float) {
    val cam = camera ?: return
    val point = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .setAutoCancelDuration(4, TimeUnit.SECONDS)
      .build()
    try { cam.cameraControl.startFocusAndMetering(action) } catch (_: Throwable) {}
    focusRing.show(x, y)
  }

  private fun emitZoom(z: Float, max: Float) {
    val m = Arguments.createMap()
    m.putDouble("zoom", z.toDouble())
    m.putDouble("maxZoom", max.toDouble())
    Emitter.emit(reactContext.reactApplicationContext, "GeoZoom", m)
  }

  /** Small animated focus indicator. */
  private class FocusRingView(ctx: android.content.Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3f * ctx.resources.displayMetrics.density
      color = Color.parseColor("#FFD54F")
    }
    private var cx = 0f
    private var cy = 0f
    private var visibleRing = false
    private val hide = Runnable { visibleRing = false; invalidate() }

    init { isClickable = false; isFocusable = false }

    fun show(x: Float, y: Float) {
      cx = x; cy = y; visibleRing = true
      alpha = 1f
      invalidate()
      removeCallbacks(hide)
      postDelayed(hide, 1200)
    }

    override fun onDraw(canvas: Canvas) {
      if (!visibleRing) return
      val r = 36f * resources.displayMetrics.density
      canvas.drawCircle(cx, cy, r, paint)
    }
  }

  companion object {
    @Volatile var activeView: WeakReference<GeoCameraView>? = null
  }
}
