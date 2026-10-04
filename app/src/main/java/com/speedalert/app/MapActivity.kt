package com.speedalert.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
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
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình bản đồ: hiển thị bản đồ nền (OpenFreeMap, phủ đầy đủ Việt Nam,
 * miễn phí không cần API key), chấm vị trí hiện tại TỰ ĐỘNG BÁM theo GPS,
 * và các camera bắn tốc độ đã lưu trước đó.
 *
 * Chạm vào chấm vị trí mở bảng thông tin: tốc độ hiện tại, giới hạn tốc độ,
 * tên đường, địa chỉ, và công tắc "Xem & tự động quay camera" - khi bật sẽ
 * hoạt động như camera hành trình thực sự: quay + lưu video (qua MediaStore,
 * vào Movies/SpeedAlert) ngay trong lúc xem, không chỉ xem suông.
 */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapLibreMap: MapLibreMap
    private val scope = CoroutineScope(Dispatchers.Main)
    private var myLocationMarker: Marker? = null

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

    /** Theo dõi vị trí hiện tại và TỰ ĐỘNG đưa camera bản đồ bám theo (auto-follow). */
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
                    map.easeCamera(CameraUpdateFactory.newLatLng(latLng), 800)
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
