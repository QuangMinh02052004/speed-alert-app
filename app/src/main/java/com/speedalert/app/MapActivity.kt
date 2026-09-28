package com.speedalert.app

import android.app.AlertDialog
import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.data.CameraLocation
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale

/**
 * Màn hình bản đồ: hiển thị bản đồ nền (OpenFreeMap - vector tile miễn phí từ
 * dữ liệu OpenStreetMap, phủ đầy đủ Việt Nam, không cần API key, không giới
 * hạn lượt gọi - xem https://openfreemap.org), chấm đánh dấu vị trí hiện tại
 * (tự cập nhật theo dữ liệu GPS từ LocationTrackingService đang chạy nền),
 * và cho người dùng chạm vào bản đồ để tự đánh dấu vị trí camera bắn tốc độ
 * (lưu local qua Room, dùng chung DB với SpeedRepository).
 *
 * Dùng API Marker/MarkerOptions kiểu cũ (đã deprecated từ MapLibre 7.0 nhưng
 * vẫn hoạt động) để đơn giản hoá - không cần thêm plugin annotation/ảnh icon
 * riêng. Vì vậy chấm vị trí hiện tại và camera dùng chung icon mặc định,
 * chỉ khác nhau ở tiêu đề/hành vi khi chạm vào - có thể nâng cấp icon riêng
 * sau nếu cần.
 */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private val scope = CoroutineScope(Dispatchers.Main)
    private var myLocationMarker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle("https://tiles.openfreemap.org/styles/bright")

            val current = SpeedState.state.value
            val startLat = current.lastLat ?: DEFAULT_LAT
            val startLon = current.lastLon ?: DEFAULT_LON
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(startLat, startLon))
                .zoom(14.0)
                .build()

            loadSavedCameras(map)
            observeMyLocation(map)

            map.addOnMapClickListener { point ->
                saveCameraLocation(point.latitude, point.longitude)
                map.addMarker(MarkerOptions().position(point).title("Camera bắn tốc độ"))
                true
            }

            map.setOnMarkerClickListener { marker ->
                if (marker.title == MY_LOCATION_TITLE) {
                    showAddressForLocation(marker.position.latitude, marker.position.longitude)
                    true // đã xử lý - không hiện info window mặc định
                } else {
                    false // để hành vi mặc định (hiện tiêu đề) cho các marker khác
                }
            }
        }
    }

    /** Theo dõi vị trí hiện tại (từ Service chạy nền) và cập nhật chấm đánh dấu trên bản đồ. */
    private fun observeMyLocation(map: MapLibreMap) {
        scope.launch {
            SpeedState.state.collect { state ->
                val lat = state.lastLat
                val lon = state.lastLon
                if (lat != null && lon != null) {
                    myLocationMarker?.let { map.removeMarker(it) }
                    myLocationMarker = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(lat, lon))
                            .title(MY_LOCATION_TITLE)
                    )
                }
            }
        }
    }

    /** Tra ngược toạ độ ra địa chỉ (Geocoder có sẵn trong Android) và hiện hộp thoại. */
    private fun showAddressForLocation(lat: Double, lon: Double) {
        scope.launch {
            val addressText = withContext(Dispatchers.IO) {
                try {
                    if (!Geocoder.isPresent()) return@withContext null
                    val geocoder = Geocoder(this@MapActivity, Locale("vi", "VN"))
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocation(lat, lon, 1)
                    results?.firstOrNull()?.getAddressLine(0)
                } catch (e: Exception) {
                    null
                }
            }
            AlertDialog.Builder(this@MapActivity)
                .setTitle("Vị trí của bạn")
                .setMessage(addressText ?: "Không lấy được địa chỉ (có thể do mất mạng hoặc máy không hỗ trợ tra cứu địa chỉ).")
                .setPositiveButton("Đóng", null)
                .show()
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
        super.onDestroy()
        mapView.onDestroy()
        scope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        // Toạ độ mặc định khi chưa có vị trí GPS nào - trung tâm TP.HCM
        private const val DEFAULT_LAT = 10.7769
        private const val DEFAULT_LON = 106.7009
        private const val MY_LOCATION_TITLE = "Vị trí của tôi"
    }
}
