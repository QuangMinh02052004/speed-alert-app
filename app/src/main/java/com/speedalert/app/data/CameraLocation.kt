package com.speedalert.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Vị trí camera bắn tốc độ do người dùng tự đánh dấu trên bản đồ (crowdsource). */
@Entity(tableName = "camera_locations")
data class CameraLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val createdAt: Long = System.currentTimeMillis()
)
