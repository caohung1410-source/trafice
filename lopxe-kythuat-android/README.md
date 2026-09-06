# APK Kỹ thuật Lốp Xe

Ứng dụng máy tính bảng Android 15–16 dành riêng cho nhân viên kỹ thuật.

## Kết nối

- Tự động: thử `lopxe.local`, `lopxe-server.local`, cổng 80/8080 và các địa chỉ máy chủ thường dùng trong lớp mạng Wi‑Fi hiện tại.
- Thủ công: nhấn **Cấu hình thủ công**, nhập `http://IP-MAY-CHU:CONG`, kiểm tra và lưu.
- Nhấn giữ trên màn hình ứng dụng để mở lại cấu hình máy chủ.

Máy chủ phải cung cấp ứng dụng tại `/` và API kiểm tra tại `/api/auth/me`.

## Đóng gói

Mở thư mục `android-tech` bằng Android Studio, chọn Build > Generate App Bundles or APKs > Generate APKs.
