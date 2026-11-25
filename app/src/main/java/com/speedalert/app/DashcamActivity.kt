package com.speedalert.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera hành trình.
 *
 * Chế độ "Trước + Sau": CameraX (1.5+) ghép 2 camera thành MỘT khung hình và
 * MỘT file video - nửa trên là camera trước, nửa dưới là camera sau. Bản cũ
 * ghi 2 file bằng 2 bộ mã hoá cùng lúc, nhiều máy không chịu nổi nên lỗi.
 *
 * Ngày giờ, tốc độ, toạ độ được IN THẲNG VÀO VIDEO (OverlayEffect) như dashcam.
 * Video lưu vào thư viện máy: Movies/SpeedAlert (xem được trong Bộ sưu tập).
 *
 * Nếu máy không hỗ trợ ghép 2 camera, tự chuyển sang 1 camera và báo rõ.
 */
class DashcamActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val TAG = "Dashcam"
        private const val PREFS = "dashcam"

        fun open(context: Context, autoStart: Boolean) {
            context.startActivity(Intent(context, DashcamActivity::class.java).putExtra(EXTRA_AUTO_START, autoStart))
        }
    }

    private enum class Mode { DUAL, BACK, FRONT }

    private lateinit var preview: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recIndicator: TextView
    private lateinit var recordButton: Button
    private lateinit var modeButtons: Map<Mode, Button>

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var overlayEffect: OverlayEffect? = null
    private val effectThread = HandlerThread("dashcam-overlay").apply { start() }
    private var mode = Mode.DUAL
    private var activeMode: Mode? = null
    private var overlayInVideo = false
    private var recStartedAt = 0L
    private var timerJob: Job? = null
    private var autoStartPending = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true && results[Manifest.permission.RECORD_AUDIO] == true) {
            startCamera()
        } else {
            statusText.text = "Cần cấp quyền Camera và Micro để quay hành trình"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        statusText = findViewById(R.id.statusText)
        recIndicator = findViewById(R.id.recIndicator)
        recordButton = findViewById(R.id.recordButton)
        modeButtons = mapOf(
            Mode.DUAL to findViewById(R.id.modeDual),
            Mode.BACK to findViewById(R.id.modeBack),
            Mode.FRONT to findViewById(R.id.modeFront)
        )

        mode = runCatching {
            Mode.valueOf(getSharedPreferences(PREFS, MODE_PRIVATE).getString("mode", Mode.DUAL.name)!!)
        }.getOrDefault(Mode.DUAL)
        autoStartPending = intent.getBooleanExtra(EXTRA_AUTO_START, false)

        modeButtons.forEach { (m, btn) ->
            btn.setOnClickListener {
                if (recording != null) {
                    Toast.makeText(this, "Dừng quay trước khi đổi chế độ", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mode = m
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mode", m.name).apply()
                bindCamera()
            }
        }
        recordButton.setOnClickListener { if (recording != null) stopRecording() else startRecording() }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
            if (autoStartPending) {
                autoStartPending = false
                recordButton.postDelayed({ if (videoCapture != null) startRecording() }, 600)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshModeButtons() {
        modeButtons.forEach { (m, btn) ->
            btn.alpha = if (m == mode) 1f else 0.55f
            btn.isSelected = m == mode
        }
    }

    // ------------------------------------------------------------- Ghép camera

    private fun buildGroup(withEffect: Boolean): Pair<UseCaseGroup, VideoCapture<Recorder>> {
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD)))
            .build()
        val vc = VideoCapture.withOutput(recorder)
        val builder = UseCaseGroup.Builder().addUseCase(previewUseCase).addUseCase(vc)
        if (withEffect) builder.addEffect(createOverlayEffect())
        return builder.build() to vc
    }

    /** Thử lần lượt: có in thông tin vào video → không in; ghép 2 camera → 1 camera. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        refreshModeButtons()
        provider.unbindAll()
        closeEffect()
        videoCapture = null

        if (mode == Mode.DUAL) {
            val pair = findConcurrentPair(provider)
            if (pair != null) {
                for (withEffect in listOf(true, false)) {
                    try {
                        val (group, vc) = buildGroup(withEffect)
                        // Toạ độ ghép: tâm khung = (0,0), trục y hướng lên, cạnh = ±1.
                        // Thu nhỏ 1/2 rồi đặt lên trên (y = +0.5) và xuống dưới (y = -0.5).
                        val back = SingleCameraConfig(
                            pair.first, group,
                            CompositionSettings.Builder().setAlpha(1f).setOffset(0f, -0.5f).setScale(0.5f, 0.5f).build(),
                            this
                        )
                        val front = SingleCameraConfig(
                            pair.second, group,
                            CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0.5f).setScale(0.5f, 0.5f).build(),
                            this
                        )
                        provider.bindToLifecycle(listOf(back, front))
                        videoCapture = vc
                        activeMode = Mode.DUAL
                        overlayInVideo = withEffect
                        statusText.text = "Trước + Sau: ghép 1 video (trên: cam trước, dưới: cam sau)" +
                            if (withEffect) "" else "\nMáy không in được thông tin vào video, chỉ hiện trên màn hình."
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Bind dual (effect=$withEffect) failed", e)
                        provider.unbindAll()
                        closeEffect()
                    }
                }
            }
        }

        val selector = if (mode == Mode.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        for (withEffect in listOf(true, false)) {
            try {
                val (group, vc) = buildGroup(withEffect)
                provider.bindToLifecycle(this, selector, group)
                videoCapture = vc
                activeMode = if (mode == Mode.FRONT) Mode.FRONT else Mode.BACK
                overlayInVideo = withEffect
                statusText.text = when {
                    mode == Mode.DUAL -> "Máy này không hỗ trợ quay 2 camera cùng lúc - đang dùng cam sau"
                    mode == Mode.FRONT -> "Đang dùng cam trước"
                    else -> "Đang dùng cam sau"
                } + if (withEffect) "" else "\nMáy không in được thông tin vào video, chỉ hiện trên màn hình."
                return
            } catch (e: Exception) {
                Log.w(TAG, "Bind single (effect=$withEffect) failed", e)
                provider.unbindAll()
                closeEffect()
            }
        }
        statusText.text = "Không mở được camera (đang bị ứng dụng khác dùng?)"
    }

    private fun findConcurrentPair(provider: ProcessCameraProvider): Pair<CameraSelector, CameraSelector>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (infos in provider.availableConcurrentCameraInfos) {
            val back = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) return back.cameraSelector to front.cameraSelector
        }
        return null
    }

    // ------------------------------------------------------------- In thông tin vào video

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val boxPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val stampFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)

    private fun createOverlayEffect(): OverlayEffect {
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            0,
            Handler(effectThread.looper)
        ) { t -> Log.w(TAG, "Overlay error", t) }

        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = frame.size.width.toFloat()
            val h = frame.size.height.toFloat()
            val rot = frame.rotationDegrees

            // Khung đệm có thể đang nằm ngang so với video hiển thị: xoay canvas để chữ đứng thẳng.
            canvas.save()
            when (rot) {
                90 -> { canvas.translate(0f, h); canvas.rotate(-90f) }
                180 -> { canvas.translate(w, h); canvas.rotate(180f) }
                270 -> { canvas.translate(w, 0f); canvas.rotate(90f) }
            }
            val uprightW = if (rot == 90 || rot == 270) h else w
            val uprightH = if (rot == 90 || rot == 270) w else h

            val s = SpeedState.state.value
            val lines = mutableListOf(
                stampFormat.format(Date()),
                "${s.currentSpeedKmh} km/h" + (s.speedLimitKmh?.let { "  (gioi han $it)" } ?: "")
            )
            if (s.lastLat != null && s.lastLon != null) {
                lines.add(String.format(Locale.US, "%.5f, %.5f", s.lastLat, s.lastLon))
            }
            if (s.roadName.isNotBlank()) lines.add(s.roadName)

            textPaint.textSize = uprightW / 30f
            val lineH = textPaint.textSize * 1.25f
            val pad = textPaint.textSize * 0.5f
            val boxW = lines.maxOf { textPaint.measureText(it) } + pad * 2
            val boxH = lineH * lines.size + pad
            val top = uprightH - boxH - pad
            canvas.drawRect(pad, top, pad + boxW, top + boxH, boxPaint)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, pad * 2, top + lineH * (i + 1) - textPaint.textSize * 0.2f, textPaint)
            }
            canvas.restore()
            true
        }
        overlayEffect = effect
        return effect
    }

    private fun closeEffect() {
        overlayEffect?.let { runCatching { it.close() } }
        overlayEffect = null
    }

    // ------------------------------------------------------------- Ghi hình

    private fun startRecording() {
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "hanhtrinh_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedAlert")
            }
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        val pending = vc.output.prepareRecording(this, output)
        val withAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (withAudio) pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecordingStarted()
                is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
            }
        }
    }

    private fun onRecordingStarted() {
        recStartedAt = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recordButton.text = "■ Dừng quay & lưu"
        recIndicator.visibility = View.VISIBLE
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (true) {
                val sec = (SystemClock.elapsedRealtime() - recStartedAt) / 1000
                recIndicator.text = String.format(Locale.US, "● REC %02d:%02d", sec / 60, sec % 60)
                delay(500)
            }
        }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        timerJob?.cancel()
        recIndicator.visibility = View.GONE
        recordButton.text = "● Bắt đầu quay"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recording = null

        val savedOk = event.outputResults.outputUri != android.net.Uri.EMPTY
        val message = when {
            !event.hasError() -> "Đã lưu video vào Bộ sưu tập › Movies/SpeedAlert"
            event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE && savedOk ->
                "Đã lưu video (dừng vì camera bị tắt/thoát màn hình)"
            event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Lưu thiếu: bộ nhớ máy đã đầy"
            event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "Không lưu được: video quá ngắn, hãy quay ít nhất 2 giây"
            event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "Lỗi mã hoá video trên máy này. Thử chế độ 1 camera."
            savedOk -> "Đã lưu video nhưng có cảnh báo (mã ${event.error})"
            else -> "Không lưu được video (mã lỗi ${event.error}: ${event.cause?.message ?: ""})"
        }
        Log.d(TAG, "Finalize error=${event.error} uri=${event.outputResults.outputUri}", event.cause)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        statusText.text = message
    }

    private fun stopRecording() {
        recording?.stop()
    }

    override fun onStop() {
        // Thoát màn hình/khoá máy: dừng & lưu gọn gàng thay vì để file hỏng.
        recording?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        closeEffect()
        effectThread.quitSafely()
    }
}
