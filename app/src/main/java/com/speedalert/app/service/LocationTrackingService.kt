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

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val speedKmh = (location.speed * 3.6).toInt() // m/s -> km/h
            scope.launch {
                val nearest = repository.findNearestSpeedLimit(location.latitude, location.longitude)
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
