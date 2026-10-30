package com.mps.geostamp.store

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

class StoreModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  override fun getName() = "GeoStore"

  private companion object {
    const val MAX_GRID_PHOTOS = 20
    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  }

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

  // ---------------- Page layout (shared by PDF and Word) ----------------

  private class PageOpts(
    val border: String, val borderColor: String, val gap: String, val caption: String,
    val pageSize: String, val landscape: Boolean, val header: Boolean, val rounded: Boolean,
    val details: Boolean, // details table under each photo (single / double layouts)
  )

  private fun pageOpts(options: ReadableMap): PageOpts {
    val m = if (options.hasKey("page") && !options.isNull("page")) options.getMap("page") else null
    fun str(k: String, d: String) = if (m != null && m.hasKey(k) && !m.isNull(k)) (m.getString(k) ?: d) else d
    fun bool(k: String, d: Boolean) = if (m != null && m.hasKey(k) && !m.isNull(k)) m.getBoolean(k) else d
    return PageOpts(
      str("border", "thin"), str("borderColor", "#0F172A"), str("gap", "small"), str("caption", "none"),
      str("pageSize", "a4"), str("orientation", "portrait") == "landscape", bool("header", true), bool("rounded", false),
      bool("details", true),
    )
  }

  private fun gridCols(n: Int, landscape: Boolean): Int =
    if (landscape) when { n <= 1 -> 1; n <= 4 -> 2; n <= 9 -> 3; n <= 16 -> 4; else -> 5 }
    else when { n <= 2 -> 1; n <= 6 -> 2; n <= 12 -> 3; else -> 4 }

  private fun gapPoints(gap: String) = when (gap) { "none" -> 0f; "large" -> 16f; else -> 8f }

  /** Page size in points (A4 595x842, Letter 612x792; sides swapped for landscape). */
  private fun pagePoints(po: PageOpts): Pair<Int, Int> {
    val (w, h) = if (po.pageSize == "letter") 612 to 792 else 595 to 842
    return if (po.landscape) h to w else w to h
  }

  private fun parseColorOr(s: String, d: String): Int = try { Color.parseColor(s) } catch (_: Throwable) { Color.parseColor(d) }

  private fun hex6(s: String): String {
    val h = s.removePrefix("#").uppercase(Locale.US)
    return if (Regex("[0-9A-F]{6}").matches(h)) h else "0F172A"
  }

  private val dirs16 = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

  /** Same rules as JS headingText(deg, 0): rounded degrees + 16-point cardinal. */
  private fun headingLabel(deg: Double): String {
    val d = (Math.round(((deg % 360) + 360) % 360).toInt()) % 360
    return "$d° ${dirs16[Math.round(d / 22.5).toInt() % 16]}"
  }

  private fun captionText(rec: JSONObject, caption: String): String? {
    if (caption == "none") return null
    val m = rec.optJSONObject("meta") ?: return null
    val idx = if (m.has("index") && !m.isNull("index")) "#" + String.format(Locale.US, "%04d", m.optInt("index")) else null
    return when (caption) {
      "index" -> idx
      "date" -> m.optString("dateText", "").ifBlank { null }
      "indexHeading" -> {
        val hd = if (m.has("heading") && !m.isNull("heading")) headingLabel(m.optDouble("heading")) else null
        listOfNotNull(idx, hd).joinToString(" · ").ifBlank { null }
      }
      else -> null
    }
  }

  @ReactMethod
  fun exportPdf(ids: ReadableArray, options: ReadableMap, promise: Promise) {
    io.execute {
      try {
        val list = records(ids)
        if (list.isEmpty()) { promise.reject("E_EMPTY", "No photos selected"); return@execute }
        val title = opt(options, "title", "Geo-Tagged Photo Report")
        val author = opt(options, "author", "")
        val layout = opt(options, "layout", "single") // single | double | grid
        val po = pageOpts(options)
        if (layout == "grid" && list.size > MAX_GRID_PHOTOS) {
          promise.reject("E_TOO_MANY", "Max $MAX_GRID_PHOTOS photos on one page. Use Multiple pages.")
          return@execute
        }
        val file = File(PhotoStore.exportsDir(ctx), "GeoStamp_Report_${stamp()}.pdf")
        if (layout == "grid") buildGridPdf(file, list, title, author, po) else buildPdf(file, list, title, author, layout == "double", po)
        // Exports are always copied to Downloads/GeoStamp (saveToDownloads option kept for compatibility)
        promise.resolve(resultMap(file, "application/pdf", true))
      } catch (oom: OutOfMemoryError) {
        promise.reject("E_MEMORY", "Too many photos for one PDF. Try fewer.")
      } catch (t: Throwable) {
        promise.reject("E_PDF", t.message ?: "PDF export failed", t)
      }
    }
  }

  private fun opt(m: ReadableMap, k: String, d: String) = if (m.hasKey(k) && !m.isNull(k)) (m.getString(k) ?: d) else d

  private fun buildPdf(file: File, list: List<JSONObject>, title: String, author: String, double: Boolean, po: PageOpts) {
    val (pageW, pageH) = pagePoints(po)
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
      if (po.header) {
        c.drawRect(0f, 0f, pageW.toFloat(), 6f, accentP)
        c.drawText(title, margin, margin + 10f, titleP)
        val sub = listOf(if (author.isNotBlank()) "Prepared by $author" else "", "Generated $generated").filter { it.isNotBlank() }.joinToString("  •  ")
        c.drawText(sub, margin, margin + 24f, subP)
        c.drawLine(margin, margin + 32f, pageW - margin, margin + 32f, lineP)
      }

      val areaTop = margin + (if (po.header) 44f else 0f)
      val areaBottom = pageH - margin - 18f
      val areaW = pageW - margin * 2
      if (double && po.landscape) {
        // Landscape: the two photos sit side by side
        val gapX = 16f
        val slotW = (areaW - gapX) / 2
        chunk.forEachIndexed { si, rec ->
          drawEntry(c, rec, margin + si * (slotW + gapX), areaTop, slotW, areaBottom - areaTop - 10f, true, labelP, valueP, lineP, po.details)
        }
      } else {
        val slotH = (areaBottom - areaTop) / perPage
        chunk.forEachIndexed { si, rec ->
          val top = areaTop + si * slotH
          drawEntry(c, rec, margin, top, areaW, slotH - 10f, double, labelP, valueP, lineP, po.details)
        }
      }
      c.drawText("Page ${pi + 1} of ${pages.size}", pageW - margin - 60f, pageH - margin + 4f, subP)
      c.drawText("GeoStamp Camera", margin, pageH - margin + 4f, subP)
      doc.finishPage(page)
    }
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
  }

  /** Space (pt) a border style takes from the cell edge inward. */
  private fun borderInset(border: String) = when (border) {
    "none" -> 0f
    "thick" -> 3f
    "double" -> 4.1f
    "dotted" -> 1.5f
    else -> 1f // thin, dashed
  }

  private fun drawCellBorder(c: Canvas, x: Float, y: Float, w: Float, h: Float, po: PageOpts, lineColor: Int) {
    if (po.border == "none") return
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = lineColor }
    val r = 8f
    fun box(inset: Float, radius: Float) {
      val rf = RectF(x + inset, y + inset, x + w - inset, y + h - inset)
      if (po.rounded) c.drawRoundRect(rf, radius, radius, p) else c.drawRect(rf, p)
    }
    when (po.border) {
      "thick" -> { p.strokeWidth = 3f; box(1.5f, r) }
      "double" -> { p.strokeWidth = 0.8f; box(0.4f, r); box(3.7f, max(r - 3.3f, 1f)) } // two 0.8 pt lines, 2.5 pt apart
      "dashed" -> { p.strokeWidth = 1f; p.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f); box(0.5f, r) }
      "dotted" -> {
        p.strokeWidth = 1.5f
        p.strokeCap = Paint.Cap.ROUND
        p.pathEffect = DashPathEffect(floatArrayOf(1.5f, 3f), 0f)
        box(0.75f, r)
      }
      else -> { p.strokeWidth = 1f; box(0.5f, r) }
    }
  }

  /** ONE page: optional header + a bordered grid of all selected photos (no details table). */
  private fun buildGridPdf(file: File, list: List<JSONObject>, title: String, author: String, po: PageOpts) {
    val (pageW, pageH) = pagePoints(po)
    val margin = 36f
    val gap = gapPoints(po.gap)
    val pad = 4f
    val inset = borderInset(po.border)
    val lineColor = parseColorOr(po.borderColor, "#0F172A")
    val doc = PdfDocument()
    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#0F172A") }
    val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.parseColor("#64748B") }
    val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.parseColor("#334155"); textAlign = Paint.Align.CENTER }
    val lineP = Paint().apply { color = Color.parseColor("#E2E8F0"); strokeWidth = 0.8f }
    val accentP = Paint().apply { color = Color.parseColor("#0EA5E9") }
    val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    val generated = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
    val c = page.canvas
    if (po.header) {
      c.drawRect(0f, 0f, pageW.toFloat(), 6f, accentP)
      c.drawText(title, margin, margin + 10f, titleP)
      val sub = listOf(if (author.isNotBlank()) "Prepared by $author" else "", "Generated $generated").filter { it.isNotBlank() }.joinToString("  •  ")
      c.drawText(sub, margin, margin + 24f, subP)
      c.drawLine(margin, margin + 32f, pageW - margin, margin + 32f, lineP)
    }

    val n = list.size
    val cols = gridCols(n, po.landscape)
    val rows = (n + cols - 1) / cols
    val areaTop = margin + (if (po.header) 44f else 0f)
    val areaW = pageW - margin * 2
    val areaH = (pageH - margin - 18f) - areaTop
    val cellW = (areaW - gap * (cols - 1)) / cols
    val cellH = (areaH - gap * (rows - 1)) / rows
    val capH = if (po.caption != "none") 12f else 0f
    val edge = inset + pad

    list.forEachIndexed { i, rec ->
      val x = margin + (i % cols) * (cellW + gap)
      val y = areaTop + (i / cols) * (cellH + gap)
      drawCellBorder(c, x, y, cellW, cellH, po, lineColor)

      val iw = cellW - 2 * edge
      val ih = cellH - 2 * edge - capH
      val bmp = if (iw > 1f && ih > 1f) decodeScaled(rec.optString("path"), 900) else null
      if (bmp != null) {
        val scale = min(iw / bmp.width, ih / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = x + edge + (iw - dw) / 2f
        val top = y + edge + (ih - dh) / 2f
        c.save()
        if (po.rounded) c.clipPath(Path().apply { addRoundRect(RectF(x, y, x + cellW, y + cellH), 8f, 8f, Path.Direction.CW) })
        c.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bmpP)
        c.restore()
        bmp.recycle()
      }

      captionText(rec, po.caption)?.let { cap ->
        var t = cap
        if (capP.measureText(t) > iw) {
          while (t.length > 1 && capP.measureText("$t…") > iw) t = t.dropLast(1)
          t += "…"
        }
        c.drawText(t, x + cellW / 2f, y + cellH - edge - 2f, capP)
      }
    }
    c.drawText("Page 1 of 1", pageW - margin - 60f, pageH - margin + 4f, subP)
    c.drawText("GeoStamp Camera", margin, pageH - margin + 4f, subP)
    doc.finishPage(page)
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
  }

  private fun drawEntry(
    c: android.graphics.Canvas, rec: JSONObject, x: Float, y: Float, w: Float, h: Float, compact: Boolean,
    labelP: Paint, valueP: Paint, lineP: Paint, showDetails: Boolean = true,
  ) {
    val meta = rec.optJSONObject("meta") ?: JSONObject()
    val details = mutableListOf<Pair<String, String>>()
    // Details off: the photo (with its stamp) gets the whole slot
    if (showDetails) meta.optJSONArray("details")?.let { arr ->
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
        // Always saved to Downloads/GeoStamp (saveToDownloads parameter kept for compatibility)
        promise.resolve(resultMap(file, "text/csv", true))
      } catch (t: Throwable) {
        promise.reject("E_CSV", t.message, t)
      }
    }
  }

  @ReactMethod
  fun exportDocx(ids: ReadableArray, options: ReadableMap, promise: Promise) {
    io.execute {
      try {
        val list = records(ids)
        if (list.isEmpty()) { promise.reject("E_EMPTY", "No photos selected"); return@execute }
        val title = opt(options, "title", "Geo-Tagged Photo Report")
        val author = opt(options, "author", "")
        val layout = opt(options, "layout", "single") // single | double | grid
        val po = pageOpts(options)
        if (layout == "grid" && list.size > MAX_GRID_PHOTOS) {
          promise.reject("E_TOO_MANY", "Max $MAX_GRID_PHOTOS photos on one page. Use Multiple pages.")
          return@execute
        }
        val file = File(PhotoStore.exportsDir(ctx), "GeoStamp_Report_${stamp()}.docx")
        buildDocx(file, list, title, author, layout, po)
        promise.resolve(resultMap(file, DOCX_MIME, true))
      } catch (oom: OutOfMemoryError) {
        promise.reject("E_MEMORY", "Too many photos for one Word file. Try fewer.")
      } catch (t: Throwable) {
        promise.reject("E_DOCX", t.message ?: "Word export failed", t)
      }
    }
  }

  private fun xmlEsc(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
      when {
        ch == '&' -> sb.append("&amp;")
        ch == '<' -> sb.append("&lt;")
        ch == '>' -> sb.append("&gt;")
        ch == '"' -> sb.append("&quot;")
        ch == '\'' -> sb.append("&apos;")
        ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {} // illegal in XML 1.0
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  /** A w:r run; newlines in [text] become line breaks. [halfPts] is Word's half-point font size. */
  private fun docxRun(text: String, halfPts: Int, bold: Boolean = false, color: String? = null): String {
    val sb = StringBuilder("<w:r><w:rPr>")
    if (bold) sb.append("<w:b/>")
    if (color != null) sb.append("<w:color w:val=\"").append(color).append("\"/>")
    sb.append("<w:sz w:val=\"").append(halfPts).append("\"/></w:rPr>")
    text.replace("\r", "").split("\n").forEachIndexed { i, part ->
      if (i > 0) sb.append("<w:br/>")
      sb.append("<w:t xml:space=\"preserve\">").append(xmlEsc(part)).append("</w:t>")
    }
    return sb.append("</w:r>").toString()
  }

  private fun docxPicture(rid: String, n: Int, cx: Long, cy: Long, spacingAfter: Int = 120): String =
    "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"$spacingAfter\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
      "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
      "<wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
      "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
      "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
      "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
      "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
      "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
      "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
      "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

  private fun detailRows(rec: JSONObject): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rec.optJSONObject("meta")?.optJSONArray("details")?.let { arr ->
      for (i in 0 until arr.length()) {
        val d = arr.optJSONObject(i) ?: continue
        val v = d.optString("value")
        if (v.isNotBlank()) rows.add(d.optString("label") to v)
      }
    }
    return rows
  }

  private fun docxDetailsTable(rec: JSONObject, usableW: Int, labelW: Int = 2600): String {
    val rows = detailRows(rec)
    if (rows.isEmpty()) return ""
    val valueW = usableW - labelW
    val b = { side: String -> "<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CBD5E1\"/>" }
    val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$usableW\" w:type=\"dxa\"/><w:tblBorders>")
    sb.append(b("top")).append(b("left")).append(b("bottom")).append(b("right")).append(b("insideH")).append(b("insideV"))
    sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr>")
    sb.append("<w:tblGrid><w:gridCol w:w=\"$labelW\"/><w:gridCol w:w=\"$valueW\"/></w:tblGrid>")
    for ((label, value) in rows) {
      sb.append("<w:tr>")
      sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$labelW\" w:type=\"dxa\"/></w:tcPr><w:p>").append(docxRun(label, 18, bold = true)).append("</w:p></w:tc>")
      sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$valueW\" w:type=\"dxa\"/></w:tcPr><w:p>").append(docxRun(value, 18)).append("</w:p></w:tc>")
      sb.append("</w:tr>")
    }
    return sb.append("</w:tbl>").toString()
  }

  /** Rough height (twips) of the details table, used to size the photo so photo + table fit their page slot. */
  private fun docxTableEstimate(rec: JSONObject, usableW: Int, labelW: Int = 2600, rowTw: Int = 240): Int {
    val perLine = max(20, (usableW - labelW) / 92)
    var lines = 0
    for ((_, v) in detailRows(rec)) lines += max(1, (v.length + perLine - 1) / perLine) + v.count { it == '\n' }
    return lines * rowTw
  }

  private fun docxBorder(side: String, po: PageOpts): String {
    val col = hex6(po.borderColor)
    return when (po.border) {
      "none" -> "<w:$side w:val=\"nil\"/>"
      "thick" -> "<w:$side w:val=\"single\" w:sz=\"24\" w:space=\"0\" w:color=\"$col\"/>"
      "double" -> "<w:$side w:val=\"double\" w:sz=\"6\" w:space=\"0\" w:color=\"$col\"/>"
      "dashed" -> "<w:$side w:val=\"dashed\" w:sz=\"8\" w:space=\"0\" w:color=\"$col\"/>"
      "dotted" -> "<w:$side w:val=\"dotted\" w:sz=\"8\" w:space=\"0\" w:color=\"$col\"/>"
      else -> "<w:$side w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"$col\"/>"
    }
  }

  /** Picture size in EMU that fits (boxW x boxH twips) keeping the aspect ratio. 1 twip = 635 EMU. */
  private fun fitEmu(w: Int, h: Int, boxW: Int, boxH: Int): Pair<Long, Long> {
    val s = min(boxW.toDouble() / w, boxH.toDouble() / h)
    return Math.max(1L, (w * s * 635).toLong()) to Math.max(1L, (h * s * 635).toLong())
  }

  /** ONE page: a fixed-layout rows x cols table with exact row heights, so it always fits the page. */
  private fun docxGridTable(
    list: List<JSONObject>, imgDims: Array<Pair<Int, Int>?>, po: PageOpts, usableW: Int, availH: Int,
  ): String {
    val n = list.size
    val cols = gridCols(n, po.landscape)
    val rows = (n + cols - 1) / cols
    val spacing = when (po.gap) { "none" -> 0; "large" -> 160; else -> 80 }
    val colW = (usableW - (cols + 1) * spacing) / cols
    val rowH = (availH - (rows + 1) * spacing) / rows
    val capReserve = if (po.caption != "none") 240 else 0
    val innerW = max(200, colW - 120) // tblCellMar left/right 60 + 60
    val innerH = max(200, rowH - 80 - capReserve - 40) // tblCellMar top/bottom 40 + 40, slack for line leading

    val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$usableW\" w:type=\"dxa\"/>")
    if (spacing > 0) sb.append("<w:tblCellSpacing w:w=\"$spacing\" w:type=\"dxa\"/>")
    // With spacing between cells every cell draws its own box, so the table frame itself is left off
    val tbl = { side: String -> if (spacing == 0) docxBorder(side, po) else "<w:$side w:val=\"nil\"/>" }
    sb.append("<w:tblBorders>")
    sb.append(tbl("top")).append(tbl("left")).append(tbl("bottom")).append(tbl("right")).append(tbl("insideH")).append(tbl("insideV"))
    sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
    sb.append("<w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>")
    sb.append("<w:tblGrid>")
    repeat(cols) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
    sb.append("</w:tblGrid>")

    for (r in 0 until rows) {
      sb.append("<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"$rowH\" w:hRule=\"exact\"/></w:trPr>")
      for (cidx in 0 until cols) {
        val i = r * cols + cidx
        sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/><w:tcBorders>")
        val side = { s: String -> if (i < n) docxBorder(s, po) else "<w:$s w:val=\"nil\"/>" }
        sb.append(side("top")).append(side("left")).append(side("bottom")).append(side("right"))
        sb.append("</w:tcBorders><w:vAlign w:val=\"center\"/></w:tcPr>")
        if (i >= n) {
          sb.append("<w:p/>")
        } else {
          val dims = imgDims[i]
          if (dims != null) {
            val (cx, cy) = fitEmu(dims.first, dims.second, innerW, innerH)
            sb.append(docxPicture("rIdImg${i + 1}", i + 1, cx, cy, spacingAfter = 0))
          } else {
            sb.append("<w:p/>")
          }
          captionText(list[i], po.caption)?.let { cap ->
            sb.append("<w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"0\"/><w:jc w:val=\"center\"/></w:pPr>")
              .append(docxRun(cap, 16, color = "334155")).append("</w:p>")
          }
        }
        sb.append("</w:tc>")
      }
      sb.append("</w:tr>")
    }
    return sb.append("</w:tbl>").toString()
  }

  private fun buildDocx(file: File, list: List<JSONObject>, title: String, author: String, layout: String, po: PageOpts) {
    // Page size in twips (1/20 pt); landscape swaps the sides
    val (pw0, ph0) = if (po.pageSize == "letter") 12240 to 15840 else 11906 to 16838
    val pageW = if (po.landscape) ph0 else pw0
    val pageH = if (po.landscape) pw0 else ph0
    val marginTw = 720
    val usableW = pageW - 2 * marginTw
    val usableH = pageH - 2 * marginTw
    val headerReserve = if (po.header) 900 else 0
    val grid = layout == "grid"

    val rids = arrayOfNulls<String>(list.size)
    val dims = arrayOfNulls<Pair<Int, Int>>(list.size)

    ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
      fun put(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
      val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
      put(
        "[Content_Types].xml",
        (head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
          "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
          "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
          "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
          "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
          "</Types>").toByteArray(),
      )
      put(
        "_rels/.rels",
        (head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
          "</Relationships>").toByteArray(),
      )

      // Photos: decode one at a time, re-compress, and release the bitmap before the next
      val maxSide = if (grid) 1000 else 1600
      list.forEachIndexed { i, rec ->
        val bmp = decodeScaled(rec.optString("path"), maxSide) ?: return@forEachIndexed
        val w = bmp.width
        val h = bmp.height
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val n = i + 1
        put("word/media/image$n.jpeg", out.toByteArray())
        rids[i] = "rIdImg$n"
        dims[i] = w to h
      }

      val rels = StringBuilder(head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
      rids.forEachIndexed { i, rid ->
        if (rid != null) rels.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${i + 1}.jpeg\"/>")
      }
      rels.append("</Relationships>")
      put("word/_rels/document.xml.rels", rels.toString().toByteArray())

      val body = StringBuilder()
      if (po.header) {
        val generated = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val sub = listOf(if (author.isNotBlank()) "Prepared by $author" else "", "Generated $generated").filter { it.isNotBlank() }.joinToString(" • ")
        body.append("<w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr>").append(docxRun(title, 32, bold = true)).append("</w:p>")
        body.append("<w:p><w:pPr><w:spacing w:after=\"200\"/></w:pPr>").append(docxRun(sub, 18, color = "64748B")).append("</w:p>")
      }

      if (grid) {
        // Leave room for the 1 pt trailing paragraph Word requires after a table
        body.append(docxGridTable(list, dims, po, usableW, usableH - headerReserve - 160))
        body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>")
      } else if (layout == "double" && po.landscape) {
        // Landscape, 2 per page: each pair is a borderless 2-column table, photo + its details table in each cell
        val colW = usableW / 2
        val innerW = colW - 200 // tblCellMar left/right 100 + 100
        val detailLabelW = 1800
        val tiny = "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>"
        // Measured in a real renderer: header block ~1100 twips, details rows ~265 twips each. Budget a little above that
        // so a pair (plus the title on page 1) always fits one page; a pair that spills would add a near-empty page.
        val ldHeaderReserve = if (po.header) 1250 else 0
        val groups = list.indices.chunked(2)
        groups.forEachIndexed { gi, group ->
          val tableEst = if (po.details) group.maxOf { docxTableEstimate(list[it], innerW, detailLabelW, 290) } else 0
          val boxH = max(1440, usableH - ldHeaderReserve - tableEst - 500)
          body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${colW * 2}\" w:type=\"dxa\"/><w:tblBorders>")
          for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$side w:val=\"nil\"/>")
          body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
          body.append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>")
          body.append("<w:tblGrid><w:gridCol w:w=\"$colW\"/><w:gridCol w:w=\"$colW\"/></w:tblGrid><w:tr>")
          for (k in 0 until 2) {
            body.append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/></w:tcPr>")
            val i = group.getOrNull(k)
            if (i != null) {
              val d = dims[i]
              val rid = rids[i]
              if (d != null && rid != null) {
                val (cx, cy) = fitEmu(d.first, d.second, innerW, boxH)
                body.append(docxPicture(rid, i + 1, cx, cy))
              }
              if (po.details) body.append(docxDetailsTable(list[i], innerW, detailLabelW))
            }
            body.append("<w:p><w:pPr>").append(tiny).append("</w:pPr></w:p></w:tc>") // a cell must end with a paragraph
          }
          body.append("</w:tr></w:tbl>")
          // 1 pt separator; carries the page break between pairs so it cannot spill onto an extra blank page
          body.append("<w:p><w:pPr>").append(tiny).append("</w:pPr>")
          if (gi < groups.size - 1) body.append("<w:r><w:br w:type=\"page\"/></w:r>")
          body.append("</w:p>")
        }
      } else {
        val perPage = if (layout == "double") 2 else 1
        val slotH = (usableH - headerReserve) / perPage
        val groups = list.indices.chunked(perPage)
        groups.forEachIndexed { gi, group ->
          group.forEachIndexed { k, i ->
            val d = dims[i]
            val rid = rids[i]
            if (d != null && rid != null) {
              val boxH = max(1440, slotH - (if (po.details) docxTableEstimate(list[i], usableW) else 0) - 360)
              val (cx, cy) = fitEmu(d.first, d.second, usableW, boxH)
              body.append(docxPicture(rid, i + 1, cx, cy))
            }
            if (po.details) body.append(docxDetailsTable(list[i], usableW))
            if (k < group.size - 1) body.append("<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr></w:p>")
          }
          if (gi < groups.size - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        body.append("<w:p/>") // a body must not end with a table
      }
      body.append("<w:sectPr><w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"")
      if (po.landscape) body.append(" w:orient=\"landscape\"")
      body.append("/><w:pgMar w:top=\"$marginTw\" w:right=\"$marginTw\" w:bottom=\"$marginTw\" w:left=\"$marginTw\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/></w:sectPr>")

      put(
        "word/document.xml",
        (head + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
          "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
          "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
          "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
          "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>" +
          body + "</w:body></w:document>").toByteArray(),
      )
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
