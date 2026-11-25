package com.speedalert.app.core

import kotlin.math.abs

/**
 * Bộ lọc tốc độ ít trễ.
 *
 * Bản cũ làm mượt kiểu "trung bình trượt" quá nặng (chỉ lấy 30-55% số đo mới),
 * nên khi tăng tốc lên 40-80 km/h con số luôn đuổi theo chậm hơn thực tế, lại
 * cắt phần thập phân (59,9 → 59). Bộ lọc này:
 * - Tin gần như hoàn toàn tốc độ Doppler của chip GPS khi độ tin cậy tốt.
 * - Loại số đo nhảy vọt vô lý (xe máy không thể tăng/giảm > 4 m/s² liên tục).
 * - Giảm tốc thì bám nhanh hơn tăng tốc → dừng xe là về 0 ngay.
 * - Dưới ~4 km/h coi như đứng yên (GPS đứng yên hay "trôi" vài km/h).
 */
class SpeedFilter {
    /** Tốc độ đã lọc, m/s. */
    var speedMps = 0.0
        private set

    private var lastTimeNanos = 0L
    private var rejectedInRow = 0

    fun reset() {
        speedMps = 0.0
        lastTimeNanos = 0L
        rejectedInRow = 0
    }

    /**
     * @param rawMps tốc độ đo được (m/s)
     * @param accuracyMps độ lệch chuẩn của tốc độ (m/s), null nếu máy không cung cấp
     * @param timeNanos thời điểm đo (elapsedRealtimeNanos)
     */
    fun update(rawMps: Double, accuracyMps: Float?, timeNanos: Long): Double {
        val first = lastTimeNanos == 0L
        if (!first && timeNanos <= lastTimeNanos) return speedMps // số đo cũ/đến trễ
        val dt = if (first) 1.0 else ((timeNanos - lastTimeNanos) / 1e9).coerceIn(0.05, 10.0)
        lastTimeNanos = timeNanos
        val acc = accuracyMps?.toDouble() ?: 1.0

        // 1. Loại số đo nhảy vọt (vượt gia tốc vật lý của xe máy), trừ khi lặp lại liên tục.
        val maxDelta = 4.0 * dt + 2.0 * acc + 1.0
        if (abs(rawMps - speedMps) > maxDelta && rejectedInRow < 2 && !first && speedMps > 0.5) {
            rejectedInRow++
            return speedMps
        }
        rejectedInRow = 0

        // 2. Đứng yên.
        if (rawMps < 1.1) {
            speedMps = if (speedMps < 2.5) 0.0 else speedMps * 0.3
            return speedMps
        }

        // 3. Làm mượt nhẹ, nặng nhẹ theo độ tin cậy; giảm tốc bám nhanh hơn.
        val alpha = when {
            acc <= 0.6 -> 0.9
            acc <= 1.2 -> 0.8
            acc <= 2.5 -> 0.6
            else -> 0.4
        }.let { if (rawMps < speedMps) minOf(1.0, it + 0.1) else it }
        speedMps = alpha * rawMps + (1 - alpha) * speedMps
        return speedMps
    }
}
