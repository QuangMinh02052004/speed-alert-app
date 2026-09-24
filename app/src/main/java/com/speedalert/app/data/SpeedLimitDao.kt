package com.speedalert.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpeedLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<RoadSegment>)

    @Query("SELECT COUNT(*) FROM road_segments")
    suspend fun count(): Int

    @Query("SELECT * FROM road_segments")
    suspend fun getAll(): List<RoadSegment>
}
