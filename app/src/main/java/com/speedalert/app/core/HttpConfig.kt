package com.speedalert.app.core

/**
 * Các dịch vụ miễn phí của cộng đồng OpenStreetMap (Overpass, Nominatim, OSRM,
 * Valhalla) yêu cầu User-Agent nhận diện được ứng dụng, thiếu thì có thể bị
 * từ chối (một nguyên nhân của lỗi "không tính được đường đi" trước đây).
 */
object HttpConfig {
    const val USER_AGENT = "SpeedAlertApp/2.0 (Android; github.com/QuangMinh02052004/speed-alert-app)"
}
