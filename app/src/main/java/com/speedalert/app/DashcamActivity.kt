package com.speedalert.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
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
 */
class DashcamActivity : ComponentActivity() {

    private lateinit var previewBack: PreviewView
    private lateinit var previewFront: PreviewView
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView

    private lateinit var cameraProvider: ProcessCameraProvider
    private var videoCaptureBack: VideoCapture<Recorder>? = null
    private var videoCaptureFront: VideoCapture<Recorder>? = null
    private var recordingBack: Recording? = null
    private var recordingFront: Recording? = null
    private var isDualMode = false
    private var isRecording = false

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

        recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        requestPermissionsAndStart()
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

    private fun startRecording() {
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        videoCaptureBack?.let { vc ->
            val file = File(outputDir, "hanhtrinh_sau_$timestamp.mp4")
            val options = FileOutputOptions.Builder(file).build()
            recordingBack = vc.output
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize && event.hasError()) {
                        Toast.makeText(this, "Lỗi quay camera sau (mã ${event.error})", Toast.LENGTH_LONG).show()
                    }
                }
        }

        if (isDualMode) {
            videoCaptureFront?.let { vc ->
                val file = File(outputDir, "hanhtrinh_truoc_$timestamp.mp4")
                val options = FileOutputOptions.Builder(file).build()
                recordingFront = vc.output
                    .prepareRecording(this, options)
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize && event.hasError()) {
                            Toast.makeText(this, "Lỗi quay camera trước (mã ${event.error})", Toast.LENGTH_LONG).show()
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
        statusText.text = "Đã lưu video (thư mục Movies riêng của app)"
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingBack?.stop()
        recordingFront?.stop()
    }
}
