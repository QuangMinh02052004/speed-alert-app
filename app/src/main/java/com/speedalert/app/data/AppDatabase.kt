package com.speedalert.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [RoadSegment::class, CameraLocation::class, TileInfo::class, UserSpeedLimit::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roadDao(): RoadDao
    abstract fun cameraDao(): CameraDao
    abstract fun userLimitDao(): UserLimitDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "speed_alert.db"
                )
                    // Dữ liệu chỉ là bộ đệm tải lại được từ OpenStreetMap, nên đổi
                    // cấu trúc thì xoá và tải lại là an toàn.
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
