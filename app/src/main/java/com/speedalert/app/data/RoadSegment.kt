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
    /** Suy ra lúc tải: đoạn đường nằm trong khu vực đông dân cư hay không. */
    val urban: Boolean,
    val geometry: List<GeoPoint>
)

/** Ô khu vực đã tải dữ liệu, để biết khi nào cần tải lại (cập nhật dữ liệu). */
@androidx.room.Entity(tableName = "tiles")
data class TileInfo(
    @androidx.room.PrimaryKey val key: String,
    val fetchedAt: Long,
    val segmentCount: Int,
    val cameraCount: Int
)
