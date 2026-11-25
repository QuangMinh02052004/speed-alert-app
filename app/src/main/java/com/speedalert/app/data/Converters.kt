package com.speedalert.app.data

import androidx.room.TypeConverter

/**
 * Lưu hình dạng đường dạng chuỗi gọn "lat,lon;lat,lon" (nhẹ và nhanh hơn JSON
 * nhiều lần, quan trọng khi một ô ở thành phố có hàng nghìn đoạn đường).
 */
class Converters {
    @TypeConverter
    fun fromGeometry(points: List<GeoPoint>): String {
        val sb = StringBuilder(points.size * 22)
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(';')
            sb.append(String.format(java.util.Locale.US, "%.6f,%.6f", p.lat, p.lon))
        }
        return sb.toString()
    }

    @TypeConverter
    fun toGeometry(data: String): List<GeoPoint> {
        if (data.isEmpty()) return emptyList()
        val parts = data.split(';')
        val list = ArrayList<GeoPoint>(parts.size)
        for (part in parts) {
            val comma = part.indexOf(',')
            if (comma <= 0) continue
            val lat = part.substring(0, comma).toDoubleOrNull() ?: continue
            val lon = part.substring(comma + 1).toDoubleOrNull() ?: continue
            list.add(GeoPoint(lat, lon))
        }
        return list
    }
}
