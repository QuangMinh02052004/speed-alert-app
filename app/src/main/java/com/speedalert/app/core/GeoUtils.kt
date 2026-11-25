package com.speedalert.app.core

import com.speedalert.app.data.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Kết quả chiếu một điểm lên một đường gấp khúc. */
data class Projection(
    /** Khoảng cách (m) từ điểm tới đường. */
    val distance: Double,
    /** Chỉ số đoạn thẳng gần nhất (từ điểm i tới i+1). */
    val segmentIndex: Int,
    /** Tỉ lệ 0..1 dọc đoạn thẳng đó. */
    val fraction: Double,
    /** Điểm đã chiếu (nằm trên đường). */
    val point: GeoPoint,
    /** Hướng (độ, 0 = Bắc) của đoạn thẳng gần nhất. */
    val bearing: Double
)

object GeoUtils {
    private const val EARTH_RADIUS = 6371000.0

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(a), sqrt(1 - a))
    }

    fun distance(a: GeoPoint, b: GeoPoint) = haversine(a.lat, a.lon, b.lat, b.lon)

    /** Hướng từ a tới b, độ 0..360 (0 = Bắc, 90 = Đông). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Chênh lệch góc nhỏ nhất giữa hai hướng, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    /**
     * Chiếu điểm p lên đường gấp khúc [line] (phép chiếu phẳng cục bộ - đủ chính
     * xác ở cự ly vài trăm mét). Chỉ xét các đoạn từ [from] tới [to] nếu cho.
     */
    fun projectOnLine(p: GeoPoint, line: List<GeoPoint>, from: Int = 0, to: Int = line.size - 1): Projection? {
        if (line.size < 2) return null
        val start = from.coerceIn(0, line.size - 2)
        val end = to.coerceIn(start + 1, line.size - 1)
        val cosLat = cos(Math.toRadians(p.lat))
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cosLat

        var best: Projection? = null
        for (i in start until end) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lon - p.lon) * mPerDegLon
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lon - p.lon) * mPerDegLon
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0) -(ax * dx + ay * dy) / len2 else 0.0
            t = t.coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val dist = sqrt(cx * cx + cy * cy)
            if (best == null || dist < best.distance) {
                val point = GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
                best = Projection(dist, i, t, point, bearing(a, b))
            }
        }
        return best
    }

    /** Khoảng cách cộng dồn dọc đường gấp khúc, phần tử i = quãng đường từ điểm 0 tới điểm i. */
    fun cumulativeDistances(line: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) out[i] = out[i - 1] + distance(line[i - 1], line[i])
        return out
    }
}
