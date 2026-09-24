package com.speedalert.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.data.CameraLocation
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Màn hình bản đồ: hiển thị bản đồ nền (style demo miễn phí của MapLibre - chỉ
 * để test, nên thay bằng nguồn tile chi tiết hơn cho VN khi triển khai thật),
 * và cho người dùng chạm vào bản đồ để tự đánh dấu vị trí camera bắn tốc độ
 * (lưu local qua Room, dùng chung DB với SpeedRepository).
 *
 * Dùng API Marker/MarkerOptions kiểu cũ (đã deprecated từ MapLibre 7.0 nhưng
 * vẫn hoạt động) để đơn giản hoá - không cần thêm plugin annotation/ảnh icon
 * riêng. Có thể nâng cấp lên SymbolManager sau nếu cần tuỳ biến icon.
 */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle("https://demotiles.maplibre.org/style.json")

            val current = SpeedState.state.value
            val startLat = current.lastLat ?: DEFAULT_LAT
            val startLon = current.lastLon ?: DEFAULT_LON
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(startLat, startLon))
                .zoom(14.0)
                .build()

            loadSavedCameras(map)

            map.addOnMapClickListener { point ->
                saveCameraLocation(point.latitude, point.longitude)
                map.addMarker(MarkerOptions().position(point).title("Camera bắn tốc độ"))
                true
            }
        }
    }

    private fun loadSavedCameras(map: MapLibreMap) {
        scope.launch {
            val cameras = AppDatabase.getInstance(applicationContext).cameraDao().getAll()
            cameras.forEach { cam ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(cam.lat, cam.lon))
                        .title("Camera bắn tốc độ")
                )
            }
        }
    }

    private fun saveCameraLocation(lat: Double, lon: Double) {
        scope.launch {
            AppDatabase.getInstance(applicationContext).cameraDao()
                .insert(CameraLocation(lat = lat, lon = lon))
        }
    }

    override fun onStart() {
        super.onStart(); mapView.onStart()
    }

    override fun onResume() {
        super.onResume(); mapView.onResume()
    }

    override fun onPause() {
        super.onPause(); mapView.onPause()
    }

    override fun onStop() {
        super.onStop(); mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory(); mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy(); mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        // Toạ độ mặc định khi chưa có vị trí GPS nào - trung tâm TP.HCM
        private const val DEFAULT_LAT = 10.7769
        private const val DEFAULT_LON = 106.7009
    }
}
