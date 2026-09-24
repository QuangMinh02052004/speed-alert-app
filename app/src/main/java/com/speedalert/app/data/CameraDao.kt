package com.speedalert.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CameraDao {
    @Insert
    suspend fun insert(camera: CameraLocation): Long

    @Query("SELECT * FROM camera_locations")
    suspend fun getAll(): List<CameraLocation>
}
