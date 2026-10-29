package com.mps.geostamp.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.exifinterface.media.ExifInterface
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.mps.geostamp.store.PhotoStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class GeoCameraModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  override fun getName() = "GeoCamera"

  private val worker = Executors.newSingleThreadExecutor()
  private val sound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

  @ReactMethod
  fun takePhoto(opts: ReadableMap, promise: Promise) {
    val view = GeoCameraView.activeView?.get()
    val ic = view?.imageCapture
    if (view == null || ic == null) {
      promise.reject("E_NO_CAMERA", "Camera is not ready yet")
      return
    }
    val playSound = opts.hasKey("shutterSound") && opts.getBoolean("shutterSound")
    val tmp = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
    val meta = ImageCapture.Metadata().apply {
      isReversedHorizontal = view.lensFacing == CameraSelector.LENS_FACING_FRONT &&
        opts.hasKey("mirrorFront") && opts.getBoolean("mirrorFront")
    }
    val output = ImageCapture.OutputFileOptions.Builder(tmp).setMetadata(meta).build()
    try {
      if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
    } catch (_: Throwable) {}

    ic.takePicture(output, worker, object : ImageCapture.OnImageSavedCallback {
      override fun onImageSaved(result: ImageCapture.OutputFileResults) {
        try {
          val record = process(tmp, opts)
          promise.resolve(record)
        } catch (oom: OutOfMemoryError) {
          promise.reject("E_MEMORY", "Not enough memory. Lower photo quality in Settings.")
        } catch (t: Throwable) {
          promise.reject("E_PROCESS", t.message ?: "Failed to save photo", t)
        } finally {
          tmp.delete()
        }
      }

      override fun onError(e: ImageCaptureException) {
        tmp.delete()
        promise.reject("E_CAPTURE", e.message ?: "Capture failed", e)
      }
    })
  }

  private fun process(src: File, opts: ReadableMap): com.facebook.react.bridge.WritableMap {
    val quality = if (opts.hasKey("quality")) opts.getString("quality") ?: "max" else "max"
    val maxSide = when (quality) {
      "medium" -> 2048
      "high" -> 3264
      else -> 0
    }
    val jpegQ = if (opts.hasKey("jpegQuality")) opts.getInt("jpegQuality").coerceIn(60, 100) else 92

    // Decode (with optional downsampling)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.absolutePath, bounds)
    var sample = 1
    if (maxSide > 0) {
      val longest = max(bounds.outWidth, bounds.outHeight)
      while (longest / (sample * 2) >= maxSide) sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sample
      inMutable = true
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bmp = BitmapFactory.decodeFile(src.absolutePath, decodeOpts)
      ?: throw IllegalStateException("Could not decode photo")

    // Apply EXIF orientation so the stamp is drawn upright
    val exifIn = ExifInterface(src.absolutePath)
    val m = orientationMatrix(exifIn.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    if (m != null) {
      val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
      if (rotated !== bmp) bmp.recycle()
      bmp = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
    }
    // Exact downscale to target size
    if (maxSide > 0 && max(bmp.width, bmp.height) > maxSide) {
      val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
      val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
      if (scaled !== bmp) bmp.recycle()
      bmp = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
    }

    StampRenderer.parse(if (opts.hasKey("stamp")) opts.getMap("stamp") else null)?.let { StampRenderer.draw(bmp, it) }

    val now = System.currentTimeMillis()
    val name = "GeoStamp_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
    val dir = PhotoStore.photosDir(ctx)
    val out = File(dir, "$name.jpg")
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, it) }
    val width = bmp.width
    val height = bmp.height

    // Thumbnail
    val thumbScale = 420f / max(width, height)
    val thumb = Bitmap.createScaledBitmap(bmp, max(1, (width * thumbScale).roundToInt()), max(1, (height * thumbScale).roundToInt()), true)
    bmp.recycle()
    val thumbFile = File(dir, "${name}_thumb.jpg")
    FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    thumb.recycle()

    val metaMap = if (opts.hasKey("meta")) opts.getMap("meta") else null
    writeExif(out, metaMap, now)

    val saveToGallery = !opts.hasKey("saveToGallery") || opts.getBoolean("saveToGallery")
    val galleryUri = if (saveToGallery) PhotoStore.copyToGallery(ctx, out, "$name.jpg") else null

    return PhotoStore.writeRecord(ctx, name, out, thumbFile, width, height, now, galleryUri, metaMap)
  }

  private fun orientationMatrix(o: Int): Matrix? {
    val m = Matrix()
    when (o) {
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
      else -> return null
    }
    return m
  }

  private fun writeExif(file: File, meta: ReadableMap?, time: Long) {
    try {
      val exif = ExifInterface(file.absolutePath)
      val df = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
      val ts = df.format(Date(time))
      exif.setAttribute(ExifInterface.TAG_DATETIME, ts)
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ts)
      exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, ts)
      exif.setAttribute(ExifInterface.TAG_SOFTWARE, "GeoStamp Camera")
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
      if (meta != null) {
        fun d(k: String): Double? = if (meta.hasKey(k) && !meta.isNull(k)) meta.getDouble(k) else null
        val lat = d("latitude")
        val lon = d("longitude")
        if (lat != null && lon != null) exif.setLatLong(lat, lon)
        d("altitude")?.let { exif.setAltitude(it) }
        d("heading")?.let {
          exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(abs(it) * 100).roundToInt()}/100")
          val ref = if (meta.hasKey("headingRef") && meta.getString("headingRef") == "magnetic") "M" else "T"
          exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ref)
        }
        val note = listOfNotNull(
          if (meta.hasKey("address") && !meta.isNull("address")) meta.getString("address") else null,
          if (meta.hasKey("note") && !meta.isNull("note")) meta.getString("note") else null,
        ).filter { it.isNotBlank() }.joinToString(" | ")
        if (note.isNotBlank()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, note)
      }
      exif.saveAttributes()
    } catch (_: Throwable) {
    }
  }

  override fun invalidate() {
    try { sound.release() } catch (_: Throwable) {}
    worker.shutdown()
    super.invalidate()
  }
}
