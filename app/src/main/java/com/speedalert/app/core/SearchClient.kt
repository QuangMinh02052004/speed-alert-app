package com.speedalert.app.core

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.speedalert.app.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val address: String, val point: GeoPoint)

/**
 * Tìm địa chỉ / địa điểm, ưu tiên kết quả gần vị trí hiện tại:
 * 1. Geocoder của máy (Google, chính xác với số nhà Việt Nam),
 * 2. Photon (OpenStreetMap, tìm mờ tên địa điểm, có ưu tiên vị trí gần),
 * 3. Nominatim (OpenStreetMap) giới hạn trong Việt Nam.
 * Gộp kết quả, bỏ trùng, sắp theo khoảng cách tới bạn.
 */
object SearchClient {
    private const val TAG = "Search"

    fun search(context: Context, query: String, near: GeoPoint?): List<Place> {
        val results = mutableListOf<Place>()
        results += runCatching { geocoder(context, query) }.onFailure { Log.w(TAG, "Geocoder", it) }.getOrDefault(emptyList())
        results += runCatching { photon(query, near) }.onFailure { Log.w(TAG, "Photon", it) }.getOrDefault(emptyList())
        if (results.size < 3) {
            results += runCatching { nominatim(query, near) }.onFailure { Log.w(TAG, "Nominatim", it) }.getOrDefault(emptyList())
        }
        val unique = mutableListOf<Place>()
        for (p in results) {
            if (unique.none { GeoUtils.distance(it.point, p.point) < 40 }) unique.add(p)
        }
        return if (near != null) unique.sortedBy { GeoUtils.distance(it.point, near) }.take(8) else unique.take(8)
    }

    @Suppress("DEPRECATION")
    private fun geocoder(context: Context, query: String): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val list = Geocoder(context, Locale("vi", "VN")).getFromLocationName(query, 5) ?: return emptyList()
        return list.map { a ->
            val line = a.getAddressLine(0) ?: query
            Place(a.featureName?.takeIf { it.isNotBlank() && it != a.subThoroughfare } ?: line.substringBefore(','), line, GeoPoint(a.latitude, a.longitude))
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", HttpConfig.USER_AGENT)
            conn.setRequestProperty("Accept-Language", "vi")
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun photon(query: String, near: GeoPoint?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val bias = if (near != null) "&lat=${near.lat}&lon=${near.lon}" else ""
        // Khung bao Việt Nam để không lẫn kết quả nước ngoài.
        val url = "https://photon.komoot.io/api/?q=$q&limit=6$bias&bbox=102.1,8.4,109.5,23.4"
        val features = JSONObject(get(url)).getJSONArray("features")
        val out = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val props = f.getJSONObject("properties")
            val name = props.optString("name").ifBlank {
                listOf(props.optString("housenumber"), props.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
            }
            val addr = listOf(
                listOf(props.optString("housenumber"), props.optString("street")).filter { it.isNotBlank() }.joinToString(" "),
                props.optString("district"), props.optString("city"), props.optString("state")
            ).filter { it.isNotBlank() && it != name }.joinToString(", ")
            out.add(Place(name.ifBlank { query }, addr, GeoPoint(coords.getDouble(1), coords.getDouble(0))))
        }
        return out
    }

    private fun nominatim(query: String, near: GeoPoint?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val viewbox = if (near != null) {
            "&viewbox=${near.lon - 0.3},${near.lat + 0.3},${near.lon + 0.3},${near.lat - 0.3}"
        } else ""
        val url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=5&countrycodes=vn&accept-language=vi$viewbox"
        val arr = JSONArray(get(url))
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            out.add(Place(display.substringBefore(','), display, GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble())))
        }
        return out
    }

    /** Địa chỉ của một toạ độ (dùng cho bảng thông tin vị trí). */
    @Suppress("DEPRECATION")
    fun reverse(context: Context, p: GeoPoint): String? {
        runCatching {
            if (Geocoder.isPresent()) {
                Geocoder(context, Locale("vi", "VN")).getFromLocation(p.lat, p.lon, 1)
                    ?.firstOrNull()?.getAddressLine(0)?.let { return it }
            }
        }
        return runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=${p.lat}&lon=${p.lon}&format=json&accept-language=vi"
            JSONObject(get(url)).optString("display_name").ifBlank { null }
        }.getOrNull()
    }
}
