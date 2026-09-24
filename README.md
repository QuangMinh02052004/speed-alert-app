# Speed Alert App — Giai đoạn 1 + 2

## Đây là gì
Source code Android Studio đầy đủ, gồm cả Giai đoạn 1 và Giai đoạn 2 đã chốt:
- Hiển thị tốc độ GPS hiện tại, so với giới hạn tốc độ của đoạn đường đang đi,
  đổi màu đỏ khi vượt quá.
- Cảnh báo âm thanh (ToneGenerator có sẵn, không cần file âm thanh) khi vượt tốc
  độ hoặc khi tới gần camera đã đánh dấu.
- Màn hình bản đồ (MapLibre, dùng style demo miễn phí để test) - chạm vào bản đồ
  để tự đánh dấu vị trí camera bắn tốc độ, lưu local qua Room, cảnh báo khi GPS
  tới gần (trong bán kính 300m) một camera đã lưu.
- Chạy nền qua foreground service + notification (hiện cả cảnh báo camera trong
  notification).

**Lưu ý về bản đồ:** style `demotiles.maplibre.org` là bản đồ demo miễn phí của
MapLibre, độ chi tiết thấp (không có tên đường/địa danh chi tiết ở VN) - đủ để
test tính năng chạm-để-đánh-dấu, nhưng nên đổi sang nguồn tile chi tiết hơn
(MapTiler, Stadia Maps, hoặc tự host từ OSM) khi làm bản chính thức.

## Vì sao chưa có sẵn file APK
Code này được viết bằng tay, KHÔNG được biên dịch/kiểm thử trong môi trường viết
ra nó (không có Android SDK, không có thiết bị/emulator). Nhiều khả năng chạy
đúng vì dùng toàn API/pattern phổ biến, ổn định, nhưng Android Studio có thể báo
vài lỗi nhỏ về phiên bản (Gradle/AGP/Kotlin) khi sync lần đầu — thường Android
Studio tự đề xuất nút "Fix" hoặc "Upgrade" cho các lỗi này.

## Cách lấy file APK KHÔNG CẦN máy tính (chỉ cần điện thoại + trình duyệt)
Project này đã có sẵn `.github/workflows/build.yml` để GitHub tự build APK giúp bạn.

1. Vào github.com trên điện thoại, đăng nhập (hoặc tạo tài khoản miễn phí nếu chưa có).
2. Tạo repository mới, ví dụ tên `speed-alert-app` (để Public cho đơn giản, không giới hạn phút build).
3. Mở repo vừa tạo → nút **Code** (màu xanh) → tab **Codespaces** → **Create codespace on main**.
   (Đây là VS Code chạy trên trình duyệt, không cần cài gì.)
4. Trong Codespaces, ở khung file bên trái (Explorer), bấm chuột phải vào khoảng trống →
   **Upload...** → chọn file `SpeedAlertApp.zip` bạn đã tải về từ đoạn chat trước.
5. Mở **Terminal** trong Codespaces (menu Terminal > New Terminal), gõ lần lượt:
   ```
   unzip SpeedAlertApp.zip
   shopt -s dotglob && mv SpeedAlertApp/* . && rmdir SpeedAlertApp
   git add -A
   git commit -m "add project"
   git push
   ```
6. Quay lại trang repo trên github.com → tab **Actions** → sẽ thấy 1 lượt chạy đang thực hiện
   (mất khoảng 3-5 phút). Chờ tới khi có dấu ✔ xanh.
7. Bấm vào lượt chạy đó → kéo xuống mục **Artifacts** → tải file
   `speed-alert-debug-apk` (đây là file .zip chứa file .apk bên trong).
8. Giải nén trên điện thoại (hầu hết ứng dụng quản lý file Android đều giải nén được),
   bấm vào file `app-debug.apk` để cài (có thể cần bật "Cho phép cài từ nguồn này"
   trong Cài đặt nếu điện thoại chặn mặc định).

**Lưu ý:** trước bước 5, nếu muốn app có dữ liệu tốc độ thật ngay từ đầu thay vì mảng
rỗng, hãy chạy `speed_limit_data_pipeline.py` trước (trên bất kỳ máy nào có Python,
hoặc nhờ mình chạy giúp phần xử lý dữ liệu) rồi thay nội dung
`app/src/main/assets/speed_limits.json` trước khi `git push`.

## Cách lấy file APK khi có máy tính (khoảng 10-15 phút)
1. Cài Android Studio (nếu chưa có): https://developer.android.com/studio
2. Mở thư mục `SpeedAlertApp` này bằng Android Studio (File > Open).
3. Chờ Gradle sync xong (lần đầu sẽ tự tải Android SDK/Gradle cần thiết).
4. **Trước khi build**, chạy file `speed_limit_data_pipeline.py` (đã gửi ở bước
   trước) để tạo ra `speed_limits.json` cho khu vực bạn muốn test, rồi thay thế
   nội dung file `app/src/main/assets/speed_limits.json` (hiện đang là mảng rỗng
   `[]` để không bị crash khi chưa có dữ liệu thật).
5. Cắm điện thoại Android qua USB, bật "USB debugging" trong Developer options.
6. Bấm nút Run (▶) trong Android Studio — app sẽ build và cài thẳng vào điện thoại.
7. Muốn file .apk rời để cài thủ công: Build > Build Bundle(s)/APK(s) > Build APK(s),
   file xuất ra ở `app/build/outputs/apk/debug/app-debug.apk`.

## Giới hạn đã biết của MVP này
- Tra cứu giới hạn tốc độ đang dùng linear scan qua toàn bộ điểm dữ liệu — chỉ
  đủ nhanh cho vùng nhỏ (vd. 1 quận), cần tối ưu (spatial index) nếu mở rộng.
- Không có bản đồ hiển thị, chỉ hiện số tốc độ + tên đường gần nhất.
- Không có cảnh báo âm thanh (chỉ đổi màu), chưa có tính năng đánh dấu camera.

## Nếu Android Studio báo lỗi
Copy nguyên văn lỗi và đưa lại cho Claude (ở đây, hoặc trong Claude Code) — sửa
lỗi build cụ thể thường nhanh hơn nhiều so với viết lại từ đầu.
