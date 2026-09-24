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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.speedalert.app.service.LocationTrackingService
import com.speedalert.app.service.SpeedState

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTrackingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpeedScreen()
            }
        }
        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startTrackingService()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun SpeedScreen() {
    val context = LocalContext.current
    val state by SpeedState.state.collectAsState()
    val isOverLimit = state.speedLimitKmh != null && state.currentSpeedKmh > state.speedLimitKmh!!

    val backgroundColor = when {
        isOverLimit -> Color(0xFFB00020)
        state.nearCamera -> Color(0xFFFF8F00)
        else -> Color.White
    }
    val contentColor = if (isOverLimit || state.nearCamera) Color.White else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${state.currentSpeedKmh}",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(text = "km/h", fontSize = 24.sp, color = contentColor)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (state.speedLimitKmh != null)
                    "Giới hạn: ${state.speedLimitKmh} km/h"
                else "Chưa có dữ liệu giới hạn tốc độ",
                fontSize = 20.sp,
                color = contentColor
            )
            if (state.roadName.isNotBlank()) {
                Text(text = state.roadName, fontSize = 14.sp, color = contentColor)
            }
            if (state.nearCamera) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "⚠ Có camera bắn tốc độ gần đây",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = {
                context.startActivity(Intent(context, MapActivity::class.java))
            }) {
                Text("Xem bản đồ / Đánh dấu camera")
            }
        }
    }
}
