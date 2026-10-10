package com.speedalert.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kết quả định tuyến: toạ độ đường đi, quãng đường, thời gian ước tính, các bước rẽ. */
private data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<String>
)

/**
 * Màn hình bản đồ: OpenFreeMap (phủ Việt Nam, miễn phí), chấm vị trí hiện
 * tại TỰ ĐỘNG BÁM theo GPS (tạm tắt khi tìm kiếm/chỉ đường, bật lại bằng nút
 * "Vị trí của tôi"), thanh TÌM KIẾM ĐỊA CHỈ, nút CHỈ ĐƯỜNG (dùng OSRM - dịch
 * vụ định tuyến miễn phí, hồ sơ "ô tô" chung vì không có hồ sơ xe máy miễn
 * phí - CHƯA loại trừ cao tốc cấm xe máy, có cảnh báo rõ trong hộp thoại),
 * và các camera bắn tốc độ đã lưu.
 *
 * Chạm vào chấm vị trí mở bảng thông tin: tốc độ, giới hạn tốc độ, tên
 * đường, địa chỉ, và công tắc "Xem & tự động quay camera" (quay + lưu video
 * như camera hành trình ngay trong lúc xem).
 */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private lateinit var directionsButton: Button
    private val scope = CoroutineScope(Dispatchers.Main)
    private var myLocationMarker: Marker? = null
    private var searchResultMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var lastSearchedLatLng: LatLng? = null
    private var lastRouteError: String? = null

    // Khi true, bản đồ tự động bám theo vị trí GPS mỗi lần cập nhật. Tạm tắt
    // khi tìm kiếm/chỉ đường một nơi khác, bật lại qua nút "Vị trí của tôi".
    private var followEnabled = true

    private var liveCameraProvider: ProcessCameraProvider? = null
    private var liveRecording: Recording? = null

    private var pendingPreviewView: PreviewView? = null
    private var pendingSwitch: Switch? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val previewView = pendingPreviewView
        if (cameraGranted && audioGranted && previewView != null) {
            startLiveRecording(previewView)
        } else {
            pendingSwitch?.isChecked = false
            Toast.makeText(this, "Cần cấp quyền Camera và Micro để quay video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map)

        val searchInput = findViewById<EditText>(R.id.searchInput)
        val searchButton = findViewById<Button>(R.id.searchButton)
        val myLocationButton = findViewById<Button>(R.id.myLocationButton)
        directionsButton = findViewById(R.id.directionsButton)

        searchButton.setOnClickListener { performSearch(searchInput.text.toString()) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(searchInput.text.toString())
                true
            } else {
                false
            }
        }
        myLocationButton.setOnClickListener { recenterOnMyLocation() }
        directionsButton.setOnClickListener { requestDirections() }

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
                .zoom(16.0)
                .build()

            loadSavedCameras(map)
            observeMyLocation(map)

            map.setOnMarkerClickListener { marker ->
                if (marker.title == MY_LOCATION_TITLE) {
                    showLocationInfoDialog(marker.position.latitude, marker.position.longitude)
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Tìm địa chỉ (geocoding thuận) và đưa bản đồ tới đó, giống thanh tìm kiếm Google Maps. */
    private fun performSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || !::mapLibreMap.isInitialized) return

        scope.launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    if (!Geocoder.isPresent()) return@withContext null
                    val geocoder = Geocoder(this@MapActivity, Locale("vi", "VN"))
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }

            if (address == null) {
                Toast.makeText(this@MapActivity, "Không tìm thấy địa chỉ \"$query\"", Toast.LENGTH_SHORT).show()
                return@launch
            }

            followEnabled = false
            val latLng = LatLng(address.latitude, address.longitude)
            lastSearchedLatLng = latLng
            directionsButton.visibility = View.VISIBLE

            searchResultMarker?.let { mapLibreMap.removeMarker(it) }
            searchResultMarker = mapLibreMap.addMarker(
                MarkerOptions().position(latLng).title(query)
            )
            mapLibreMap.easeCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.0), 1000)
        }
    }

    /** Tính đường đi từ vị trí hiện tại tới điểm đã tìm kiếm (OSRM, hồ sơ ô tô chung). */
    private fun requestDirections() {
        val dest = lastSearchedLatLng ?: return
        val state = SpeedState.state.value
        val startLat = state.lastLat
        val startLon = state.lastLon
        if (startLat == null || startLon == null) {
            Toast.makeText(this, "Chưa xác định được vị trí hiện tại của bạn", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Đang tính đường đi...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = fetchRoute(startLat, startLon, dest.latitude, dest.longitude)
            if (result == null) {
                Toast.makeText(
                    this@MapActivity,
                    "Không tính được đường đi (${lastRouteError ?: "không rõ lý do"})",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            routePolyline?.let { mapLibreMap.removePolyline(it) }
            routePolyline = mapLibreMap.addPolyline(
                PolylineOptions().addAll(result.points).color(Color.parseColor("#1E88E5")).width(6f)
            )
            followEnabled = false

            val distanceKm = result.distanceMeters / 1000.0
            val durationMin = (result.durationSeconds / 60.0).toInt()
            val header = "Quãng đường: %.1f km - Thời gian ước tính: %d phút\n\n".format(distanceKm, durationMin) +
                "⚠ Tuyến đường tính theo dữ liệu đường ô tô chung (chưa có hồ sơ riêng cho xe máy miễn phí) " +
                "- có thể gồm đoạn cao tốc cấm xe máy, hãy chú ý biển báo thực tế.\n\n"

            AlertDialog.Builder(this@MapActivity)
                .setTitle("Chỉ đường")
                .setMessage(header + result.steps.joinToString("\n"))
                .setPositiveButton("Đóng", null)
                .show()
        }
    }

    /** Gọi API định tuyến công cộng OSRM (miễn phí, không cần API key). */
    private suspend fun fetchRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$startLon,$startLat;$endLon,$endLat?overview=full&geometries=geojson&steps=true"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "SpeedAlertApp/1.0 (Android; +https://github.com/QuangMinh02052004/speed-alert-app)")

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()

            Log.d("MapActivity", "OSRM url=$url code=$responseCode body=${responseText.take(300)}")

            if (responseCode !in 200..299) {
                lastRouteError = "HTTP $responseCode"
                return@withContext null
            }

            val json = JSONObject(responseText)
            val code = json.optString("code")
            if (code != "Ok") {
                lastRouteError = "$code: ${json.optString("message")}"
                return@withContext null
            }

            val route = json.getJSONArray("routes").getJSONObject(0)
            val distanceMeters = route.getDouble("distance")
            val durationSeconds = route.getDouble("duration")

            val coordsArray = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = mutableListOf<LatLng>()
            for (i in 0 until coordsArray.length()) {
                val pair = coordsArray.getJSONArray(i)
                points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
            }

            val steps = mutableListOf<String>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val stepsArray = legs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until stepsArray.length()) {
                    val step = stepsArray.getJSONObject(j)
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.optString("type")
                    val modifier = maneuver.optString("modifier", "")
                    val roadName = step.optString("name", "")
                    val stepDistance = step.optDouble("distance", 0.0)
                    steps.add(describeManeuver(type, modifier, roadName, stepDistance))
                }
            }

            RouteResult(points, distanceMeters, durationSeconds, steps)
        } catch (e: Exception) {
            Log.e("MapActivity", "Loi goi OSRM", e)
            lastRouteError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** Dịch kiểu rẽ của OSRM (tiếng Anh) sang mô tả tiếng Việt ngắn gọn. */
    private fun describeManeuver(type: String, modifier: String, roadName: String, distanceMeters: Double): String {
        val action = when (type) {
            "depart" -> "Xuất phát"
            "arrive" -> "Đã đến nơi"
            "turn" -> when (modifier) {
                "left" -> "Rẽ trái"
                "right" -> "Rẽ phải"
                "sharp left" -> "Rẽ gắt trái"
                "sharp right" -> "Rẽ gắt phải"
                "slight left" -> "Rẽ nhẹ trái"
                "slight right" -> "Rẽ nhẹ phải"
                "uturn" -> "Quay đầu"
                else -> "Rẽ"
            }
            "continue" -> "Đi thẳng"
            "merge" -> "Nhập làn"
            "roundabout", "rotary" -> "Vào vòng xuyến"
            "exit roundabout", "exit rotary" -> "Ra khỏi vòng xuyến"
            "fork" -> "Tại ngã ba, đi theo nhánh phù hợp"
            "end of road" -> "Cuối đường, rẽ theo hướng phù hợp"
            "new name" -> "Tiếp tục đi"
            else -> "Đi tiếp"
        }
        val roadPart = if (roadName.isNotBlank()) " vào $roadName" else ""
        val distPart = if (distanceMeters > 0) " (${distanceMeters.toInt()}m)" else ""
        return "• $action$roadPart$distPart"
    }

    /** Bật lại chế độ tự động bám vị trí và đưa camera về ngay vị trí hiện tại. */
    private fun recenterOnMyLocation() {
        followEnabled = true
        if (!::mapLibreMap.isInitialized) return
        val state = SpeedState.state.value
        val lat = state.lastLat
        val lon = state.lastLon
        if (lat != null && lon != null) {
            mapLibreMap.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0), 800)
        }
    }

    /** Theo dõi vị trí hiện tại; tự động đưa camera bám theo nếu followEnabled. */
    private fun observeMyLocation(map: MapLibreMap) {
        scope.launch {
            SpeedState.state.collect { state ->
                val lat = state.lastLat
                val lon = state.lastLon
                if (lat != null && lon != null) {
                    val latLng = LatLng(lat, lon)
                    myLocationMarker?.let { map.removeMarker(it) }
                    myLocationMarker = map.addMarker(
                        MarkerOptions().position(latLng).title(MY_LOCATION_TITLE)
                    )
                    if (followEnabled) {
                        map.easeCamera(CameraUpdateFactory.newLatLng(latLng), 800)
                    }
                }
            }
        }
    }

    /** Bảng thông tin: tốc độ, giới hạn, đoạn đường, địa chỉ + công tắc xem & quay camera. */
    private fun showLocationInfoDialog(lat: Double, lon: Double) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location_info, null)
        val infoSpeed = view.findViewById<TextView>(R.id.infoSpeed)
        val infoLimit = view.findViewById<TextView>(R.id.infoLimit)
        val infoRoad = view.findViewById<TextView>(R.id.infoRoad)
        val infoAddress = view.findViewById<TextView>(R.id.infoAddress)
        val liveSwitch = view.findViewById<Switch>(R.id.liveCameraSwitch)
        val livePreview = view.findViewById<PreviewView>(R.id.liveCameraPreview)

        val state = SpeedState.state.value
        infoSpeed.text = "Tốc độ hiện tại: ${state.currentSpeedKmh} km/h"
        infoLimit.text = if (state.speedLimitKmh != null)
            "Giới hạn tốc độ: ${state.speedLimitKmh} km/h"
        else "Giới hạn tốc độ: chưa có dữ liệu"
        infoRoad.text = if (state.roadName.isNotBlank()) "Đoạn đường: ${state.roadName}" else "Đoạn đường: không rõ"
        infoAddress.text = "Địa chỉ: đang tra cứu..."

        liveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                livePreview.visibility = View.VISIBLE
                val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                val allGranted = needed.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    startLiveRecording(livePreview)
                } else {
                    pendingPreviewView = livePreview
                    pendingSwitch = liveSwitch
                    cameraPermissionLauncher.launch(needed.toTypedArray())
                }
            } else {
                livePreview.visibility = View.GONE
                stopLiveRecording()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Vị trí của bạn")
            .setView(view)
            .setPositiveButton("Đóng") { _, _ -> stopLiveRecording() }
            .setOnCancelListener { stopLiveRecording() }
            .show()

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
            infoAddress.text = "Địa chỉ: ${addressText ?: "không lấy được (mất mạng hoặc máy không hỗ trợ)"}"
        }
    }

    /** Bật camera sau, xem trực tiếp + tự động quay và lưu video (như Camera hành trình). */
    private fun startLiveRecording(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            liveCameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder().build()
            val videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "hanhtrinh_dinhvi_$timestamp")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedAlert")
                }
                val outputOptions = MediaStoreOutputOptions.Builder(
                    contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                liveRecording = videoCapture.output
                    .prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize && !event.hasError()) {
                            Toast.makeText(this, "Đã lưu video vào Movies/SpeedAlert", Toast.LENGTH_SHORT).show()
                        }
                    }
            } catch (e: Exception) {
                // Camera có thể đang bận (vd. đang quay ở màn hình Camera hành trình) - bỏ qua.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopLiveRecording() {
        liveRecording?.stop()
        liveRecording = null
        liveCameraProvider?.unbindAll()
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
        stopLiveRecording()
        scope.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        private const val DEFAULT_LAT = 10.7769
        private const val DEFAULT_LON = 106.7009
        private const val MY_LOCATION_TITLE = "Vị trí của tôi"
    }
}
