package com.speedalert.app.core

import android.content.Context
import android.util.Log
import com.speedalert.app.data.AppDatabase
import com.speedalert.app.data.CameraLocation
import com.speedalert.app.data.GeoPoint
import com.speedalert.app.data.RoadSegment
import com.speedalert.app.data.TileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor

/** Đoạn đường đã nạp vào bộ nhớ, kèm khung bao để lọc nhanh. */
class PreparedSegment(val seg: RoadSegment) {
    val minLat = seg.geometry.minOf { it.lat }
    val maxLat = seg.geometry.maxOf { it.lat }
    val minLon = seg.geometry.minOf { it.lon }
    val maxLon = seg.geometry.maxOf { it.lon }
}

data class MatchResult(
    val segment: RoadSegment,
    val distance: Double,
    val snapped: GeoPoint,
    val roadBearing: Double
)

/**
 * Tự tải và cập nhật dữ liệu đường / giới hạn tốc độ / camera phạt nguội từ
 * OpenStreetMap (Overpass API, miễn phí) theo từng ô ~2,8 km quanh vị trí, lưu
 * vào Room để lần sau dùng offline, tự làm mới khi dữ liệu cũ hơn 30 ngày.
 */
class RoadDataManager(context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "RoadData"
        const val TILE_DEG = 0.025
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val MATCH_MAX_DIST = 45.0

        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )

        private const val HIGHWAY_REGEX =
            "^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
                "tertiary|tertiary_link|unclassified|residential|living_street|road)$"

        fun tileKeyOf(lat: Double, lon: Double): String =
            "${floor(lat / TILE_DEG).toInt()}_${floor(lon / TILE_DEG).toInt()}"

        fun neighborKeys(lat: Double, lon: Double): List<String> {
            val ty = floor(lat / TILE_DEG).toInt()
            val tx = floor(lon / TILE_DEG).toInt()
            val keys = mutableListOf("${ty}_${tx}")
            for (dy in -1..1) for (dx in -1..1) {
                if (dy == 0 && dx == 0) continue
                keys.add("${ty + dy}_${tx + dx}")
            }
            return keys
        }

        private fun boundsOf(key: String): DoubleArray {
            val (ty, tx) = key.split('_').map { it.toInt() }
            return doubleArrayOf(ty * TILE_DEG, tx * TILE_DEG, (ty + 1) * TILE_DEG, (tx + 1) * TILE_DEG)
        }
    }

    private val db = AppDatabase.getInstance(context)
    private val roadDao = db.roadDao()
    private val cameraDao = db.cameraDao()

    @Volatile private var segments: List<PreparedSegment> = emptyList()
    @Volatile var cameras: List<CameraLocation> = emptyList()
        private set

    private val _status = MutableStateFlow("Chưa có dữ liệu đường")
    val status: StateFlow<String> = _status

    /** Tăng mỗi khi dữ liệu trong bộ nhớ thay đổi (để bản đồ vẽ lại camera). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val downloadMutex = Mutex()
    private var centerKey: String? = null
    private var areaJob: Job? = null
    private val failedAt = HashMap<String, Long>()
    private var lastMatchId: Long? = null

    /** Gọi mỗi lần có vị trí mới: đảm bảo khu vực xung quanh đã có dữ liệu. */
    fun ensureArea(lat: Double, lon: Double) {
        val key = tileKeyOf(lat, lon)
        if (key == centerKey && areaJob?.isActive == true) return
        if (key == centerKey && segments.isNotEmpty()) return
        centerKey = key
        areaJob = scope.launch(Dispatchers.IO) {
            val keys = neighborKeys(lat, lon)
            loadIntoMemory(keys)
            var downloaded = 0
            for (k in keys) {
                val info = roadDao.getTile(k)
                val stale = info == null || System.currentTimeMillis() - info.fetchedAt > MAX_AGE_MS
                val recentlyFailed = (failedAt[k] ?: 0L) > System.currentTimeMillis() - 60_000
                if (stale && !recentlyFailed) {
                    if (downloadTile(k)) downloaded++
                    loadIntoMemory(keys)
                }
            }
            updateStatus(downloaded)
        }
    }

    /** Buộc tải lại ô hiện tại (nút "Cập nhật dữ liệu khu vực"). */
    fun forceRefresh(lat: Double, lon: Double, onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val keys = neighborKeys(lat, lon)
            val ok = downloadTile(keys.first())
            loadIntoMemory(keys)
            updateStatus(if (ok) 1 else 0)
            onDone(ok)
        }
    }

    private fun updateStatus(downloaded: Int) {
        val n = segments.size
        _status.value = when {
            n == 0 && failedAt.isNotEmpty() -> "Không tải được dữ liệu đường (kiểm tra mạng)"
            n == 0 -> "Chưa có dữ liệu đường khu vực này"
            else -> "Dữ liệu: $n đoạn đường, ${cameras.size} camera" +
                if (downloaded > 0) " (vừa cập nhật)" else ""
        }
    }

    private suspend fun loadIntoMemory(keys: List<String>) {
        val segs = roadDao.getByTiles(keys)
            .distinctBy { it.osmId }
            .filter { it.geometry.size >= 2 }
            .map { PreparedSegment(it) }
        val cams = cameraDao.getByTiles(keys).distinctBy { if (it.osmId != 0L) it.osmId else it.id }
        segments = segs
        cameras = cams
        _version.value = _version.value + 1
    }

    private suspend fun downloadTile(key: String): Boolean = downloadMutex.withLock {
        _status.value = "Đang tải dữ liệu đường khu vực..."
        val b = boundsOf(key)
        val bbox = String.format(java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f", b[0], b[1], b[2], b[3])
        val query = """
            [out:json][timeout:40];
            (way["highway"~"$HIGHWAY_REGEX"]($bbox);)->.w;
            (node["highway"="speed_camera"]($bbox);)->.c;
            .w out tags geom;
            .c out body;
        """.trimIndent()

        for (endpoint in OVERPASS_ENDPOINTS) {
            try {
                val body = postOverpass(endpoint, query) ?: continue
                val (segs, cams) = parseOverpass(body, key)
                roadDao.replaceTile(key, segs, TileInfo(key, System.currentTimeMillis(), segs.size, cams.size))
                cameraDao.deleteTile(key)
                if (cams.isNotEmpty()) cameraDao.insertAll(cams)
                failedAt.remove(key)
                Log.d(TAG, "Tile $key: ${segs.size} segments, ${cams.size} cameras from $endpoint")
                return@withLock true
            } catch (e: Exception) {
                Log.w(TAG, "Overpass $endpoint failed for $key", e)
            }
        }
        failedAt[key] = System.currentTimeMillis()
        false
    }

    private fun postOverpass(endpoint: String, query: String): String? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", HttpConfig.USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Overpass HTTP $code from $endpoint")
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOverpass(body: String, key: String): Pair<List<RoadSegment>, List<CameraLocation>> {
        val elements = JSONObject(body).getJSONArray("elements")
        data class RawWay(val id: Long, val tags: JSONObject, val geom: List<GeoPoint>)

        val ways = ArrayList<RawWay>()
        val cams = ArrayList<CameraLocation>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: JSONObject()
            when (el.optString("type")) {
                "way" -> {
                    val g = el.optJSONArray("geometry") ?: continue
                    val pts = ArrayList<GeoPoint>(g.length())
                    for (j in 0 until g.length()) {
                        val p = g.optJSONObject(j) ?: continue
                        pts.add(GeoPoint(p.getDouble("lat"), p.getDouble("lon")))
                    }
                    if (pts.size >= 2) ways.add(RawWay(el.getLong("id"), tags, pts))
                }
                "node" -> {
                    if (el.has("lat") && el.has("lon")) {
                        cams.add(
                            CameraLocation(
                                lat = el.getDouble("lat"),
                                lon = el.getDouble("lon"),
                                osmId = el.getLong("id"),
                                tileKey = key,
                                maxspeed = SpeedLimitRules.parseMaxspeed(tags.optString("maxspeed")) ?: 0
                            )
                        )
                    }
                }
            }
        }

        // Mật độ đường dân sinh quanh mỗi điểm: cách gần đúng để biết "khu đông dân cư"
        // khi bản đồ không ghi rõ (Vietmap dùng dữ liệu biển báo thu thập thực địa).
        val cell = 0.003
        val density = HashMap<Long, Int>()
        fun cellId(lat: Double, lon: Double): Long =
            floor(lat / cell).toLong() * 1_000_000L + floor(lon / cell).toLong()
        for (w in ways) {
            val h = w.tags.optString("highway")
            if (h == "residential" || h == "living_street") {
                val mid = w.geom[w.geom.size / 2]
                val id = cellId(mid.lat, mid.lon)
                density[id] = (density[id] ?: 0) + 1
            }
        }
        fun densityAround(p: GeoPoint): Int {
            val cy = floor(p.lat / cell).toLong()
            val cx = floor(p.lon / cell).toLong()
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += density[(cy + dy) * 1_000_000L + (cx + dx)] ?: 0
            return sum
        }

        val segs = ways.map { w ->
            val h = w.tags.optString("highway")
            val maxspeed = w.tags.optString("maxspeed")
            val zoneTag = SpeedLimitRules.zoneFromTag(w.tags.optString("maxspeed:type"))
                ?: SpeedLimitRules.zoneFromTag(w.tags.optString("source:maxspeed"))
                ?: SpeedLimitRules.zoneFromTag(maxspeed)
            val d = densityAround(w.geom[w.geom.size / 2])
            val urban = zoneTag ?: when (h) {
                "residential", "living_street" -> true
                "tertiary", "tertiary_link", "unclassified", "road" -> d >= 5
                "motorway", "motorway_link" -> false
                else -> d >= 10
            }
            val oneway = w.tags.optString("oneway").let { it == "yes" || it == "1" || it == "-1" } ||
                h == "motorway" || w.tags.optString("junction") == "roundabout"
            RoadSegment(
                osmId = w.id,
                tileKey = key,
                name = w.tags.optString("name").ifBlank { w.tags.optString("ref") },
                highwayType = h,
                maxspeedRaw = maxspeed,
                maxspeedMotoRaw = w.tags.optString("maxspeed:motorcycle"),
                lanes = w.tags.optString("lanes").toIntOrNull() ?: 0,
                oneway = oneway,
                urban = urban,
                geometry = if (w.tags.optString("oneway") == "-1") w.geom.reversed() else w.geom
            )
        }
        return segs to cams
    }

    /**
     * Khớp vị trí GPS vào đoạn đường phù hợp nhất: gần nhất + cùng hướng di chuyển,
     * có "độ trễ" để không nhảy qua lại giữa 2 đường song song / đường gom.
     */
    fun match(p: GeoPoint, bearing: Float?, speedKmh: Double): MatchResult? {
        val margin = 0.0005
        var best: MatchResult? = null
        var bestScore = Double.MAX_VALUE
        var lastScore = Double.MAX_VALUE
        var lastResult: MatchResult? = null

        for (ps in segments) {
            if (p.lat < ps.minLat - margin || p.lat > ps.maxLat + margin ||
                p.lon < ps.minLon - margin || p.lon > ps.maxLon + margin
            ) continue
            val proj = GeoUtils.projectOnLine(p, ps.seg.geometry) ?: continue
            if (proj.distance > MATCH_MAX_DIST) continue

            var score = proj.distance
            if (bearing != null && speedKmh > 8) {
                var diff = GeoUtils.angleDiff(bearing.toDouble(), proj.bearing)
                if (!ps.seg.oneway) diff = minOf(diff, 180 - diff)
                score += diff / 90.0 * 30.0
                if (ps.seg.oneway && diff > 100) score += 40.0
            }
            val result = MatchResult(ps.seg, proj.distance, proj.point, proj.bearing)
            if (score < bestScore) {
                bestScore = score
                best = result
            }
            if (ps.seg.osmId == lastMatchId) {
                lastScore = score
                lastResult = result
            }
        }
        val chosen = if (lastResult != null && lastScore <= bestScore + 10.0) lastResult else best
        lastMatchId = chosen?.segment?.osmId
        return chosen
    }

    fun hasData(): Boolean = segments.isNotEmpty()
}
