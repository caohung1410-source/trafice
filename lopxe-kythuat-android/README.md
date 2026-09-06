# APK Kỹ thuật Lốp Xe

Ứng dụng máy tính bảng Android 15–16 dành riêng cho nhân viên kỹ thuật.

## Kết nối

- Tự động: thử `lopxe.local`, `lopxe-server.local` qua HTTP nội bộ; địa chỉ IP được kiểm tra bằng HTTPS cổng 443/8443.
- Thủ công: nhấn **Cấu hình thủ công**, nhập `https://IP-MAY-CHU:CONG`, kiểm tra và lưu.
- Nhấn giữ trên màn hình ứng dụng để mở lại cấu hình máy chủ.

Máy chủ phải cung cấp ứng dụng tại `/` và API kiểm tra tại `/api/auth/me`.
HTTP không mã hóa chỉ được cho phép với đúng hai tên nội bộ `lopxe.local` và `lopxe-server.local`.

## Đóng gói

Mở thư mục `android-tech` bằng Android Studio, chọn Build > Generate App Bundles or APKs > Generate APKs.
