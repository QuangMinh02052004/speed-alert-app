package com.speedalert.app.data

import android.content.Context
import org.json.JSONArray
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nạp dữ liệu giới hạn tốc độ (xuất ra từ speed_limit_data_pipeline.py) từ
 * assets/speed_limits.json vào Room DB ở lần chạy đầu, và tra cứu đoạn đường
 * gần vị trí hiện tại nhất.
 *
 * LƯU Ý: tra cứu hiện dùng linear scan qua toàn bộ điểm của toàn bộ đoạn
 * đường - đủ nhanh cho vùng test nhỏ (vd. 1 quận), nhưng cần đánh index
 * không gian (vd. R-tree / SQLite RTree module) nếu mở rộng dữ liệu ra
 * toàn thành phố/toàn quốc.
 */
class SpeedRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val dao = db.speedLimitDao()
    private val cameraDao = db.cameraDao()

    suspend fun ensureSeeded() {
        if (dao.count() > 0) return
        val json = appContext.assets.open("speed_limits.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val segments = mutableListOf<RoadSegment>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val geomArr = obj.optJSONArray("geometry") ?: continue
            val points = mutableListOf<GeoPoint>()
            for (j in 0 until geomArr.length()) {
                val p = geomArr.getJSONObject(j)
                points.add(GeoPoint(p.getDouble("lat"), p.getDouble("lon")))
            }
            if (points.isEmpty()) continue
            segments.add(
                RoadSegment(
                    osmId = obj.getLong("osm_id"),
                    name = obj.optString("name", ""),
                    highwayType = obj.optString("highway_type", ""),
                    speedKmh = obj.getInt("speed_kmh"),
                    source = obj.optString("source", ""),
                    geometry = points
                )
            )
        }
        if (segments.isNotEmpty()) dao.insertAll(segments)
    }

    suspend fun findNearestSpeedLimit(lat: Double, lon: Double): RoadSegment? {
        val all = dao.getAll()
        var best: RoadSegment? = null
        var bestDist = Double.MAX_VALUE
        for (segment in all) {
            for (point in segment.geometry) {
                val d = haversineMeters(lat, lon, point.lat, point.lon)
                if (d < bestDist) {
                    bestDist = d
                    best = segment
                }
            }
        }
        // Bỏ qua nếu điểm gần nhất cách quá xa (>200m) - có thể đang ngoài vùng dữ liệu đã nạp
        return if (bestDist <= 200.0) best else null
    }

    /** Tìm camera đã lưu gần vị trí hiện tại nhất, trong bán kính radiusMeters. */
    suspend fun findNearbyCamera(lat: Double, lon: Double, radiusMeters: Double = 300.0): CameraLocation? {
        val cameras = cameraDao.getAll()
        var nearest: CameraLocation? = null
        var bestDist = Double.MAX_VALUE
        for (cam in cameras) {
            val d = haversineMeters(lat, lon, cam.lat, cam.lon)
            if (d < bestDist) {
                bestDist = d
                nearest = cam
            }
        }
        return if (bestDist <= radiusMeters) nearest else null
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
