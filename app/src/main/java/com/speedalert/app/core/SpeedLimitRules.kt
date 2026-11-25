package com.speedalert.app.core

import com.speedalert.app.data.RoadSegment

/** Kết quả xác định giới hạn tốc độ cho một đoạn đường. */
data class SpeedLimitInfo(
    /** km/h, null nếu không áp dụng được (vd xe máy trên cao tốc). */
    val limitKmh: Int?,
    /** true = lấy từ biển báo (dữ liệu bản đồ hoặc người dùng sửa), false = suy ra theo luật. */
    val fromSign: Boolean,
    /** Ghi chú hiển thị thêm (vd "Cao tốc - cấm xe máy"). */
    val note: String = "",
    /** "user" = bạn tự sửa, "sign" = biển báo trên bản đồ, "law" = suy theo luật. */
    val source: String = if (fromSign) "sign" else "law"
)

/**
 * Giới hạn tốc độ theo Thông tư 38/2024/TT-BGTVT (hiệu lực 01/01/2025):
 * - Trong khu đông dân cư: 60 km/h (đường đôi / 1 chiều từ 2 làn), 50 km/h (còn lại).
 * - Ngoài khu đông dân cư: ô tô con 90/80, mô tô 70/60.
 * - Xe gắn máy (kể cả xe máy điện tương đương): tối đa 40 km/h.
 * - Cao tốc: theo biển báo, tối đa 120 km/h (xe máy không được đi).
 *
 * Biển báo thực tế (thẻ maxspeed trong OSM) luôn được ưu tiên, nhưng không vượt
 * trần của loại xe (vd biển 80 trên quốc lộ thì xe máy vẫn tính trần 70).
 */
object SpeedLimitRules {

    /**
     * Giới hạn riêng cho XE MÁY trên một số tuyến đã xác minh qua báo chí / cơ quan
     * chức năng (OpenStreetMap chưa ghi). Áp dụng khi tên đường khớp và nằm trong vùng.
     */
    private data class Corridor(
        val names: List<String>,
        val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double,
        val motoLimit: Int,
        val source: String
    )

    private const val HCM_MIN_LAT = 10.35
    private const val HCM_MAX_LAT = 11.20
    private const val HCM_MIN_LON = 106.35
    private const val HCM_MAX_LON = 107.05

    private val CORRIDORS = listOf(
        // PLO 01/2025: Võ Văn Kiệt cho phép xe máy tối đa 50 km/h.
        Corridor(listOf("võ văn kiệt"), HCM_MIN_LAT, HCM_MAX_LAT, HCM_MIN_LON, HCM_MAX_LON, 50, "Võ Văn Kiệt - xe máy 50"),
        // Sở GTVT TP.HCM: Quốc lộ 22 giới hạn xe máy 50 km/h.
        Corridor(listOf("quốc lộ 22", "ql22", "ql.22", "xa lộ xuyên á"), HCM_MIN_LAT, HCM_MAX_LAT, HCM_MIN_LON, HCM_MAX_LON, 50, "QL22 - xe máy 50"),
        // Phạm Văn Đồng: làn hỗn hợp điều chỉnh từ 80 xuống 60 km/h.
        Corridor(listOf("phạm văn đồng"), HCM_MIN_LAT, HCM_MAX_LAT, HCM_MIN_LON, HCM_MAX_LON, 60, "Phạm Văn Đồng - làn hỗn hợp 60")
    )

    private fun normalize(name: String) = name.lowercase().trim().removePrefix("đường ").removePrefix("đại lộ ").trim()

    private fun corridorLimit(seg: RoadSegment): Int? {
        if (seg.geometry.isEmpty()) return null
        val mid = seg.geometry[seg.geometry.size / 2]
        val keys = listOf(normalize(seg.name), normalize(seg.ref))
        return CORRIDORS.firstOrNull { c ->
            mid.lat in c.minLat..c.maxLat && mid.lon in c.minLon..c.maxLon &&
                c.names.any { n -> keys.any { it == n || it.startsWith("$n ") } }
        }?.motoLimit
    }

    private val MAJOR_DIVIDED_TYPES = setOf("motorway", "trunk", "primary", "motorway_link", "trunk_link")

    fun resolve(seg: RoadSegment, vehicle: VehicleType): SpeedLimitInfo {
        UserLimits.get(seg.osmId)?.let { return SpeedLimitInfo(it, true, "", "user") }
        val isMotorway = seg.highwayType == "motorway" || seg.highwayType == "motorway_link"
        if (isMotorway && vehicle != VehicleType.CAR) {
            return SpeedLimitInfo(null, false, "Cao tốc - cấm xe máy")
        }
        if (!seg.motorcycleAllowed && vehicle != VehicleType.CAR) {
            return SpeedLimitInfo(null, false, "Làn/đường này cấm xe máy - đi đường song hành")
        }
        if (vehicle == VehicleType.MOTORBIKE) {
            corridorLimit(seg)?.let { return SpeedLimitInfo(it, true) }
        }

        val multiLane = (seg.oneway && (seg.lanes >= 2 || (seg.lanes == 0 && seg.highwayType in MAJOR_DIVIDED_TYPES)))
        val legal = when {
            isMotorway -> 120
            vehicle == VehicleType.MOPED -> 40
            seg.urban -> if (multiLane) 60 else 50
            vehicle == VehicleType.CAR -> if (multiLane) 90 else 80
            else -> if (multiLane) 70 else 60
        }
        val vehicleCap = when (vehicle) {
            VehicleType.MOPED -> 40
            VehicleType.MOTORBIKE -> 70
            VehicleType.CAR -> 120
        }

        // Xe máy đi làn ngoài cùng bên phải → lấy giới hạn của làn cuối trong maxspeed:lanes.
        val laneSign = if (vehicle != VehicleType.CAR && seg.maxspeedLanesRaw.isNotBlank()) {
            seg.maxspeedLanesRaw.split('|').lastOrNull()?.let { parseMaxspeed(it) }
        } else null
        val sign = when {
            vehicle != VehicleType.CAR && seg.maxspeedMotoRaw.isNotBlank() -> parseMaxspeed(seg.maxspeedMotoRaw)
            laneSign != null -> laneSign
            else -> parseMaxspeed(seg.maxspeedRaw)
        }
        val motoNote = if (vehicle != VehicleType.CAR && seg.motorcycleNo) "Phần đường cấm xe máy" else ""
        if (sign != null) {
            return SpeedLimitInfo(minOf(sign, vehicleCap), true, motoNote)
        }
        return SpeedLimitInfo(minOf(legal, vehicleCap), false, motoNote)
    }

    /** "60" / "60 km/h" / "40 mph" → km/h. Các giá trị kiểu "VN:urban" → null. */
    fun parseMaxspeed(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val firstPart = s.split(';').first().trim()
        val number = Regex("^(\\d{1,3})").find(firstPart)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (number < 5 || number > 150) return null
        return if (firstPart.contains("mph")) (number * 1.609).toInt() else number
    }

    /** Thẻ maxspeed dạng vùng ("VN:urban"/"VN:rural") cho biết khu đông dân cư hay không. */
    fun zoneFromTag(raw: String): Boolean? {
        val s = raw.trim().lowercase()
        return when {
            s.contains("urban") -> true
            s.contains("rural") -> false
            else -> null
        }
    }
}
