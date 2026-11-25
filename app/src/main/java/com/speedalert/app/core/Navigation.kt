package com.speedalert.app.core

import com.speedalert.app.data.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Trạng thái dẫn đường hiển thị cho UI (banner, thanh dưới, chấm định vị bám tuyến). */
data class NavState(
    val active: Boolean = false,
    val route: Route? = null,
    val destination: GeoPoint? = null,
    val destinationName: String = "",
    val nextStep: RouteStep? = null,
    val distanceToNextStep: Double = 0.0,
    val remainingDistance: Double = 0.0,
    val remainingSeconds: Double = 0.0,
    /** Vị trí đã "dán" lên tuyến đường (để chấm định vị chạy mượt theo tuyến). */
    val snapped: GeoPoint? = null,
    val snappedBearing: Double? = null,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false
)

/** Câu cần đọc, do NavigationManager sinh ra khi tiến tới các mốc. */
data class Announcement(val text: String, val urgent: Boolean = false)

/**
 * Bộ não dẫn đường, chạy trong Service (nên vẫn đọc giọng nói khi tắt màn hình).
 * Mỗi lần có vị trí: chiếu lên tuyến → tính quãng đã đi, bước rẽ tiếp theo,
 * khoảng cách, thời gian còn lại; quyết định câu cần đọc; phát hiện đi lệch tuyến.
 */
object NavigationManager {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private var lastIndex = 0
    private var offRouteCount = 0
    private val announcedFar = HashSet<Int>()
    private val announcedNear = HashSet<Int>()
    private var lastRerouteAt = 0L
    private var pendingStart: Announcement? = null

    fun start(route: Route, destination: GeoPoint, name: String) {
        lastIndex = 0
        offRouteCount = 0
        announcedFar.clear()
        announcedNear.clear()
        val first = route.steps.firstOrNull { it.pointIndex > 0 }
        pendingStart = Announcement(
            "Bắt đầu dẫn đường đến $name, quãng đường ${roundDistance(route.distanceMeters)}." +
                (first?.let { " ${it.text()}." } ?: ""),
            true
        )
        _state.value = NavState(
            active = true, route = route, destination = destination, destinationName = name,
            nextStep = route.steps.firstOrNull { it.pointIndex > 0 } ?: route.steps.lastOrNull(),
            remainingDistance = route.distanceMeters,
            remainingSeconds = route.durationSeconds
        )
    }

    /** Thay tuyến mới sau khi tính lại (giữ đích cũ). */
    fun replaceRoute(route: Route) {
        val s = _state.value
        if (!s.active || s.destination == null) return
        lastIndex = 0
        offRouteCount = 0
        announcedFar.clear()
        announcedNear.clear()
        _state.value = s.copy(route = route, rerouting = false, offRoute = false)
    }

    fun setRerouting(value: Boolean) {
        _state.value = _state.value.copy(rerouting = value)
    }

    fun stop() {
        pendingStart = null
        _state.value = NavState()
    }

    /** true nếu nên tính lại tuyến ngay (đi lệch tuyến liên tục). */
    fun shouldReroute(): Boolean {
        val s = _state.value
        val now = System.currentTimeMillis()
        if (!s.active || s.rerouting || !s.offRoute) return false
        if (now - lastRerouteAt < 15_000) return false
        lastRerouteAt = now
        return true
    }

    /**
     * Cập nhật theo vị trí mới. Trả về danh sách câu cần đọc (có thể rỗng).
     */
    fun onLocation(p: GeoPoint, accuracy: Float, speedMps: Double): List<Announcement> {
        val s = _state.value
        val route = s.route ?: return emptyList()
        if (!s.active) return emptyList()
        val out = mutableListOf<Announcement>()
        pendingStart?.let { out.add(it) }
        pendingStart = null

        // Tìm vị trí trên tuyến trong một cửa sổ quanh vị trí trước đó (tránh nhảy
        // nhầm sang đoạn tuyến đi qua lại gần nhau).
        val windowEnd = (lastIndex + 80).coerceAtMost(route.points.size - 1)
        val windowStart = (lastIndex - 5).coerceAtLeast(0)
        var proj = GeoUtils.projectOnLine(p, route.points, windowStart, windowEnd)
        if (proj == null || proj.distance > 60) {
            val global = GeoUtils.projectOnLine(p, route.points)
            if (global != null && (proj == null || global.distance < proj.distance)) proj = global
        }
        if (proj == null) return out

        val offThreshold = maxOf(40.0, accuracy.toDouble() * 1.5)
        if (proj.distance > offThreshold) offRouteCount++ else offRouteCount = 0
        val offRoute = offRouteCount >= 3
        if (offRoute && !s.offRoute) out.add(Announcement("Bạn đã đi lệch tuyến, đang tính lại đường đi", true))

        lastIndex = proj.segmentIndex
        val segLen = route.cumulative[proj.segmentIndex + 1] - route.cumulative[proj.segmentIndex]
        val traveled = route.cumulative[proj.segmentIndex] + segLen * proj.fraction
        val totalLen = route.cumulative.last().coerceAtLeast(1.0)
        val remaining = (totalLen - traveled).coerceAtLeast(0.0)
        val remainingSec = route.durationSeconds * (remaining / totalLen)

        val nextIdx = route.steps.indexOfFirst { it.pointIndex > proj.segmentIndex }
        val next = if (nextIdx >= 0) route.steps[nextIdx] else route.steps.lastOrNull()
        val distToNext = if (next != null) {
            (route.cumulative[next.pointIndex.coerceAtMost(route.cumulative.size - 1)] - traveled).coerceAtLeast(0.0)
        } else remaining

        // Mốc đọc: xa (~ 400m khi đi nhanh / 250m khi chậm) và gần (~ 60-100m).
        if (next != null && nextIdx >= 0 && next.kind != ManeuverKind.DEPART) {
            val far = if (speedMps > 13) 500.0 else 250.0
            val near = if (speedMps > 13) 120.0 else 60.0
            if (distToNext in (near + 40)..far && announcedFar.add(nextIdx)) {
                out.add(Announcement("Khoảng ${roundDistance(distToNext)} nữa, ${next.text().lowercaseFirst()}"))
            } else if (distToNext <= near && announcedNear.add(nextIdx)) {
                announcedFar.add(nextIdx)
                out.add(Announcement(if (next.kind == ManeuverKind.ARRIVE) "Sắp đến nơi" else next.text(), true))
            }
        }

        var arrived = false
        val dest = s.destination
        if (dest != null && (remaining < 25 || GeoUtils.distance(p, dest) < 20)) {
            arrived = true
            out.add(Announcement("Bạn đã đến nơi", true))
        }

        _state.value = s.copy(
            nextStep = next,
            distanceToNextStep = distToNext,
            remainingDistance = remaining,
            remainingSeconds = remainingSec,
            snapped = if (proj.distance <= 30) proj.point else null,
            snappedBearing = if (proj.distance <= 30) proj.bearing else null,
            offRoute = offRoute,
            arrived = arrived,
            active = !arrived
        )
        return out
    }

    fun roundDistance(m: Double): String = when {
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f ki lô mét", m / 1000).replace('.', ',')
        m >= 100 -> "${(Math.round(m / 50.0) * 50).toInt()} mét"
        else -> "${(Math.round(m / 10.0) * 10).toInt()} mét"
    }

    fun formatDistance(m: Double): String = when {
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
        m >= 100 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
        else -> "${m.toInt()} m"
    }

    private fun String.lowercaseFirst(): String =
        if (isEmpty()) this else this[0].lowercaseChar() + substring(1)
}
