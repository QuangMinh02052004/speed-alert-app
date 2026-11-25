package com.speedalert.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.speedalert.app.core.RoadDataManager
import com.speedalert.app.core.UserLimits
import com.speedalert.app.data.GeoPoint
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hộp thoại sửa giới hạn tốc độ theo biển báo thật. Áp cho đoạn đang đi và các
 * đoạn cùng tên đường trong bán kính 1,5 km; lưu vĩnh viễn trong máy.
 */
object LimitCorrection {
    private val VALUES = listOf(30, 40, 50, 60, 70, 80)

    fun show(activity: Activity, scope: LifecycleCoroutineScope) {
        val s = SpeedState.state.value
        val osmId = s.matchedOsmId
        val lat = s.lastLat
        val lon = s.lastLon
        if (osmId == null || lat == null || lon == null) {
            Toast.makeText(activity, "Chưa xác định được đoạn đường đang đi", Toast.LENGTH_SHORT).show()
            return
        }
        val road = s.roadName.ifBlank { "đoạn đường này" }
        val current = s.speedLimitKmh?.let { "$it km/h" } ?: "chưa có"
        val labels = VALUES.map { "$it km/h" } + "Xoá giá trị tôi đã sửa"
        AlertDialog.Builder(activity)
            .setTitle("Biển tốc độ thật trên $road?")
            .setItems(labels.toTypedArray()) { _, which ->
                val value = VALUES.getOrNull(which)
                scope.launch {
                    val ids = withContext(Dispatchers.Default) {
                        RoadDataManager.current?.sameRoadIds(osmId, GeoPoint(lat, lon)) ?: listOf(osmId)
                    }
                    withContext(Dispatchers.IO) { UserLimits.set(activity.applicationContext, ids, value) }
                    Toast.makeText(
                        activity,
                        if (value != null) "Đã lưu $value km/h cho $road (${ids.size} đoạn quanh đây)"
                        else "Đã xoá, dùng lại dữ liệu bản đồ/luật cho $road",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Huỷ", null)
            .setMessage(null)
            .show()
        Toast.makeText(activity, "Đang dùng: $current", Toast.LENGTH_SHORT).show()
    }
}
