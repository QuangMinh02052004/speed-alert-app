package com.speedalert.app.data

import androidx.room.Entity
import androidx.room.Index

data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Một đoạn đường (OSM way) trong một ô khu vực (tile). Một way dài có thể nằm
 * ở nhiều ô, nên khoá chính gồm cả osmId và tileKey; khi đọc lên sẽ gộp trùng.
 *
 * Giới hạn tốc độ KHÔNG lưu sẵn mà tính lúc chạy (SpeedLimitRules) từ các thẻ
 * thô bên dưới, để đổi loại xe (xe máy / ô tô / xe gắn máy) là áp dụng ngay.
 */
@Entity(
    tableName = "road_segments",
    primaryKeys = ["osmId", "tileKey"],
    indices = [Index("tileKey")]
)
data class RoadSegment(
    val osmId: Long,
    val tileKey: String,
    val name: String,
    val highwayType: String,
    /** Thẻ maxspeed gốc (vd "60", "50 km/h", "VN:urban"), rỗng nếu không có. */
    val maxspeedRaw: String,
    /** Thẻ maxspeed:motorcycle nếu có. */
    val maxspeedMotoRaw: String,
    val lanes: Int,
    val oneway: Boolean,
    /** Số hiệu tuyến, vd "QL.1", "QL.52". */
    val ref: String,
    /** false nếu đoạn này cấm xe máy (vd làn chính Xa lộ Hà Nội, cao tốc). */
    val motorcycleAllowed: Boolean,
    /** Suy ra lúc tải: đoạn đường nằm trong khu vực đông dân cư hay không. */
    val urban: Boolean,
    val geometry: List<GeoPoint>,
    /** true nếu bản đồ ghi phần đường này cấm xe máy (motorcycle=no), vd làn chính Xa lộ Hà Nội. */
    val motorcycleNo: Boolean = false,
    /** Thẻ maxspeed:lanes (vd "80|80|60"): giới hạn từng làn, trái → phải. */
    val maxspeedLanesRaw: String = ""
)

/** Giới hạn tốc độ người dùng tự sửa cho một đoạn đường (ưu tiên cao nhất). */
@androidx.room.Entity(tableName = "user_limits")
data class UserSpeedLimit(
    @androidx.room.PrimaryKey val osmId: Long,
    val limitKmh: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Ô khu vực đã tải dữ liệu, để biết khi nào cần tải lại (cập nhật dữ liệu). */
@androidx.room.Entity(tableName = "tiles")
data class TileInfo(
    @androidx.room.PrimaryKey val key: String,
    val fetchedAt: Long,
    val segmentCount: Int,
    val cameraCount: Int
)
