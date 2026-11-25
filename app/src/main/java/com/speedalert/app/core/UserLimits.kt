package com.speedalert.app.core

import android.content.Context
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.data.UserSpeedLimit
import java.util.concurrent.ConcurrentHashMap

/** Giới hạn tốc độ do người dùng tự sửa theo biển báo thật (ưu tiên cao nhất). */
object UserLimits {
    private val map = ConcurrentHashMap<Long, Int>()
    @Volatile private var loaded = false

    suspend fun load(context: Context) {
        if (loaded) return
        AppDatabase.getInstance(context).userLimitDao().getAll().forEach { map[it.osmId] = it.limitKmh }
        loaded = true
    }

    fun get(osmId: Long): Int? = map[osmId]

    /** limit = null để xoá, quay về dữ liệu bản đồ / luật. */
    suspend fun set(context: Context, osmIds: List<Long>, limit: Int?) {
        val dao = AppDatabase.getInstance(context).userLimitDao()
        if (limit == null) {
            osmIds.forEach { map.remove(it) }
            dao.delete(osmIds)
        } else {
            osmIds.forEach { map[it] = limit }
            dao.upsertAll(osmIds.map { UserSpeedLimit(it, limit) })
        }
    }
}
