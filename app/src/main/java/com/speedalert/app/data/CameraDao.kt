package com.speedalert.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CameraDao {
    @Insert
    suspend fun insertAll(cameras: List<CameraLocation>)

    @Query("SELECT * FROM camera_locations")
    suspend fun getAll(): List<CameraLocation>

    @Query("SELECT * FROM camera_locations WHERE tileKey IN (:keys)")
    suspend fun getByTiles(keys: List<String>): List<CameraLocation>

    @Query("DELETE FROM camera_locations WHERE tileKey = :key")
    suspend fun deleteTile(key: String)
}

@Dao
interface RoadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<RoadSegment>)

    @Query("SELECT * FROM road_segments WHERE tileKey IN (:keys)")
    suspend fun getByTiles(keys: List<String>): List<RoadSegment>

    @Query("DELETE FROM road_segments WHERE tileKey = :key")
    suspend fun deleteTile(key: String)

    @Query("SELECT * FROM tiles WHERE `key` = :key")
    suspend fun getTile(key: String): TileInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTile(tile: TileInfo)

    @Query("SELECT COUNT(*) FROM tiles")
    suspend fun tileCount(): Int

    @Query("DELETE FROM tiles")
    suspend fun clearTiles()

    @Query("DELETE FROM road_segments")
    suspend fun clearSegments()

    /** Thay toàn bộ dữ liệu một ô trong một giao dịch (không để lại dữ liệu nửa vời). */
    @Transaction
    suspend fun replaceTile(key: String, segments: List<RoadSegment>, tile: TileInfo) {
        deleteTile(key)
        if (segments.isNotEmpty()) insertAll(segments)
        upsertTile(tile)
    }
}

@Dao
interface UserLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<UserSpeedLimit>)

    @Query("SELECT * FROM user_limits")
    suspend fun getAll(): List<UserSpeedLimit>

    @Query("DELETE FROM user_limits WHERE osmId IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
