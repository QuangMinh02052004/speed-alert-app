package com.speedalert.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.clickable
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.speedalert.app.core.AppSettings
import com.speedalert.app.core.VehicleType
import com.speedalert.app.service.LocationTrackingService
import com.speedalert.app.service.SpeedState

class MainActivity : ComponentActivity() {

    private val permissionMessage = mutableStateOf("")

    // Chỉ xin quyền vị trí KHI DÙNG APP (+ thông báo). Không xin "vị trí nền" chung
    // một lần: Android 11+ sẽ bỏ qua toàn bộ yêu cầu nếu gộp như vậy (lỗi bản cũ).
    // Service foreground loại "location" vẫn chạy khi tắt màn hình mà không cần quyền nền.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionMessage.value = when {
            fine -> ""
            coarse -> "Bạn đang cho phép vị trí GẦN ĐÚNG. Hãy bật \"Vị trí chính xác\" trong Cài đặt ứng dụng để đo tốc độ đúng."
            else -> "Chưa có quyền vị trí. Vào Cài đặt > Ứng dụng > Speed Alert > Quyền > Vị trí > \"Chỉ cho phép khi dùng ứng dụng\"."
        }
        if (fine || coarse) LocationTrackingService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContent {
            MaterialTheme {
                HomeScreen(permissionMessage.value) { LimitCorrection.show(this, lifecycleScope) }
            }
        }
        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            LocationTrackingService.start(this)
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

private val Green = Color(0xFF0E7C47)
private val Red = Color(0xFFD32F2F)

@Composable
fun HomeScreen(permissionMessage: String, onEditLimit: () -> Unit) {
    val context = LocalContext.current
    val state by SpeedState.state.collectAsState()
    val running by SpeedState.serviceRunning.collectAsState()
    val voice by AppSettings.voiceEnabled.collectAsState()
    val calibration by AppSettings.speedCalibrationPercent.collectAsState()

    val bg = when {
        state.overLimit -> Red
        state.nearCamera -> Color(0xFFF57C00)
        else -> Color.White
    }
    val fg = if (state.overLimit || state.nearCamera) Color.White else Color(0xFF212121)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (permissionMessage.isNotBlank()) {
            Text(
                permissionMessage, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(Red).padding(10.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${state.currentSpeedKmh}", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = fg)
                Text("km/h", fontSize = 22.sp, color = fg)
            }
            Spacer(Modifier.width(24.dp))
            val limit = state.speedLimitKmh
            if (limit != null || state.roadNote.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .background(Color.White, CircleShape)
                        .border(9.dp, Red, CircleShape)
                        .clickable { onEditLimit() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        limit?.toString() ?: "CẤM\nXE MÁY",
                        fontSize = if (limit != null) 34.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF212121),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.roadName.isNotBlank() -> state.roadName
                state.lastLat == null -> "Đang chờ tín hiệu GPS..."
                else -> "Chưa khớp được đường"
            },
            fontSize = 18.sp, fontWeight = FontWeight.Medium, color = fg, textAlign = TextAlign.Center
        )
        if (state.speedLimitKmh != null) {
            Text(
                when (state.limitSource) {
                    "user" -> "Giới hạn bạn đã sửa theo biển thật"
                    "sign" -> "Giới hạn theo biển báo trên bản đồ"
                    else -> "Giới hạn theo luật (TT 38/2024) - chạm biển để sửa nếu sai"
                },
                fontSize = 13.sp, color = fg.copy(alpha = 0.75f), textAlign = TextAlign.Center
            )
        }
        if (state.lanes > 0) {
            Text(
                (if (state.oneway) "Đường 1 chiều, " else "") + "${state.lanes} làn",
                fontSize = 13.sp, color = fg.copy(alpha = 0.75f)
            )
        }
        if (calibration > 0) {
            Text("Tốc độ thật GPS: ${state.trueSpeedKmh} km/h", fontSize = 13.sp, color = fg.copy(alpha = 0.75f))
        }
        state.cameraDistanceM?.let {
            Text("📷 Camera phạt nguội phía trước: $it m", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        }
        Text(
            state.accuracyM?.let { "GPS ±${it.toInt()} m" + if (state.gpsWeak) " (yếu)" else "" } ?: "",
            fontSize = 13.sp, color = fg.copy(alpha = 0.75f)
        )
        Text(state.dataStatus, fontSize = 13.sp, color = fg.copy(alpha = 0.75f), textAlign = TextAlign.Center)

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { context.startActivity(Intent(context, MapActivity::class.java)) },
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("🗺  Bản đồ, tìm kiếm & chỉ đường", fontSize = 16.sp) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { context.startActivity(Intent(context, DashcamActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("🎥  Camera hành trình", fontSize = 16.sp) }

        Spacer(Modifier.height(20.dp))
        Text(
            "🏍  Chế độ xe máy: giới hạn tốc độ theo luật cho mô tô, chỉ đường tránh cao tốc",
            fontSize = 14.sp, color = fg, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Cảnh báo bằng giọng nói", fontSize = 15.sp, color = fg, modifier = Modifier.weight(1f))
            Switch(checked = voice, onCheckedChange = { AppSettings.setVoiceEnabled(context, it) })
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Hiệu chỉnh theo đồng hồ xe: +$calibration%" + if (calibration == 0) " (đang hiện tốc độ thật GPS)" else "",
            fontSize = 14.sp, color = fg, modifier = Modifier.fillMaxWidth()
        )
        Slider(
            value = calibration.toFloat(),
            onValueChange = { AppSettings.setSpeedCalibration(context, Math.round(it)) },
            valueRange = 0f..15f,
            steps = 14
        )
        Text(
            "Đồng hồ xe máy thường hiện cao hơn tốc độ thật vài %. Kéo để số trên app khớp đồng hồ xe; cảnh báo quá tốc độ vẫn dùng tốc độ thật.",
            fontSize = 11.sp, color = fg.copy(alpha = 0.65f)
        )

        Spacer(Modifier.height(12.dp))
        var refreshing by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = {
                refreshing = true
                LocationTrackingService.refreshData(context)
            },
            enabled = state.lastLat != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (refreshing) "Đã gửi yêu cầu cập nhật dữ liệu" else "Cập nhật dữ liệu đường khu vực này", color = fg) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (running) {
                    context.startService(
                        Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP)
                    )
                } else {
                    LocationTrackingService.start(context)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (running) "Tắt theo dõi tốc độ" else "Bật theo dõi tốc độ", color = fg) }

        Spacer(Modifier.height(16.dp))
        Text(
            "Dữ liệu đường & camera: © OpenStreetMap contributors. Giới hạn tốc độ suy theo luật chỉ mang tính tham khảo - luôn tuân theo biển báo thực tế.",
            fontSize = 11.sp, color = fg.copy(alpha = 0.6f), textAlign = TextAlign.Center
        )
    }
}
