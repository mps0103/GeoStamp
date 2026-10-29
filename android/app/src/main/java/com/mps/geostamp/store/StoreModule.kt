package com.mps.geostamp.store

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class StoreModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  override fun getName() = "GeoStore"

  private val io = Executors.newSingleThreadExecutor()
  private val prefs get() = ctx.getSharedPreferences("geostamp", Context.MODE_PRIVATE)

  // ---------------- Preferences ----------------

  @ReactMethod
  fun getItem(key: String, promise: Promise) = promise.resolve(prefs.getString(key, null))

  @ReactMethod
  fun setItem(key: String, value: String, promise: Promise) {
    prefs.edit().putString(key, value).apply()
    promise.resolve(true)
  }

  @ReactMethod
  fun nextIndex(promise: Promise) {
    val n = prefs.getInt("photoIndex", 0) + 1
    prefs.edit().putInt("photoIndex", n).apply()
    promise.resolve(n)
  }

  @ReactMethod
  fun peekIndex(promise: Promise) = promise.resolve(prefs.getInt("photoIndex", 0) + 1)

  @ReactMethod
  fun resetIndex(promise: Promise) {
    prefs.edit().putInt("photoIndex", 0).apply()
    promise.resolve(true)
  }

  @ReactMethod
  fun getDeviceInfo(promise: Promise) {
    val tz = TimeZone.getDefault()
    val now = Date()
    val m = Arguments.createMap()
    m.putString("tzShort", tz.getDisplayName(tz.inDaylightTime(now), TimeZone.SHORT, Locale.getDefault()))
    m.putString("tzId", tz.id)
    m.putInt("tzOffsetMin", tz.getOffset(now.time) / 60000)
    m.putInt("sdk", Build.VERSION.SDK_INT)
    m.putString("model", Build.MANUFACTURER + " " + Build.MODEL)
    promise.resolve(m)
  }

  @ReactMethod
  fun openAppSettings() {
    try {
      val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(i)
    } catch (_: Throwable) {}
  }

  // ---------------- Photos ----------------

  @ReactMethod
  fun list(promise: Promise) {
    io.execute {
      try {
        val arr = Arguments.createArray()
        PhotoStore.readAll(ctx).forEach { arr.pushMap(PhotoStore.jsonToMap(it)) }
        promise.resolve(arr)
      } catch (t: Throwable) {
        promise.reject("E_LIST", t.message, t)
      }
    }
  }

  @ReactMethod
  fun remove(ids: ReadableArray, alsoGallery: Boolean, promise: Promise) {
    io.execute {
      for (i in 0 until ids.size()) ids.getString(i)?.let { PhotoStore.delete(ctx, it, alsoGallery) }
      promise.resolve(true)
    }
  }

  @ReactMethod
  fun updateMeta(id: String, patch: ReadableMap, promise: Promise) {
    io.execute {
      val o = PhotoStore.updateMeta(ctx, id, patch)
      if (o == null) promise.reject("E_NOT_FOUND", "Photo not found") else promise.resolve(PhotoStore.jsonToMap(o))
    }
  }

  // ---------------- Sharing ----------------

  private fun uriFor(file: File): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

  @ReactMethod
  fun shareFiles(paths: ReadableArray, mime: String, title: String, promise: Promise) {
    try {
      val uris = ArrayList<Uri>()
      for (i in 0 until paths.size()) {
        val p = paths.getString(i) ?: continue
        val f = File(p.removePrefix("file://"))
        if (f.exists()) uris.add(uriFor(f))
      }
      if (uris.isEmpty()) { promise.reject("E_SHARE", "Nothing to share"); return }
      val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
      } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
      }
      intent.type = mime
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      val clip = ClipData.newRawUri("", uris[0])
      for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
      intent.clipData = clip
      val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      ctx.startActivity(chooser)
      promise.resolve(true)
    } catch (t: Throwable) {
      promise.reject("E_SHARE", t.message ?: "Share failed", t)
    }
  }

  @ReactMethod
  fun openFile(path: String, mime: String, promise: Promise) {
    try {
      val f = File(path.removePrefix("file://"))
      val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      promise.resolve(true)
    } catch (t: Throwable) {
      promise.reject("E_OPEN", "No app found to open this file", t)
    }
  }

  // ---------------- Exports ----------------

  private fun records(ids: ReadableArray): List<JSONObject> {
    val all = mutableListOf<JSONObject>()
    for (i in 0 until ids.size()) ids.getString(i)?.let { id -> PhotoStore.read(ctx, id)?.let { all.add(it) } }
    return all.sortedBy { it.optLong("createdAt") }
  }

  private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

  private fun resultMap(file: File, mime: String, saveToDownloads: Boolean) = Arguments.createMap().apply {
    putString("path", file.absolutePath)
    putString("name", file.name)
    val saved = if (saveToDownloads) PhotoStore.copyToDownloads(ctx, file, mime) else null
    if (saved != null) putString("savedTo", saved) else putNull("savedTo")
  }

  @ReactMethod
  fun exportPdf(ids: ReadableArray, options: ReadableMap, promise: Promise) {
    io.execute {
      try {
        val list = records(ids)
        if (list.isEmpty()) { promise.reject("E_EMPTY", "No photos selected"); return@execute }
        val title = opt(options, "title", "Geo-Tagged Photo Report")
        val author = opt(options, "author", "")
        val layout = opt(options, "layout", "single") // single | double
        val save = !options.hasKey("saveToDownloads") || options.getBoolean("saveToDownloads")
        val file = File(PhotoStore.exportsDir(ctx), "GeoStamp_Report_${stamp()}.pdf")
        buildPdf(file, list, title, author, layout == "double")
        promise.resolve(resultMap(file, "application/pdf", save))
      } catch (oom: OutOfMemoryError) {
        promise.reject("E_MEMORY", "Too many photos for one PDF. Try fewer.")
      } catch (t: Throwable) {
        promise.reject("E_PDF", t.message ?: "PDF export failed", t)
      }
    }
  }

  private fun opt(m: ReadableMap, k: String, d: String) = if (m.hasKey(k) && !m.isNull(k)) (m.getString(k) ?: d) else d

  private fun buildPdf(file: File, list: List<JSONObject>, title: String, author: String, double: Boolean) {
    val pageW = 595
    val pageH = 842
    val margin = 36f
    val doc = PdfDocument()
    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#0F172A") }
    val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.parseColor("#64748B") }
    val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#334155") }
    val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.parseColor("#0F172A") }
    val lineP = Paint().apply { color = Color.parseColor("#E2E8F0"); strokeWidth = 0.8f }
    val accentP = Paint().apply { color = Color.parseColor("#0EA5E9") }
    val generated = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

    val perPage = if (double) 2 else 1
    val pages = list.chunked(perPage)
    pages.forEachIndexed { pi, chunk ->
      val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pi + 1).create())
      val c = page.canvas
      c.drawRect(0f, 0f, pageW.toFloat(), 6f, accentP)
      c.drawText(title, margin, margin + 10f, titleP)
      val sub = listOf(if (author.isNotBlank()) "Prepared by $author" else "", "Generated $generated").filter { it.isNotBlank() }.joinToString("  •  ")
      c.drawText(sub, margin, margin + 24f, subP)
      c.drawLine(margin, margin + 32f, pageW - margin, margin + 32f, lineP)

      val areaTop = margin + 44f
      val areaBottom = pageH - margin - 18f
      val slotH = (areaBottom - areaTop) / perPage
      chunk.forEachIndexed { si, rec ->
        val top = areaTop + si * slotH
        drawEntry(c, rec, margin, top, pageW - margin * 2, slotH - 10f, double, labelP, valueP, lineP)
      }
      c.drawText("Page ${pi + 1} of ${pages.size}", pageW - margin - 60f, pageH - margin + 4f, subP)
      c.drawText("GeoStamp Camera", margin, pageH - margin + 4f, subP)
      doc.finishPage(page)
    }
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
  }

  private fun drawEntry(
    c: android.graphics.Canvas, rec: JSONObject, x: Float, y: Float, w: Float, h: Float, compact: Boolean,
    labelP: Paint, valueP: Paint, lineP: Paint,
  ) {
    val meta = rec.optJSONObject("meta") ?: JSONObject()
    val details = mutableListOf<Pair<String, String>>()
    meta.optJSONArray("details")?.let { arr ->
      for (i in 0 until arr.length()) {
        val d = arr.optJSONObject(i) ?: continue
        val v = d.optString("value")
        if (v.isNotBlank()) details.add(d.optString("label") to v)
      }
    }
    val rowH = 13f
    val detailRows = details.sumOf { wrapText(it.second, valueP, w - 110f).size }
    val detailsH = if (compact) min(detailRows * rowH, h * 0.42f) else detailRows * rowH
    val imgMaxH = h - detailsH - 12f

    val bmp = decodeScaled(rec.optString("path"), 1400)
    if (bmp != null) {
      val ratio = bmp.width.toFloat() / bmp.height
      var dw = w
      var dh = dw / ratio
      if (dh > imgMaxH) { dh = imgMaxH; dw = dh * ratio }
      val left = x + (w - dw) / 2f
      c.drawBitmap(bmp, null, RectF(left, y, left + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
      bmp.recycle()
      var ty = y + dh + 14f
      for ((label, value) in details) {
        val lines = wrapText(value, valueP, w - 110f)
        for ((li, line) in lines.withIndex()) {
          if (ty > y + h) break
          if (li == 0) c.drawText(label, x, ty, labelP)
          c.drawText(line, x + 110f, ty, valueP)
          ty += rowH
        }
      }
      c.drawLine(x, y + h + 4f, x + w, y + h + 4f, lineP)
    }
  }

  private fun wrapText(text: String, p: Paint, maxW: Float): List<String> {
    val out = mutableListOf<String>()
    for (para in text.split("\n")) {
      var cur = ""
      for (word in para.split(" ")) {
        val t = if (cur.isEmpty()) word else "$cur $word"
        if (p.measureText(t) <= maxW || cur.isEmpty()) cur = t else { out.add(cur); cur = word }
      }
      out.add(cur)
    }
    return out
  }

  private fun decodeScaled(path: String, maxSide: Int): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b)
    var s = 1
    while (max(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
  }

  @ReactMethod
  fun exportCsv(ids: ReadableArray, saveToDownloads: Boolean, promise: Promise) {
    io.execute {
      try {
        val list = records(ids)
        val sb = StringBuilder("Index,File,Date Time,Latitude,Longitude,Altitude (m),Accuracy (m),Heading (deg),North Ref,Address,Tag,Note\n")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (r in list) {
          val m = r.optJSONObject("meta") ?: JSONObject()
          sb.append(listOf(
            numOrBlank(m, "index", 0), File(r.optString("path")).name, m.optString("dateText", ""),
            numOrBlank(m, "latitude", 7), numOrBlank(m, "longitude", 7), numOrBlank(m, "altitude", 1),
            numOrBlank(m, "accuracy", 1), numOrBlank(m, "heading", 1), m.optString("headingRef", ""),
            m.optString("address", ""), m.optString("tag", ""), m.optString("note", ""),
          ).joinToString(",") { q(it) }).append("\n")
        }
        val file = File(PhotoStore.exportsDir(ctx), "GeoStamp_Points_${stamp()}.csv")
        file.writeText(sb.toString())
        promise.resolve(resultMap(file, "text/csv", saveToDownloads))
      } catch (t: Throwable) {
        promise.reject("E_CSV", t.message, t)
      }
    }
  }

  @ReactMethod
  fun exportKml(ids: ReadableArray, saveToDownloads: Boolean, promise: Promise) {
    io.execute {
      try {
        val list = records(ids)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>GeoStamp Points</name>\n")
        for (r in list) {
          val m = r.optJSONObject("meta") ?: JSONObject()
          if (!m.has("latitude") || m.isNull("latitude")) continue
          val name = m.optString("tag", "").ifBlank { "Photo " + numOrBlank(m, "index", 0) }
          val desc = listOf(m.optString("dateText", ""), m.optString("address", ""), m.optString("note", ""),
            if (m.has("heading")) "Heading " + numOrBlank(m, "heading", 0) + "°" else "").filter { it.isNotBlank() }.joinToString(" | ")
          sb.append("<Placemark><name>").append(esc(name)).append("</name><description>").append(esc(desc))
            .append("</description><Point><coordinates>").append(m.optDouble("longitude")).append(",")
            .append(m.optDouble("latitude")).append(",").append(m.optDouble("altitude", 0.0))
            .append("</coordinates></Point></Placemark>\n")
        }
        sb.append("</Document></kml>")
        val file = File(PhotoStore.exportsDir(ctx), "GeoStamp_Points_${stamp()}.kml")
        file.writeText(sb.toString())
        promise.resolve(resultMap(file, "application/vnd.google-earth.kml+xml", saveToDownloads))
      } catch (t: Throwable) {
        promise.reject("E_KML", t.message, t)
      }
    }
  }

  private fun numOrBlank(m: JSONObject, k: String, decimals: Int): String {
    if (!m.has(k) || m.isNull(k)) return ""
    val d = m.optDouble(k)
    if (d.isNaN()) return ""
    return String.format(Locale.US, "%.${decimals}f", d)
  }

  override fun invalidate() {
    io.shutdown()
    super.invalidate()
  }
}
