package com.speedalert.app.core

import android.util.Log
import com.speedalert.app.data.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ManeuverKind {
    DEPART, ARRIVE, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, ROUNDABOUT, EXIT_ROUNDABOUT, KEEP_LEFT, KEEP_RIGHT, MERGE, RAMP, FERRY;

    /** Mũi tên hiển thị trên banner dẫn đường. */
    val arrow: String
        get() = when (this) {
            DEPART, STRAIGHT, MERGE, RAMP, FERRY -> "↑"
            ARRIVE -> "⚑"
            SLIGHT_LEFT, KEEP_LEFT -> "↖"
            LEFT -> "←"
            SHARP_LEFT -> "↙"
            SLIGHT_RIGHT, KEEP_RIGHT -> "↗"
            RIGHT -> "→"
            SHARP_RIGHT -> "↘"
            UTURN -> "↶"
            ROUNDABOUT, EXIT_ROUNDABOUT -> "⟳"
        }

    /** Động từ tiếng Việt cho câu dẫn đường. */
    val verb: String
        get() = when (this) {
            DEPART -> "Xuất phát"
            ARRIVE -> "Đến nơi"
            STRAIGHT -> "Đi thẳng"
            SLIGHT_LEFT -> "Chếch trái"
            LEFT -> "Rẽ trái"
            SHARP_LEFT -> "Rẽ gắt sang trái"
            SLIGHT_RIGHT -> "Chếch phải"
            RIGHT -> "Rẽ phải"
            SHARP_RIGHT -> "Rẽ gắt sang phải"
            UTURN -> "Quay đầu"
            ROUNDABOUT -> "Vào vòng xuyến"
            EXIT_ROUNDABOUT -> "Ra khỏi vòng xuyến"
            KEEP_LEFT -> "Đi bên trái"
            KEEP_RIGHT -> "Đi bên phải"
            MERGE -> "Nhập làn"
            RAMP -> "Vào đường nhánh"
            FERRY -> "Lên phà"
        }
}

data class RouteStep(
    val kind: ManeuverKind,
    val street: String,
    /** Chỉ số điểm trên tuyến nơi thực hiện bước rẽ này. */
    val pointIndex: Int,
    /** Số lối ra vòng xuyến (0 nếu không có). */
    val roundaboutExit: Int = 0
) {
    fun text(): String {
        val base = when {
            kind == ManeuverKind.ROUNDABOUT && roundaboutExit > 0 -> "Vào vòng xuyến, đi lối ra thứ $roundaboutExit"
            else -> kind.verb
        }
        return if (street.isNotBlank() && kind != ManeuverKind.ARRIVE) "$base vào $street" else base
    }
}

data class Route(
    val points: List<GeoPoint>,
    val cumulative: DoubleArray,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val provider: String
)

/**
 * Tính đường đi. Thứ tự: Valhalla (hồ sơ motor_scooter - phù hợp xe máy Việt
 * Nam, tự tránh cao tốc) → OSRM (loại trừ cao tốc) → OSRM thường.
 * Cả hai là máy chủ cộng đồng miễn phí của OpenStreetMap, không cần API key.
 */
object RoutingClient {
    private const val TAG = "Routing"
    var lastError: String? = null
        private set

    fun route(start: GeoPoint, end: GeoPoint, vehicle: VehicleType, startBearing: Float?): Route? {
        lastError = null
        val errors = mutableListOf<String>()
        try {
            valhalla(start, end, vehicle, startBearing)?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Valhalla failed", e)
            errors.add("Valhalla: ${e.message}")
        }
        val excludeMotorway = vehicle != VehicleType.CAR
        try {
            osrm(start, end, excludeMotorway)?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM failed", e)
            errors.add("OSRM: ${e.message}")
        }
        if (excludeMotorway) {
            try {
                osrm(start, end, false)?.let { return it }
            } catch (e: Exception) {
                errors.add("OSRM2: ${e.message}")
            }
        }
        lastError = errors.joinToString("; ").ifBlank { "không có tuyến đường phù hợp" }
        return null
    }

    private fun httpPostJson(url: String, json: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", HttpConfig.USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", HttpConfig.USER_AGENT)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- Valhalla ----------------

    private fun valhalla(start: GeoPoint, end: GeoPoint, vehicle: VehicleType, startBearing: Float?): Route? {
        val startLoc = JSONObject().put("lat", start.lat).put("lon", start.lon).put("type", "break")
        if (startBearing != null) {
            startLoc.put("heading", startBearing.toInt()).put("heading_tolerance", 60)
        }
        val costing = if (vehicle == VehicleType.CAR) "auto" else "motor_scooter"
        val costingOptions = JSONObject()
        if (costing == "motor_scooter") {
            costingOptions.put(
                "motor_scooter",
                JSONObject()
                    .put("top_speed", if (vehicle == VehicleType.MOPED) 40 else 60)
                    .put("use_primary", 0.6)
                    .put("use_hills", 0.5)
            )
        }
        val req = JSONObject()
            .put("locations", JSONArray().put(startLoc).put(JSONObject().put("lat", end.lat).put("lon", end.lon)))
            .put("costing", costing)
            .put("costing_options", costingOptions)
            .put("units", "kilometers")

        val (code, body) = httpPostJson("https://valhalla1.openstreetmap.de/route", req.toString())
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(120)}")

        val trip = JSONObject(body).getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = decodePolyline(leg.getString("shape"), 6)
        if (points.size < 2) return null

        val steps = mutableListOf<RouteStep>()
        val maneuvers = leg.getJSONArray("maneuvers")
        for (i in 0 until maneuvers.length()) {
            val m = maneuvers.getJSONObject(i)
            val names = m.optJSONArray("street_names")
            val street = if (names != null && names.length() > 0) names.getString(0) else ""
            steps.add(
                RouteStep(
                    kind = valhallaKind(m.optInt("type")),
                    street = street,
                    pointIndex = m.optInt("begin_shape_index").coerceIn(0, points.size - 1),
                    roundaboutExit = m.optInt("roundabout_exit_count", 0)
                )
            )
        }
        val summary = trip.getJSONObject("summary")
        val cumulative = GeoUtils.cumulativeDistances(points)
        return Route(
            points, cumulative,
            summary.getDouble("length") * 1000.0,
            summary.getDouble("time"),
            steps, "Valhalla (xe máy)"
        )
    }

    private fun valhallaKind(type: Int): ManeuverKind = when (type) {
        1, 2, 3 -> ManeuverKind.DEPART
        4, 5, 6 -> ManeuverKind.ARRIVE
        7, 8, 22 -> ManeuverKind.STRAIGHT
        9 -> ManeuverKind.SLIGHT_RIGHT
        10 -> ManeuverKind.RIGHT
        11 -> ManeuverKind.SHARP_RIGHT
        12, 13 -> ManeuverKind.UTURN
        14 -> ManeuverKind.SHARP_LEFT
        15 -> ManeuverKind.LEFT
        16 -> ManeuverKind.SLIGHT_LEFT
        17, 18, 19, 20, 21 -> ManeuverKind.RAMP
        23 -> ManeuverKind.KEEP_RIGHT
        24 -> ManeuverKind.KEEP_LEFT
        25, 37, 38 -> ManeuverKind.MERGE
        26 -> ManeuverKind.ROUNDABOUT
        27 -> ManeuverKind.EXIT_ROUNDABOUT
        28, 29 -> ManeuverKind.FERRY
        else -> ManeuverKind.STRAIGHT
    }

    // ---------------- OSRM ----------------

    private fun osrm(start: GeoPoint, end: GeoPoint, excludeMotorway: Boolean): Route? {
        val exclude = if (excludeMotorway) "&exclude=motorway" else ""
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${start.lon},${start.lat};${end.lon},${end.lat}" +
            "?overview=full&geometries=polyline6&steps=true$exclude"
        val (code, body) = httpGet(url)
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299 || parsed == null || parsed.optString("code") != "Ok") {
            throw IllegalStateException("HTTP $code ${parsed?.optString("code") ?: ""} ${parsed?.optString("message") ?: ""}")
        }
        val json: JSONObject = parsed
        val route = json.getJSONArray("routes").getJSONObject(0)
        val points = decodePolyline(route.getString("geometry"), 6)
        if (points.size < 2) return null
        val cumulative = GeoUtils.cumulativeDistances(points)

        val steps = mutableListOf<RouteStep>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val arr = legs.getJSONObject(i).getJSONArray("steps")
            for (j in 0 until arr.length()) {
                val s = arr.getJSONObject(j)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                val idx = GeoUtils.projectOnLine(p, points)?.let {
                    if (it.fraction > 0.5) it.segmentIndex + 1 else it.segmentIndex
                } ?: 0
                steps.add(
                    RouteStep(
                        kind = osrmKind(m.optString("type"), m.optString("modifier")),
                        street = s.optString("name"),
                        pointIndex = idx,
                        roundaboutExit = m.optInt("exit", 0)
                    )
                )
            }
        }
        return Route(
            points, cumulative,
            route.getDouble("distance"), route.getDouble("duration"),
            steps, if (excludeMotorway) "OSRM (tránh cao tốc)" else "OSRM"
        )
    }

    private fun osrmKind(type: String, modifier: String): ManeuverKind {
        when (type) {
            "depart" -> return ManeuverKind.DEPART
            "arrive" -> return ManeuverKind.ARRIVE
            "roundabout", "rotary" -> return ManeuverKind.ROUNDABOUT
            "exit roundabout", "exit rotary" -> return ManeuverKind.EXIT_ROUNDABOUT
            "merge" -> return ManeuverKind.MERGE
            "on ramp", "off ramp" -> return ManeuverKind.RAMP
        }
        return when (modifier) {
            "left" -> ManeuverKind.LEFT
            "right" -> ManeuverKind.RIGHT
            "sharp left" -> ManeuverKind.SHARP_LEFT
            "sharp right" -> ManeuverKind.SHARP_RIGHT
            "slight left" -> if (type == "fork") ManeuverKind.KEEP_LEFT else ManeuverKind.SLIGHT_LEFT
            "slight right" -> if (type == "fork") ManeuverKind.KEEP_RIGHT else ManeuverKind.SLIGHT_RIGHT
            "uturn" -> ManeuverKind.UTURN
            else -> ManeuverKind.STRAIGHT
        }
    }

    /** Giải mã encoded polyline (precision 6 cho Valhalla / OSRM polyline6). */
    fun decodePolyline(encoded: String, precision: Int): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }
}
