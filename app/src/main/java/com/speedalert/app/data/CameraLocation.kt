package com.speedalert.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Vị trí camera phạt nguội / bắn tốc độ. Lấy tự động từ OpenStreetMap
 * (highway=speed_camera) theo từng ô khu vực, gắn tileKey để làm mới được.
 */
@Entity(tableName = "camera_locations", indices = [Index("tileKey")])
data class CameraLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val osmId: Long = 0,
    val tileKey: String = "",
    /** Giới hạn tốc độ ghi trên camera (thẻ maxspeed), 0 nếu không rõ. */
    val maxspeed: Int = 0
)
