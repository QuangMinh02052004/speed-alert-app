package com.speedalert.app.core

import com.speedalert.app.data.RoadSegment

/** Kết quả xác định giới hạn tốc độ cho một đoạn đường. */
data class SpeedLimitInfo(
    /** km/h, null nếu không áp dụng được (vd xe máy trên cao tốc). */
    val limitKmh: Int?,
    /** true = lấy từ biển báo trong dữ liệu OSM, false = suy ra theo luật. */
    val fromSign: Boolean,
    /** Ghi chú hiển thị thêm (vd "Cao tốc - cấm xe máy"). */
    val note: String = ""
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

    private val MAJOR_DIVIDED_TYPES = setOf("motorway", "trunk", "primary", "motorway_link", "trunk_link")

    fun resolve(seg: RoadSegment, vehicle: VehicleType): SpeedLimitInfo {
        val isMotorway = seg.highwayType == "motorway" || seg.highwayType == "motorway_link"
        if (isMotorway && vehicle != VehicleType.CAR) {
            return SpeedLimitInfo(null, false, "Cao tốc - cấm xe máy")
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

        val signRaw = if (vehicle != VehicleType.CAR && seg.maxspeedMotoRaw.isNotBlank()) seg.maxspeedMotoRaw else seg.maxspeedRaw
        val sign = parseMaxspeed(signRaw)
        if (sign != null) {
            return SpeedLimitInfo(minOf(sign, vehicleCap), true)
        }
        return SpeedLimitInfo(minOf(legal, vehicleCap), false)
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
