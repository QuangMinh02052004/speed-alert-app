package com.speedalert.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RoadSegment::class, CameraLocation::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun speedLimitDao(): SpeedLimitDao
    abstract fun cameraDao(): CameraDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "speed_alert.db"
                )
                    // MVP: chưa viết Migration cụ thể, chấp nhận xoá DB cũ khi đổi schema.
                    // Chưa có người dùng thật nên không mất dữ liệu quan trọng ở giai đoạn này.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
