package com.mps.geostamp.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.facebook.react.bridge.ReadableMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the GPS/compass stamp onto a captured photo. */
object StampRenderer {

  data class Spec(
    val title: String,
    val lines: List<String>,
    val position: String,
    val style: String,
    val textSize: String,
    val color: Int,
    val font: String,
    val background: Boolean,
    val compass: Boolean,
    val heading: Double,
    val watermark: String,
  )

  fun parse(m: ReadableMap?): Spec? {
    if (m == null) return null
    val linesArr = if (m.hasKey("lines")) m.getArray("lines") else null
    val lines = mutableListOf<String>()
    if (linesArr != null) for (i in 0 until linesArr.size()) linesArr.getString(i)?.let { if (it.isNotEmpty()) lines.add(it) }
    fun s(k: String, d: String) = if (m.hasKey(k) && !m.isNull(k)) m.getString(k) ?: d else d
    fun b(k: String, d: Boolean) = if (m.hasKey(k) && !m.isNull(k)) m.getBoolean(k) else d
    val color = try { Color.parseColor(s("color", "#FFFFFF")) } catch (_: Throwable) { Color.WHITE }
    val heading = if (m.hasKey("heading") && !m.isNull("heading")) m.getDouble("heading") else Double.NaN
    return Spec(
      title = s("title", ""),
      lines = lines,
      position = s("position", "tr"),
      style = s("style", "classic"),
      textSize = s("textSize", "medium"),
      color = color,
      font = s("font", "default"),
      background = b("background", false),
      compass = b("compass", true) && !heading.isNaN(),
      heading = heading,
      watermark = s("watermark", ""),
    )
  }

  private fun typeface(font: String, bold: Boolean): Typeface {
    val base = when (font) {
      "mono" -> Typeface.MONOSPACE
      "serif" -> Typeface.SERIF
      "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
      else -> Typeface.SANS_SERIF
    }
    val styleBold = bold || font == "bold"
    return Typeface.create(base, if (styleBold) Typeface.BOLD else Typeface.NORMAL)
  }

  fun draw(bmp: Bitmap, spec: Spec) {
    val canvas = Canvas(bmp)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val unit = min(w, h)
    val factor = when (spec.textSize) {
      "small" -> 0.026f
      "large" -> 0.044f
      "xlarge" -> 0.054f
      else -> 0.034f
    }
    val ts = unit * factor
    val margin = unit * 0.03f
    if (spec.style == "card") drawCard(canvas, w, h, ts, margin, spec) else drawClassic(canvas, w, h, ts, margin, spec)

    if (spec.watermark.isNotBlank()) {
      val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ts * 0.75f
        color = Color.argb(200, 255, 255, 255)
        typeface = typeface(spec.font, true)
        setShadowLayer(ts * 0.15f, 0f, 0f, Color.argb(200, 0, 0, 0))
      }
      val top = spec.style != "card" && spec.position.startsWith("b")
      val y = if (top) margin + ts else h - margin - (if (spec.style == "card") cardHeightCache + margin * 0.6f else 0f)
      val right = spec.position.endsWith("l") || spec.style == "card"
      val tw = p.measureText(spec.watermark)
      val x = if (right) w - margin - tw else margin
      canvas.drawText(spec.watermark, x, y, p)
    }
  }

  private var cardHeightCache = 0f

  private fun drawClassic(canvas: Canvas, w: Float, h: Float, ts: Float, margin: Float, spec: Spec) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = ts
      color = spec.color
      typeface = typeface(spec.font, false)
      setShadowLayer(ts * 0.12f, ts * 0.04f, ts * 0.04f, Color.argb(220, 0, 0, 0))
    }
    val titlePaint = Paint(paint).apply { typeface = typeface(spec.font, true); textSize = ts * 1.12f }
    val all = mutableListOf<Pair<String, Paint>>()
    if (spec.title.isNotBlank()) all.add(spec.title to titlePaint)
    spec.lines.forEach { line -> wrap(line, paint, w * 0.62f).forEach { all.add(it to paint) } }

    val blockH = all.sumOf { (it.second.textSize * 1.28f).toDouble() }.toFloat()
    val blockW = all.maxOfOrNull { it.second.measureText(it.first) } ?: 0f
    val right = spec.position.endsWith("r")
    val bottom = spec.position.startsWith("b")
    var y = if (bottom) h - margin - blockH else margin
    val pad = ts * 0.5f

    if (spec.background && all.isNotEmpty()) {
      val bx = if (right) w - margin - blockW - pad else margin - pad
      val rect = RectF(bx, y - pad * 0.6f, bx + blockW + pad * 2, y + blockH + pad * 0.6f)
      val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
      canvas.drawRoundRect(rect, ts * 0.4f, ts * 0.4f, bg)
    }
    for ((text, p) in all) {
      val lh = p.textSize * 1.28f
      val tw = p.measureText(text)
      val x = if (right) w - margin - tw else margin
      canvas.drawText(text, x, y + lh * 0.8f, p)
      y += lh
    }

    if (spec.compass) {
      val radius = min(w, h) * 0.085f
      val cx = if (right) margin + radius + ts * 0.3f else w - margin - radius - ts * 0.3f
      val cy = if (bottom) margin + radius + ts * 0.3f else h - margin - radius - ts * 0.3f
      drawRose(canvas, cx, cy, radius, spec.heading)
    }
  }

  private fun drawCard(canvas: Canvas, w: Float, h: Float, ts: Float, margin: Float, spec: Spec) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = ts * 0.92f
      color = spec.color
      typeface = typeface(spec.font, false)
    }
    val titlePaint = Paint(paint).apply { typeface = typeface(spec.font, true); textSize = ts * 1.18f }
    val pad = ts * 0.8f
    val roseR = if (spec.compass) min(w, h) * 0.075f else 0f
    val textMaxW = w - margin * 2 - pad * 2 - (if (spec.compass) roseR * 2 + pad else 0f)

    val all = mutableListOf<Pair<String, Paint>>()
    if (spec.title.isNotBlank()) wrap(spec.title, titlePaint, textMaxW).forEach { all.add(it to titlePaint) }
    spec.lines.forEach { line -> wrap(line, paint, textMaxW).forEach { all.add(it to paint) } }

    val textH = all.sumOf { (it.second.textSize * 1.3f).toDouble() }.toFloat()
    val cardH = max(textH, roseR * 2 + ts * 0.4f) + pad * 2
    cardHeightCache = cardH
    val top = h - margin - cardH
    val rect = RectF(margin, top, w - margin, h - margin)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    canvas.drawRoundRect(rect, ts * 0.6f, ts * 0.6f, bg)
    // Accent bar
    val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22C3EE") }
    canvas.drawRoundRect(RectF(margin, top, margin + ts * 0.28f, h - margin), ts * 0.14f, ts * 0.14f, accent)

    var y = top + pad + (cardH - pad * 2 - textH) / 2f
    for ((text, p) in all) {
      val lh = p.textSize * 1.3f
      canvas.drawText(text, margin + pad, y + lh * 0.8f, p)
      y += lh
    }
    if (spec.compass) {
      val cx = w - margin - pad - roseR
      val cy = top + cardH / 2f
      drawRose(canvas, cx, cy, roseR, spec.heading)
    }
  }

  private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
    val out = mutableListOf<String>()
    for (para in text.split("\n")) {
      if (p.measureText(para) <= maxW) { out.add(para); continue }
      var cur = StringBuilder()
      for (word in para.split(" ")) {
        val test = if (cur.isEmpty()) word else "$cur $word"
        if (p.measureText(test) <= maxW || cur.isEmpty()) {
          cur = StringBuilder(test)
        } else {
          out.add(cur.toString()); cur = StringBuilder(word)
        }
      }
      if (cur.isNotEmpty()) out.add(cur.toString())
    }
    return out
  }

  /** Compass rose: dial rotates so letters point to real directions; arrow = camera direction. */
  fun drawRose(canvas: Canvas, cx: Float, cy: Float, r: Float, heading: Double) {
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE; strokeWidth = r * 0.16f; color = Color.argb(200, 40, 40, 40)
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    canvas.drawCircle(cx, cy, r, fill)
    canvas.drawCircle(cx, cy, r * 0.92f, ring)

    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); strokeWidth = r * 0.025f }
    for (deg in 0 until 360 step 30) {
      val a = Math.toRadians(deg - heading)
      val r1 = r * 0.70f
      val r2 = r * 0.78f
      canvas.drawLine(cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(), cx + (sin(a) * r2).toFloat(), cy - (cos(a) * r2).toFloat(), tick)
    }

    val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = r * 0.30f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    val labels = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
    for ((t, d) in labels) {
      val a = Math.toRadians(d - heading)
      val rr = r * 0.92f
      val x = cx + (sin(a) * rr).toFloat()
      val y = cy - (cos(a) * rr).toFloat()
      lp.color = if (t == "N") Color.parseColor("#FF5252") else Color.WHITE
      canvas.save()
      canvas.rotate((d - heading).toFloat(), x, y)
      canvas.drawText(t, x, y + lp.textSize * 0.36f, lp)
      canvas.restore()
    }

    // Fixed needle = where the camera points
    val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22C3EE") }
    val path = Path().apply {
      moveTo(cx, cy - r * 0.66f)
      lineTo(cx + r * 0.12f, cy + r * 0.50f)
      lineTo(cx - r * 0.12f, cy + r * 0.50f)
      close()
    }
    canvas.drawPath(path, needle)
    canvas.drawCircle(cx, cy, r * 0.06f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
  }
}
