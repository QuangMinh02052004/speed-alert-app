package com.speedalert.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.speedalert.app.MapActivity
import com.speedalert.app.core.AppSettings
import com.speedalert.app.core.GeoUtils
import com.speedalert.app.core.NavigationManager
import com.speedalert.app.core.RoadDataManager
import com.speedalert.app.core.RoutingClient
import com.speedalert.app.core.SpeedLimitRules
import com.speedalert.app.core.VoiceAnnouncer
import com.speedalert.app.data.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Service chạy nền (foreground) — trái tim của app:
 * GPS độ chính xác cao 1 giây/lần → lọc & làm mượt tốc độ → khớp vào đường
 * → giới hạn tốc độ theo loại xe → cảnh báo vượt tốc độ / camera phạt nguội
 * bằng giọng nói → dẫn đường từng bước + tự tính lại tuyến khi đi lệch.
 */
class LocationTrackingService : Service() {

    companion object {
        const val ACTION_STOP = "com.speedalert.app.STOP"
        const val ACTION_REFRESH_DATA = "com.speedalert.app.REFRESH_DATA"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speed_alert_channel"

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, LocationTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun refreshData(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_REFRESH_DATA)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var roadData: RoadDataManager
    private lateinit var voice: VoiceAnnouncer
    private val soundAlertManager = SoundAlertManager()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** Xử lý từng vị trí lần lượt theo thứ tự (Mutex công bằng = FIFO). */
    private val processMutex = Mutex()

    private var smoothedSpeedKmh = 0.0
    private var lastLocation: Location? = null
    private var lastBearing: Float? = null
    private var lastOverLimitVoiceAt = 0L
    private val alertedCameras = HashSet<Long>()
    private var lastNotificationAt = 0L
    private var updatesStarted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            scope.launch { processMutex.withLock { handleLocation(location) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.load(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        roadData = RoadDataManager(applicationContext, scope)
        voice = VoiceAnnouncer(this)
        createChannel()
        startInForeground(buildNotification("Đang chờ tín hiệu GPS..."))
        SpeedState.setServiceRunning(true)

        scope.launch {
            roadData.status.collectLatest { SpeedState.setStatus(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NavigationManager.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_DATA -> {
                val loc = lastLocation
                if (loc != null) {
                    roadData.forceRefresh(loc.latitude, loc.longitude) { ok ->
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(
                                this@LocationTrackingService,
                                if (ok) "Đã cập nhật dữ liệu đường khu vực" else "Không tải được dữ liệu, kiểm tra mạng",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        startLocationUpdates()
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLocationUpdates() {
        if (updatesStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            updatesStarted = true
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private suspend fun handleLocation(location: Location) {
        val prev = lastLocation
        val point = GeoPoint(location.latitude, location.longitude)

        // ---- 1. Tốc độ: ưu tiên tốc độ Doppler của chip GPS, thiếu thì tự tính ----
        var rawMps: Double? = if (location.hasSpeed()) location.speed.toDouble() else null
        if (rawMps == null && prev != null) {
            val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt in 0.5..5.0 && location.accuracy < 25 && prev.accuracy < 25) {
                rawMps = GeoUtils.haversine(prev.latitude, prev.longitude, location.latitude, location.longitude) / dt
            }
        }
        val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else 1.5f

        if (rawMps != null) {
            val rawKmh = if (rawMps < 0.9) 0.0 else rawMps * 3.6 // đứng yên: GPS hay "trôi" vài km/h
            val alpha = when {
                speedAcc <= 0.5f -> 0.75
                speedAcc <= 1.5f -> 0.55
                else -> 0.3
            }
            smoothedSpeedKmh = if (rawKmh == 0.0 && smoothedSpeedKmh < 5) 0.0
            else alpha * rawKmh + (1 - alpha) * smoothedSpeedKmh
        }
        val speedKmh = smoothedSpeedKmh.toInt()

        // ---- 2. Hướng di chuyển (chỉ tin khi đang chạy, đứng yên hướng nhảy loạn) ----
        if (location.hasBearing() && (rawMps ?: 0.0) > 1.5) lastBearing = location.bearing
        val bearing = lastBearing

        lastLocation = location
        val gpsWeak = location.accuracy > 30

        // ---- 3. Dữ liệu đường quanh vị trí + khớp đường ----
        roadData.ensureArea(location.latitude, location.longitude)
        val match = if (!gpsWeak || location.accuracy < 60) roadData.match(point, bearing, smoothedSpeedKmh) else null
        val vehicle = AppSettings.vehicle.value
        val limitInfo = match?.let { SpeedLimitRules.resolve(it.segment, vehicle) }
        val limit = limitInfo?.limitKmh
        val overLimit = limit != null && speedKmh > limit + 2

        // ---- 4. Camera phạt nguội phía trước ----
        var nearestCamDist: Int? = null
        for (cam in roadData.cameras) {
            val d = GeoUtils.haversine(location.latitude, location.longitude, cam.lat, cam.lon)
            if (d > 800) {
                alertedCameras.remove(cam.id)
                continue
            }
            val ahead = bearing == null || speedKmh < 8 ||
                GeoUtils.angleDiff(bearing.toDouble(), GeoUtils.bearing(point, GeoPoint(cam.lat, cam.lon))) < 45
            if (!ahead || d > 600) continue
            if (nearestCamDist == null || d < nearestCamDist) nearestCamDist = d.toInt()
            if (d < 500 && alertedCameras.add(cam.id)) {
                val camLimit = if (cam.maxspeed > 0) ", giới hạn ${cam.maxspeed} ki lô mét giờ" else ""
                announce("Phía trước ${NavigationManager.roundDistance(d)} có camera phạt nguội$camLimit", urgent = true)
            }
        }

        // ---- 5. Cảnh báo vượt tốc độ ----
        if (overLimit) {
            val now = System.currentTimeMillis()
            if (now - lastOverLimitVoiceAt > 20_000) {
                lastOverLimitVoiceAt = now
                announce("Bạn đang chạy quá tốc độ, giới hạn $limit ki lô mét giờ", urgent = true)
            }
            soundAlertManager.alertIfNeeded(!(AppSettings.voiceEnabled.value && voice.available))
        }

        // ---- 6. Dẫn đường ----
        val nav = NavigationManager.state.value
        if (nav.active) {
            val announcements = NavigationManager.onLocation(point, location.accuracy, rawMps ?: 0.0)
            announcements.forEach { announce(it.text, it.urgent) }
            if (NavigationManager.shouldReroute()) reroute(point, bearing)
        }

        val roadName = when {
            match == null -> ""
            match.segment.name.isNotBlank() -> match.segment.name
            else -> roadTypeLabel(match.segment.highwayType)
        }

        SpeedState.set(
            SpeedState.state.value.copy(
                currentSpeedKmh = speedKmh,
                speedLimitKmh = limit,
                limitFromSign = limitInfo?.fromSign ?: false,
                roadName = roadName,
                roadNote = limitInfo?.note ?: "",
                lastLat = location.latitude,
                lastLon = location.longitude,
                bearing = bearing,
                accuracyM = location.accuracy,
                nearCamera = nearestCamDist != null,
                cameraDistanceM = nearestCamDist,
                overLimit = overLimit,
                gpsWeak = gpsWeak,
                location = location
            )
        )

        val now = System.currentTimeMillis()
        if (now - lastNotificationAt > 2000) {
            lastNotificationAt = now
            val parts = mutableListOf("$speedKmh km/h")
            if (limit != null) parts.add("Giới hạn $limit")
            if (roadName.isNotBlank()) parts.add(roadName)
            if (nearestCamDist != null) parts.add("Camera ${nearestCamDist}m")
            updateNotification(parts.joinToString("  •  "))
        }
    }

    private fun roadTypeLabel(type: String): String = when (type) {
        "motorway" -> "Đường cao tốc"
        "trunk" -> "Quốc lộ"
        "primary" -> "Đường chính"
        "secondary" -> "Đường tỉnh"
        "residential", "living_street" -> "Đường dân sinh"
        else -> "Đường không tên"
    }

    private fun reroute(from: GeoPoint, bearing: Float?) {
        val dest = NavigationManager.state.value.destination ?: return
        NavigationManager.setRerouting(true)
        scope.launch(Dispatchers.IO) {
            val route = RoutingClient.route(from, dest, AppSettings.vehicle.value, bearing)
            if (route != null) {
                NavigationManager.replaceRoute(route)
                announce("Đã tính lại đường đi")
            } else {
                NavigationManager.setRerouting(false)
            }
        }
    }

    private fun announce(text: String, urgent: Boolean = false) {
        if (AppSettings.voiceEnabled.value && voice.available) {
            voice.speak(text, urgent)
        } else {
            soundAlertManager.alertIfNeeded(urgent)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Cảnh báo tốc độ", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Alert đang chạy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stopIntent)
            .build()
    }

    private suspend fun updateNotification(text: String) = withContext(Dispatchers.Main) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        soundAlertManager.release()
        voice.shutdown()
        scope.cancel()
        SpeedState.setServiceRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
