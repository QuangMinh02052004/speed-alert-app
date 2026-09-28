package com.speedalert.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.speedalert.app.data.RoadSegment
import com.speedalert.app.data.SpeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var repository: SpeedRepository
    private val soundAlertManager = SoundAlertManager()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Nhớ đoạn đường khớp gần nhất lần trước - dùng khi vị trí hiện tại có độ
    // chính xác kém (giữ nguyên thay vì nhảy sang giới hạn tốc độ sai).
    private var lastKnownSegment: RoadSegment? = null

    // Trung bình trượt có trọng số (EMA) cho tốc độ - giảm giật/nhảy số do
    // nhiễu GPS tức thời, mà vẫn phản ứng đủ nhanh khi tốc độ thật sự đổi.
    private var smoothedSpeedKmh: Double? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // Bỏ qua các fix không có dữ liệu tốc độ (vd. fix dự phòng từ mạng/wifi
            // khi mất tín hiệu GPS thật sự) - tránh hiển thị tốc độ sai/0 giả.
            if (!location.hasSpeed()) return

            val rawSpeedKmh = location.speed * 3.6 // m/s -> km/h

            // Độ lệch chuẩn ước tính của số đo tốc độ (m/s), có từ Android 8.0 (API 26).
            // Giá trị càng nhỏ càng đáng tin. Máy không hỗ trợ thì giả định mức trung bình.
            val speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else {
                1.5f
            }

            // Độ tin cậy càng thấp (accuracy càng lớn) thì tin vào số đo mới càng ít.
            val alpha = when {
                speedAccuracy <= 0.5f -> 0.6
                speedAccuracy <= 1.5f -> 0.4
                else -> 0.2
            }
            val previousSmoothed = smoothedSpeedKmh
            val smoothed = if (previousSmoothed == null) rawSpeedKmh
            else alpha * rawSpeedKmh + (1 - alpha) * previousSmoothed
            smoothedSpeedKmh = smoothed
            val speedKmh = smoothed.toInt()

            // Vị trí (không phải tốc độ) có độ chính xác kém - vd. dưới cầu vượt, giữa
            // nhà cao tầng - thì giữ nguyên đoạn đường đã khớp lần trước thay vì tra lại,
            // tránh nhảy nhầm sang giới hạn tốc độ của đường khác.
            val positionIsGoodEnough = location.accuracy <= 30f

            scope.launch {
                val nearest = if (positionIsGoodEnough) {
                    repository.findNearestSpeedLimit(location.latitude, location.longitude).also {
                        lastKnownSegment = it
                    }
                } else {
                    lastKnownSegment
                }

                val nearbyCamera = repository.findNearbyCamera(location.latitude, location.longitude)
                val isOverLimit = nearest != null && speedKmh > nearest.speedKmh
                val isNearCamera = nearbyCamera != null

                SpeedState.update(
                    currentSpeedKmh = speedKmh,
                    speedLimitKmh = nearest?.speedKmh,
                    roadName = nearest?.name.orEmpty(),
                    lat = location.latitude,
                    lon = location.longitude,
                    nearCamera = isNearCamera
                )
                soundAlertManager.alertIfNeeded(isOverLimit || isNearCamera)
                updateNotification(speedKmh, nearest?.speedKmh, isNearCamera)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        repository = SpeedRepository(applicationContext)
        scope.launch { repository.ensureSeeded() }
        startForeground(NOTIFICATION_ID, buildNotification(0, null, false))
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L).build()
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun buildNotification(speedKmh: Int, limitKmh: Int?, nearCamera: Boolean): Notification {
        val channelId = "speed_alert_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Cảnh báo tốc độ", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val parts = mutableListOf("Tốc độ: $speedKmh km/h")
        if (limitKmh != null) parts.add("Giới hạn: $limitKmh km/h")
        if (nearCamera) parts.add("⚠ Camera phía trước")
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Đang theo dõi tốc độ")
            .setContentText(parts.joinToString("  —  "))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(speedKmh: Int, limitKmh: Int?, nearCamera: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(speedKmh, limitKmh, nearCamera))
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        soundAlertManager.release()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
