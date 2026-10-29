package com.mps.geostamp.store

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

object PhotoStore {
  const val ALBUM = "GeoStamp"

  fun photosDir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }
  fun exportsDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

  fun writeRecord(
    ctx: Context, id: String, file: File, thumb: File, width: Int, height: Int,
    createdAt: Long, galleryUri: String?, meta: ReadableMap?,
  ): WritableMap {
    val json = JSONObject()
    json.put("id", id)
    json.put("path", file.absolutePath)
    json.put("thumb", thumb.absolutePath)
    json.put("width", width)
    json.put("height", height)
    json.put("createdAt", createdAt)
    json.put("galleryUri", galleryUri ?: JSONObject.NULL)
    json.put("meta", if (meta != null) mapToJson(meta) else JSONObject())
    File(photosDir(ctx), "$id.json").writeText(json.toString())
    return jsonToMap(json)
  }

  fun readAll(ctx: Context): List<JSONObject> {
    val files = photosDir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
    return files.mapNotNull {
      try {
        val o = JSONObject(it.readText())
        if (File(o.getString("path")).exists()) o else null
      } catch (_: Throwable) { null }
    }.sortedByDescending { it.optLong("createdAt") }
  }

  fun read(ctx: Context, id: String): JSONObject? = try {
    JSONObject(File(photosDir(ctx), "$id.json").readText())
  } catch (_: Throwable) { null }

  fun updateMeta(ctx: Context, id: String, patch: ReadableMap): JSONObject? {
    val o = read(ctx, id) ?: return null
    val meta = o.optJSONObject("meta") ?: JSONObject()
    val p = mapToJson(patch)
    for (k in p.keys()) meta.put(k, p.get(k))
    o.put("meta", meta)
    File(photosDir(ctx), "$id.json").writeText(o.toString())
    return o
  }

  fun delete(ctx: Context, id: String, alsoGallery: Boolean) {
    val o = read(ctx, id)
    if (o != null) {
      File(o.optString("path")).delete()
      File(o.optString("thumb")).delete()
      if (alsoGallery) {
        val g = o.optString("galleryUri", "")
        if (g.isNotEmpty() && g != "null") {
          try { ctx.contentResolver.delete(Uri.parse(g), null, null) } catch (_: Throwable) {}
        }
      }
    }
    File(photosDir(ctx), "$id.json").delete()
  }

  /** Copies a JPEG into the public Pictures/GeoStamp album. Returns content Uri string. */
  fun copyToGallery(ctx: Context, file: File, displayName: String): String? {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> FileInputStream(file).use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
      } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        dir.mkdirs()
        val dest = File(dir, displayName)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
        Uri.fromFile(dest).toString()
      }
    } catch (_: Throwable) {
      null
    }
  }

  /** Saves an exported file into Downloads/GeoStamp (Android 10+). Returns a readable location. */
  fun copyToDownloads(ctx: Context, file: File, mime: String): String? {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, file.name)
          put(MediaStore.Downloads.MIME_TYPE, mime)
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ALBUM)
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> FileInputStream(file).use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Downloads/$ALBUM/${file.name}"
      } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ALBUM)
        dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
        "Downloads/$ALBUM/${file.name}"
      }
    } catch (_: Throwable) {
      null
    }
  }

  // ---------- JSON <-> React bridging ----------

  fun mapToJson(map: ReadableMap): JSONObject {
    val o = JSONObject()
    val it = map.keySetIterator()
    while (it.hasNextKey()) {
      val k = it.nextKey()
      when (map.getType(k)) {
        ReadableType.Null -> o.put(k, JSONObject.NULL)
        ReadableType.Boolean -> o.put(k, map.getBoolean(k))
        ReadableType.Number -> o.put(k, map.getDouble(k))
        ReadableType.String -> o.put(k, map.getString(k))
        ReadableType.Map -> o.put(k, mapToJson(map.getMap(k)!!))
        ReadableType.Array -> o.put(k, arrayToJson(map.getArray(k)!!))
      }
    }
    return o
  }

  fun arrayToJson(arr: ReadableArray): JSONArray {
    val a = JSONArray()
    for (i in 0 until arr.size()) {
      when (arr.getType(i)) {
        ReadableType.Null -> a.put(JSONObject.NULL)
        ReadableType.Boolean -> a.put(arr.getBoolean(i))
        ReadableType.Number -> a.put(arr.getDouble(i))
        ReadableType.String -> a.put(arr.getString(i))
        ReadableType.Map -> a.put(mapToJson(arr.getMap(i)!!))
        ReadableType.Array -> a.put(arrayToJson(arr.getArray(i)!!))
      }
    }
    return a
  }

  fun jsonToMap(o: JSONObject): WritableMap {
    val m = Arguments.createMap()
    for (k in o.keys()) {
      when (val v = o.get(k)) {
        JSONObject.NULL -> m.putNull(k)
        is Boolean -> m.putBoolean(k, v)
        is Int -> m.putDouble(k, v.toDouble())
        is Long -> m.putDouble(k, v.toDouble())
        is Double -> m.putDouble(k, v)
        is Float -> m.putDouble(k, v.toDouble())
        is Number -> m.putDouble(k, v.toDouble())
        is String -> m.putString(k, v)
        is JSONObject -> m.putMap(k, jsonToMap(v))
        is JSONArray -> m.putArray(k, jsonToArray(v))
        else -> m.putString(k, v.toString())
      }
    }
    return m
  }

  fun jsonToArray(a: JSONArray): WritableArray {
    val arr = Arguments.createArray()
    for (i in 0 until a.length()) {
      when (val v = a.get(i)) {
        JSONObject.NULL -> arr.pushNull()
        is Boolean -> arr.pushBoolean(v)
        is Number -> arr.pushDouble(v.toDouble())
        is String -> arr.pushString(v)
        is JSONObject -> arr.pushMap(jsonToMap(v))
        is JSONArray -> arr.pushArray(jsonToArray(v))
        else -> arr.pushString(v.toString())
      }
    }
    return arr
  }
}
