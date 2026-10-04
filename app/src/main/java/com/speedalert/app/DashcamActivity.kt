package com.speedalert.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình camera hành trình (dashcam): quay video trong lúc lái xe.
 *
 * Nếu máy hỗ trợ Concurrent Camera (Android 11+ / API 30+, và thiết bị thực
 * sự hỗ trợ về phần cứng - không phải máy nào cũng có) thì quay ĐỒNG THỜI cả
 * camera sau (hướng ra đường) và camera trước (hướng vào người lái), lưu
 * thành 2 file video riêng biệt. Ghép 2 luồng thành 1 khung hình (composition
 * mode) chưa làm ở bản này vì API đó còn khá mới/ít ổn định hơn.
 *
 * Nếu máy không hỗ trợ, tự động chuyển sang chỉ quay camera sau.
 *
 * Video lưu qua MediaStore vào thư viện video công khai của máy (thư mục
 * Movies/SpeedAlert) - hiện trong Gallery/Files như video quay bằng app
 * Camera bình thường, không phải thư mục riêng ẩn của app.
 *
 * Có lớp hiển thị ngày giờ/tốc độ/toạ độ đè lên màn hình trong lúc quay,
 * giống các dashcam thật - nhưng hiện ở MÀN HÌNH, chưa in cứng vào file
 * video (đó là việc xử lý video phức tạp hơn, chưa làm ở bản này).
 */
class DashcamActivity : ComponentActivity() {

    private lateinit var previewBack: PreviewView
    private lateinit var previewFront: PreviewView
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView
    private lateinit var overlayInfo: TextView

    private lateinit var cameraProvider: ProcessCameraProvider
    private var videoCaptureBack: VideoCapture<Recorder>? = null
    private var videoCaptureFront: VideoCapture<Recorder>? = null
    private var recordingBack: Recording? = null
    private var recordingFront: Recording? = null
    private var isDualMode = false
    private var isRecording = false

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            startCamera()
        } else {
            statusText.text = "Cần cấp quyền Camera và Micro để dùng tính năng camera hành trình"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam)

        previewBack = findViewById(R.id.previewBack)
        previewFront = findViewById(R.id.previewFront)
        recordButton = findViewById(R.id.recordButton)
        statusText = findViewById(R.id.statusText)
        overlayInfo = findViewById(R.id.overlayInfo)

        recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        requestPermissionsAndStart()
        startOverlayUpdates()
    }

    /** Cập nhật mỗi giây: ngày giờ, tốc độ hiện tại, toạ độ - hiện đè lên màn hình quay. */
    private fun startOverlayUpdates() {
        activityScope.launch {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("vi", "VN"))
            while (isActive) {
                val state = SpeedState.state.value
                val now = dateFormat.format(Date())
                val locationText = if (state.lastLat != null && state.lastLon != null) {
                    String.format(Locale.US, "%.5f, %.5f", state.lastLat, state.lastLon)
                } else {
                    "chưa có vị trí"
                }
                overlayInfo.text = "$now\nTốc độ: ${state.currentSpeedKmh} km/h\nToạ độ: $locationText"
                delay(1000)
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startCamera()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            setupConcurrentOrFallback()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupConcurrentOrFallback() {
        var backSelector: CameraSelector? = null
        var frontSelector: CameraSelector? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (cameraInfos in cameraProvider.availableConcurrentCameraInfos) {
                val back = cameraInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val front = cameraInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                if (back != null && front != null) {
                    backSelector = back.cameraSelector
                    frontSelector = front.cameraSelector
                    break
                }
            }
        }

        cameraProvider.unbindAll()

        if (backSelector != null && frontSelector != null) {
            tryBindDualCamera(backSelector, frontSelector)
        } else {
            fallbackSingleCamera()
        }
    }

    private fun tryBindDualCamera(backSelector: CameraSelector, frontSelector: CameraSelector) {
        val previewUseBack = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewBack.surfaceProvider) }
        val recorderBack = Recorder.Builder().build()
        val videoCaptureB = VideoCapture.withOutput(recorderBack)
        val groupBack = UseCaseGroup.Builder()
            .addUseCase(previewUseBack)
            .addUseCase(videoCaptureB)
            .build()

        val previewUseFront = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewFront.surfaceProvider) }
        val recorderFront = Recorder.Builder().build()
        val videoCaptureF = VideoCapture.withOutput(recorderFront)
        val groupFront = UseCaseGroup.Builder()
            .addUseCase(previewUseFront)
            .addUseCase(videoCaptureF)
            .build()

        val configBack = ConcurrentCamera.SingleCameraConfig(backSelector, groupBack, this)
        val configFront = ConcurrentCamera.SingleCameraConfig(frontSelector, groupFront, this)

        try {
            cameraProvider.bindToLifecycle(listOf(configBack, configFront))
            videoCaptureBack = videoCaptureB
            videoCaptureFront = videoCaptureF
            isDualMode = true
            previewFront.visibility = View.VISIBLE
            statusText.text = "Sẵn sàng - máy hỗ trợ quay đồng thời 2 camera"
        } catch (e: Exception) {
            Log.e("Dashcam", "Bind concurrent camera that bai, chuyen sang 1 camera", e)
            fallbackSingleCamera()
        }
    }

    private fun fallbackSingleCamera() {
        isDualMode = false
        previewFront.visibility = View.GONE
        cameraProvider.unbindAll()

        val previewUse = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewBack.surfaceProvider) }
        val recorder = Recorder.Builder().build()
        val videoCaptureB = VideoCapture.withOutput(recorder)
        videoCaptureBack = videoCaptureB
        videoCaptureFront = null

        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, videoCaptureB)
        statusText.text = "Máy không hỗ trợ quay 2 camera cùng lúc - chỉ quay camera sau"
    }

    private fun buildMediaStoreOptions(displayName: String): MediaStoreOutputOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedAlert")
        }
        return MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
    }

    private fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        videoCaptureBack?.let { vc ->
            val options = buildMediaStoreOptions("hanhtrinh_sau_$timestamp")
            recordingBack = vc.output
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (event.hasError()) {
                            Toast.makeText(this, "Lỗi quay camera sau (mã ${event.error})", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Đã lưu video camera sau vào Movies/SpeedAlert", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }

        if (isDualMode) {
            videoCaptureFront?.let { vc ->
                val options = buildMediaStoreOptions("hanhtrinh_truoc_$timestamp")
                recordingFront = vc.output
                    .prepareRecording(this, options)
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError()) {
                                Toast.makeText(this, "Lỗi quay camera trước (mã ${event.error})", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "Đã lưu video camera trước vào Movies/SpeedAlert", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        }

        isRecording = true
        recordButton.text = "Dừng quay"
        statusText.text = if (isDualMode) "Đang quay 2 camera..." else "Đang quay camera sau..."
    }

    private fun stopRecording() {
        recordingBack?.stop()
        recordingBack = null
        recordingFront?.stop()
        recordingFront = null
        isRecording = false
        recordButton.text = "Bắt đầu quay"
        statusText.text = "Đã dừng quay - video đã lưu vào thư viện máy (Movies/SpeedAlert)"
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingBack?.stop()
        recordingFront?.stop()
        activityScope.cancel()
    }
}
