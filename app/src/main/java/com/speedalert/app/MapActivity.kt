package com.speedalert.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.speedalert.app.core.AppSettings
import com.speedalert.app.core.NavState
import com.speedalert.app.core.NavigationManager
import com.speedalert.app.core.Place
import com.speedalert.app.core.Route
import com.speedalert.app.core.RoutingClient
import com.speedalert.app.core.SearchClient
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.data.GeoPoint
import com.speedalert.app.service.LocationTrackingService
import com.speedalert.app.service.SpeedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình bản đồ & dẫn đường kiểu Vietmap:
 * - Chấm định vị có mũi tên hướng đi + vòng sai số, bản đồ tự bám theo.
 * - Đồng hồ tốc độ + biển giới hạn tốc độ + cảnh báo camera ở góc trái.
 * - Tìm kiếm có danh sách kết quả (ưu tiên gần bạn); nhấn giữ bản đồ để chọn điểm.
 * - Xem trước tuyến (quãng đường / thời gian), bắt đầu dẫn đường: banner bước
 *   rẽ, quãng đường & giờ đến, giọng nói, tự tính lại khi đi lệch (trong Service).
 * - Chạm chấm định vị: bảng thông tin + bật camera quay hành trình.
 */
class MapActivity : ComponentActivity() {

    companion object {
        private const val STYLE_DAY = "https://tiles.openfreemap.org/styles/liberty"
        private const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

        /** Ban đêm (18h-6h) dùng nền tối cho đỡ chói mắt khi chạy xe. */
        fun styleForNow(): String {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return if (h >= 18 || h < 6) STYLE_NIGHT else STYLE_DAY
        }
        private val DEFAULT_CENTER = LatLng(10.7769, 106.7009)
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var locationReady = false
    private var listenersAdded = false

    private lateinit var searchInput: EditText
    private lateinit var resultsScroll: ScrollView
    private lateinit var resultsList: LinearLayout
    private lateinit var navBanner: LinearLayout
    private lateinit var bannerArrow: TextView
    private lateinit var bannerDistance: TextView
    private lateinit var bannerText: TextView
    private lateinit var speedPanel: LinearLayout
    private lateinit var speedBubble: TextView
    private lateinit var limitSign: TextView
    private lateinit var cameraChip: TextView
    private lateinit var gpsChip: TextView
    private lateinit var dataChip: TextView
    private lateinit var voiceButton: Button
    private lateinit var placeCard: LinearLayout
    private lateinit var placeName: TextView
    private lateinit var placeAddress: TextView
    private lateinit var routeInfo: TextView
    private lateinit var directionsButton: Button
    private lateinit var startNavButton: Button
    private lateinit var navBottomBar: LinearLayout
    private lateinit var navRemaining: TextView

    private var selectedPlace: Place? = null
    private var previewRoute: Route? = null
    private var placeMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var drawnRoute: Route? = null
    private val cameraMarkers = mutableListOf<Marker>()
    private var cameraCountShown = -1
    private var lastDataStatus = ""
    private var following = true

    private var liveCameraProvider: ProcessCameraProvider? = null
    private var liveRecording: Recording? = null
    private var pendingPreviewView: PreviewView? = null
    private var pendingSwitch: Switch? = null
    private var infoDialog: AlertDialog? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            LocationTrackingService.start(this)
            map?.style?.let { enableLocationComponent(it) }
        } else {
            Toast.makeText(this, "Cần quyền vị trí để định vị và chỉ đường", Toast.LENGTH_LONG).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val previewView = pendingPreviewView
        if (results[Manifest.permission.CAMERA] == true && results[Manifest.permission.RECORD_AUDIO] == true && previewView != null) {
            startLiveRecording(previewView)
        } else {
            pendingSwitch?.isChecked = false
            Toast.makeText(this, "Cần cấp quyền Camera và Micro để quay video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        AppSettings.load(this)
        setContentView(R.layout.activity_map)
        bindViews()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassMargins(0, 260, 24, 0)
            val start = SpeedState.state.value.let {
                if (it.lastLat != null && it.lastLon != null) LatLng(it.lastLat, it.lastLon) else DEFAULT_CENTER
            }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 16.0))
            mapView.addOnDidFailLoadingMapListener {
                // Kiểu bản đồ đêm lỗi tải -> quay về kiểu ban ngày.
                if (m.style?.uri != STYLE_DAY) m.setStyle(Style.Builder().fromUri(STYLE_DAY)) { st -> onStyleReady(st) }
            }
            m.setStyle(Style.Builder().fromUri(styleForNow())) { style -> onStyleReady(style) }
            m.addOnMapLongClickListener { point ->
                selectPlace(Place("Điểm đã chọn", "Đang tra địa chỉ...", GeoPoint(point.latitude, point.longitude)), lookupAddress = true)
                true
            }
        }

        ensureLocationPermission()
        observeState()
    }

    private fun onStyleReady(style: Style) {
        locationReady = false
        enableLocationComponent(style)
        reloadCameraMarkers(force = true)
        drawnRoute = null
        renderNav(NavigationManager.state.value)
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        searchInput = findViewById(R.id.searchInput)
        resultsScroll = findViewById(R.id.resultsScroll)
        resultsList = findViewById(R.id.resultsList)
        navBanner = findViewById(R.id.navBanner)
        bannerArrow = findViewById(R.id.bannerArrow)
        bannerDistance = findViewById(R.id.bannerDistance)
        bannerText = findViewById(R.id.bannerText)
        speedPanel = findViewById(R.id.speedPanel)
        speedBubble = findViewById(R.id.speedBubble)
        limitSign = findViewById(R.id.limitSign)
        cameraChip = findViewById(R.id.cameraChip)
        gpsChip = findViewById(R.id.gpsChip)
        dataChip = findViewById(R.id.dataChip)
        voiceButton = findViewById(R.id.voiceButton)
        placeCard = findViewById(R.id.placeCard)
        placeName = findViewById(R.id.placeName)
        placeAddress = findViewById(R.id.placeAddress)
        routeInfo = findViewById(R.id.routeInfo)
        directionsButton = findViewById(R.id.directionsButton)
        startNavButton = findViewById(R.id.startNavButton)
        navBottomBar = findViewById(R.id.navBottomBar)
        navRemaining = findViewById(R.id.navRemaining)

        findViewById<Button>(R.id.searchButton).setOnClickListener { performSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(); true
            } else false
        }
        findViewById<Button>(R.id.recenterButton).setOnClickListener { recenter() }
        findViewById<Button>(R.id.placeClose).setOnClickListener { clearPlace() }
        directionsButton.setOnClickListener { selectedPlace?.let { computeRoute(it) } }
        startNavButton.setOnClickListener { startNavigation() }
        findViewById<Button>(R.id.endNavButton).setOnClickListener { endNavigation() }

        updateVoiceButton()
        voiceButton.setOnClickListener {
            AppSettings.setVoiceEnabled(this, !AppSettings.voiceEnabled.value)
            updateVoiceButton()
            Toast.makeText(
                this,
                if (AppSettings.voiceEnabled.value) "Đã bật giọng nói" else "Đã tắt giọng nói (chỉ còn tiếng bíp)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateVoiceButton() {
        voiceButton.text = if (AppSettings.voiceEnabled.value) "🔊" else "🔇"
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            LocationTrackingService.start(this)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // ---------------------------------------------------------------- Định vị

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        val m = map ?: return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted || locationReady) return

        val lc = m.locationComponent
        val options = LocationComponentOptions.builder(this)
            .accuracyAlpha(0.18f)
            .accuracyColor(Color.parseColor("#1A73E8"))
            .build()
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        lc.cameraMode = CameraMode.TRACKING_GPS_NORTH
        if (!listenersAdded) {
            listenersAdded = true
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() {
                    following = false
                }

                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            lc.addOnLocationClickListener { showLocationInfoDialog() }
        }
        locationReady = true
        SpeedState.state.value.location?.let { lc.forceLocationUpdate(it) }
    }

    private fun recenter() {
        val m = map ?: return
        following = true
        if (locationReady) {
            val nav = NavigationManager.state.value.active
            m.locationComponent.cameraMode = if (nav) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
            m.locationComponent.zoomWhileTracking(if (nav) 17.5 else 16.5)
            m.locationComponent.tiltWhileTracking(if (nav) 50.0 else 0.0)
        } else {
            val s = SpeedState.state.value
            if (s.lastLat != null && s.lastLon != null) {
                m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lastLat, s.lastLon), 16.5), 600)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SpeedState.state.collect { renderSpeed(it) } }
                launch { NavigationManager.state.collect { renderNav(it) } }
            }
        }
    }

    private fun renderSpeed(s: SpeedState.State) {
        speedBubble.text = "${s.currentSpeedKmh}\nkm/h"
        speedBubble.setBackgroundResource(if (s.overLimit) R.drawable.bg_speed_bubble_over else R.drawable.bg_speed_bubble)
        speedBubble.setTextColor(if (s.overLimit) Color.WHITE else Color.parseColor("#212121"))

        when {
            s.speedLimitKmh != null -> {
                limitSign.visibility = View.VISIBLE
                limitSign.text = s.speedLimitKmh.toString()
                limitSign.textSize = 22f
            }
            s.roadNote.isNotBlank() -> {
                limitSign.visibility = View.VISIBLE
                limitSign.text = "CẤM\nXE MÁY"
                limitSign.textSize = 9f
            }
            else -> limitSign.visibility = View.GONE
        }

        if (s.cameraDistanceM != null) {
            cameraChip.visibility = View.VISIBLE
            cameraChip.text = "📷 ${s.cameraDistanceM} m"
        } else cameraChip.visibility = View.GONE

        gpsChip.text = when {
            s.accuracyM == null -> "Đang dò GPS..."
            s.gpsWeak -> "GPS yếu ±${s.accuracyM.toInt()}m"
            else -> "GPS ±${s.accuracyM.toInt()}m"
        }
        val road = listOf(s.roadName, s.roadNote).filter { it.isNotBlank() }.joinToString(" - ")
        dataChip.text = if (road.isNotBlank()) "$road  •  ${s.dataStatus}" else s.dataStatus

        val nav = NavigationManager.state.value
        val m = map
        if (m != null && locationReady) {
            val loc = s.location
            if (loc != null) {
                val shown = if (nav.active && nav.snapped != null) {
                    Location(loc).apply {
                        latitude = nav.snapped.lat
                        longitude = nav.snapped.lon
                        nav.snappedBearing?.let { bearing = it.toFloat() }
                    }
                } else loc
                m.locationComponent.forceLocationUpdate(shown)
            }
        }
        if (s.dataStatus != lastDataStatus) {
            lastDataStatus = s.dataStatus
            reloadCameraMarkers(force = false)
        }
    }

    // ---------------------------------------------------------------- Camera phạt nguội trên bản đồ

    private fun reloadCameraMarkers(force: Boolean) {
        val m = map ?: return
        lifecycleScope.launch {
            val cams = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).cameraDao().getAll() }
            if (!force && cams.size == cameraCountShown) return@launch
            cameraMarkers.forEach { m.removeMarker(it) }
            cameraMarkers.clear()
            cams.take(500).forEach { cam ->
                val title = if (cam.maxspeed > 0) "Camera phạt nguội - ${cam.maxspeed} km/h" else "Camera phạt nguội"
                cameraMarkers.add(m.addMarker(MarkerOptions().position(LatLng(cam.lat, cam.lon)).title(title)))
            }
            cameraCountShown = cams.size
        }
    }

    // ---------------------------------------------------------------- Tìm kiếm

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        resultsList.removeAllViews()
        resultsList.addView(resultRow("Đang tìm \"$query\"...", "", null))
        resultsScroll.visibility = View.VISIBLE

        val s = SpeedState.state.value
        val near = if (s.lastLat != null && s.lastLon != null) GeoPoint(s.lastLat, s.lastLon) else null
        lifecycleScope.launch {
            val places = withContext(Dispatchers.IO) { SearchClient.search(applicationContext, query, near) }
            resultsList.removeAllViews()
            if (places.isEmpty()) {
                resultsList.addView(resultRow("Không tìm thấy \"$query\"", "Thử ghi rõ hơn: số nhà, tên đường, quận/huyện", null))
                return@launch
            }
            places.forEach { p ->
                val dist = near?.let { " • " + NavigationManager.formatDistance(com.speedalert.app.core.GeoUtils.distance(it, p.point)) } ?: ""
                resultsList.addView(resultRow(p.name, p.address + dist, p))
            }
        }
    }

    private fun resultRow(title: String, subtitle: String, place: Place?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = place != null
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#212121"))
            maxLines = 1
        })
        if (subtitle.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#777777"))
                maxLines = 2
            })
        }
        if (place != null) row.setOnClickListener {
            resultsScroll.visibility = View.GONE
            selectPlace(place, lookupAddress = false)
        }
        return row
    }

    private fun selectPlace(place: Place, lookupAddress: Boolean) {
        val m = map ?: return
        if (NavigationManager.state.value.active) return
        selectedPlace = place
        previewRoute = null
        clearRouteLine()
        placeMarker?.let { m.removeMarker(it) }
        placeMarker = m.addMarker(MarkerOptions().position(LatLng(place.point.lat, place.point.lon)).title(place.name))

        if (locationReady) m.locationComponent.cameraMode = CameraMode.NONE
        following = false
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.point.lat, place.point.lon), 16.0), 700)

        placeName.text = place.name
        placeAddress.text = place.address
        routeInfo.visibility = View.GONE
        directionsButton.visibility = View.VISIBLE
        directionsButton.isEnabled = true
        directionsButton.text = "Chỉ đường"
        startNavButton.visibility = View.GONE
        placeCard.visibility = View.VISIBLE
        liftSpeedPanel(placeCard)

        if (lookupAddress) {
            lifecycleScope.launch {
                val addr = withContext(Dispatchers.IO) { SearchClient.reverse(applicationContext, place.point) }
                if (selectedPlace === place) {
                    placeAddress.text = addr ?: "Không tra được địa chỉ"
                    if (addr != null) selectedPlace = place.copy(address = addr)
                }
            }
        }
    }

    private fun clearPlace() {
        val m = map
        selectedPlace = null
        previewRoute = null
        placeMarker?.let { m?.removeMarker(it) }
        placeMarker = null
        clearRouteLine()
        placeCard.visibility = View.GONE
        liftSpeedPanel(null)
        recenter()
    }

    // ---------------------------------------------------------------- Tuyến đường

    private fun computeRoute(place: Place) {
        val s = SpeedState.state.value
        if (s.lastLat == null || s.lastLon == null) {
            Toast.makeText(this, "Chưa xác định được vị trí của bạn. Hãy bật GPS và chờ vài giây.", Toast.LENGTH_LONG).show()
            return
        }
        val start = GeoPoint(s.lastLat, s.lastLon)
        directionsButton.isEnabled = false
        directionsButton.text = "Đang tính..."
        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) {
                RoutingClient.route(start, place.point, AppSettings.vehicle.value, s.bearing)
            }
            directionsButton.isEnabled = true
            directionsButton.text = "Chỉ đường"
            if (selectedPlace !== place && selectedPlace?.point != place.point) return@launch
            if (route == null) {
                AlertDialog.Builder(this@MapActivity)
                    .setTitle("Không tính được đường đi")
                    .setMessage(
                        "Lý do: ${RoutingClient.lastError ?: "không rõ"}\n\n" +
                            "Kiểm tra kết nối mạng rồi thử lại. Nếu điểm đến nằm trong hẻm/khu không có đường xe, hãy chọn điểm gần đường lớn hơn."
                    )
                    .setPositiveButton("Đóng", null)
                    .show()
                return@launch
            }
            previewRoute = route
            drawRoute(route, fit = true)
            routeInfo.visibility = View.VISIBLE
            routeInfo.text = "${NavigationManager.formatDistance(route.distanceMeters)} • ${formatDuration(route.durationSeconds)} • ${route.provider}"
            directionsButton.visibility = View.GONE
            startNavButton.visibility = View.VISIBLE
            liftSpeedPanel(placeCard)
        }
    }

    private fun drawRoute(route: Route, fit: Boolean) {
        val m = map ?: return
        if (drawnRoute === route && routeLine != null) return
        clearRouteLine()
        val pts = route.points.map { LatLng(it.lat, it.lon) }
        routeLine = m.addPolyline(PolylineOptions().addAll(pts).color(Color.parseColor("#1A73E8")).width(7f))
        drawnRoute = route
        if (fit && pts.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(pts).build()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(60), dp(140), dp(60), dp(260)), 800)
        }
    }

    private fun clearRouteLine() {
        routeLine?.let { map?.removePolyline(it) }
        routeLine = null
        drawnRoute = null
    }

    private fun startNavigation() {
        val route = previewRoute ?: return
        val place = selectedPlace ?: return
        NavigationManager.start(route, place.point, place.name)
        placeCard.visibility = View.GONE
        following = true
        recenter()
    }

    private fun endNavigation() {
        NavigationManager.stop()
    }

    private fun renderNav(nav: NavState) {
        if (nav.active && nav.route != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            navBanner.visibility = View.VISIBLE
            navBottomBar.visibility = View.VISIBLE
            findViewById<View>(R.id.searchBar).visibility = View.GONE
            resultsScroll.visibility = View.GONE
            placeCard.visibility = View.GONE
            liftSpeedPanel(navBottomBar)
            drawRoute(nav.route, fit = false)

            val step = nav.nextStep
            when {
                nav.rerouting -> {
                    bannerArrow.text = "⟳"
                    bannerDistance.text = "Đang tính lại..."
                    bannerText.text = "Bạn đã đi lệch tuyến"
                }
                step != null -> {
                    bannerArrow.text = step.kind.arrow
                    bannerDistance.text = NavigationManager.formatDistance(nav.distanceToNextStep)
                    bannerText.text = step.text()
                }
            }
            val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (nav.remainingSeconds * 1000).toLong()))
            navRemaining.text = "${NavigationManager.formatDistance(nav.remainingDistance)} • ${formatDuration(nav.remainingSeconds)} • Đến lúc $eta"
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val wasNavigating = navBanner.visibility == View.VISIBLE
            navBanner.visibility = View.GONE
            navBottomBar.visibility = View.GONE
            findViewById<View>(R.id.searchBar).visibility = View.VISIBLE
            if (wasNavigating) {
                if (nav.arrived) Toast.makeText(this, "Bạn đã đến nơi", Toast.LENGTH_LONG).show()
                clearPlace()
            }
        }
    }

    private fun liftSpeedPanel(cover: View?) {
        if (cover == null || cover.visibility != View.VISIBLE) {
            speedPanel.translationY = 0f
            return
        }
        cover.post { speedPanel.translationY = -(cover.height.toFloat() + dp(4)) }
    }

    private fun formatDuration(seconds: Double): String {
        val min = (seconds / 60).toInt().coerceAtLeast(1)
        return if (min >= 60) "${min / 60} giờ ${min % 60} phút" else "$min phút"
    }

    // ---------------------------------------------------------------- Bảng thông tin vị trí + camera

    private fun showLocationInfoDialog() {
        val s = SpeedState.state.value
        val lat = s.lastLat ?: return
        val lon = s.lastLon ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location_info, null)
        val infoSpeed = view.findViewById<TextView>(R.id.infoSpeed)
        val infoLimit = view.findViewById<TextView>(R.id.infoLimit)
        val infoRoad = view.findViewById<TextView>(R.id.infoRoad)
        val infoAddress = view.findViewById<TextView>(R.id.infoAddress)
        val liveSwitch = view.findViewById<Switch>(R.id.liveCameraSwitch)
        val livePreview = view.findViewById<PreviewView>(R.id.liveCameraPreview)

        infoSpeed.text = "Tốc độ hiện tại: ${s.currentSpeedKmh} km/h  (GPS ±${s.accuracyM?.toInt() ?: 0}m)"
        infoLimit.text = when {
            s.speedLimitKmh != null -> "Giới hạn tốc độ: ${s.speedLimitKmh} km/h " +
                if (s.limitFromSign) "(theo biển báo)" else "(theo luật cho ${AppSettings.vehicle.value.label.lowercase()})"
            s.roadNote.isNotBlank() -> s.roadNote
            else -> "Giới hạn tốc độ: chưa có dữ liệu đoạn này"
        }
        infoRoad.text = "Đoạn đường: ${s.roadName.ifBlank { "không rõ" }}"
        infoAddress.text = "Địa chỉ: đang tra cứu..."

        livePreview.visibility = View.GONE
        liveSwitch.text = "Mở camera hành trình & tự động quay"
        liveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                infoDialog?.dismiss()
                DashcamActivity.open(this, autoStart = true)
            }
        }

        infoDialog = AlertDialog.Builder(this)
            .setTitle("Vị trí của bạn")
            .setView(view)
            .setPositiveButton("Đóng", null)
            .show()

        lifecycleScope.launch {
            val addr = withContext(Dispatchers.IO) { SearchClient.reverse(applicationContext, GeoPoint(lat, lon)) }
            infoAddress.text = "Địa chỉ: ${addr ?: "không lấy được (mất mạng?)"}"
        }
    }

    private fun startLiveRecording(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            liveCameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val videoCapture = VideoCapture.withOutput(Recorder.Builder().build())
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "hanhtrinh_dinhvi_$timestamp")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedAlert")
                }
                val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values).build()
                liveRecording = videoCapture.output
                    .prepareRecording(this, output)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        if (event is VideoRecordEvent.Finalize && !event.hasError()) {
                            Toast.makeText(this, "Đã lưu video vào Movies/SpeedAlert", Toast.LENGTH_SHORT).show()
                        }
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera đang bận hoặc không mở được", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopLiveRecording() {
        liveRecording?.stop()
        liveRecording = null
        liveCameraProvider?.unbindAll()
    }

    // ---------------------------------------------------------------- Tiện ích

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resultsScroll.visibility == View.VISIBLE -> resultsScroll.visibility = View.GONE
            placeCard.visibility == View.VISIBLE -> clearPlace()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onDestroy() {
        stopLiveRecording()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
