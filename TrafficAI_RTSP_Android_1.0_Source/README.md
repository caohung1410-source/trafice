# TrafficAI RTSP 1.3 • Geometry + Audio + Android Auto

Ứng dụng Android nhận luồng RTSP, nhận diện biển báo giao thông Việt Nam, nhận màu đèn tín hiệu và đọc số LED đếm ngược. Dự án được thiết kế trước hết cho Samsung S23 Ultra + IMOU IPC-C22E-A, đồng thời cho phép nhập URL của camera RTSP H.264 khác.

## Bổ sung trong bản 1.3

- Tắt hẳn audio track và volume của luồng RTSP để tiếng mic camera không che giọng cảnh báo.
- Mã hóa các chiều cao lắp đặt trong QCVN 41:2024/BGTVT thành prior hình học mềm: ưu tiên cột bên phải và vùng treo cao, vẫn giữ biển/đèn giữa hoặc bên trái.
- Tile phóng đại bên phải chạy 50% số lượt; đèn trên cần vươn vẫn được nhận từ full-frame và tile giữa.
- Khi model và phân tích màu kết luận ngược nhau, không đọc màu đèn đó; đèn xanh cyan được chấp nhận tốt hơn.
- Bổ sung icon Car App và quyền `NAVIGATION_TEMPLATES` còn thiếu trong manifest Android Auto.

Chiều cao thực không thể đổi chính xác thành tọa độ pixel nếu chưa hiệu chuẩn độ cao/góc camera, tiêu cự và khoảng cách tới cột. Vì vậy bản này chỉ dùng số liệu làm prior, không loại cứng các vùng khác.

## Bổ sung trong bản 1.2 Auto Test

- GPS trên điện thoại hiển thị tốc độ thực tế theo km/h và làm mượt nhiễu ngắn.
- Tự nhận giới hạn tốc độ khi AI thấy biển phù hợp; có nút đặt nhanh 40/50/60/80 khi chưa nhận được biển.
- Cảnh báo giọng nói nếu vượt quá giới hạn hơn 5 km/h trong ít nhất 1,8 giây.
- Màn hình GPS offline trên điện thoại vẽ hướng và vệt di chuyển cục bộ; không gửi tọa độ ra máy chủ. Bản thử nghiệm chưa có nền đường phố/tên đường.
- Android Auto hiển thị thẻ tốc độ/giới hạn, màu đèn/số giây, biển báo và trạng thái camera/AI.
- Không chiếu video camera lên màn hình Android Auto; đây là giới hạn chủ động để giảm xao nhãng.

## Vá lỗi 1.2.1

- Hai model AI được đóng gói sẵn trong APK; lần mở AI đầu chỉ chép model vào vùng riêng, không cần Internet.
- Vị trí dùng GPS, nhà mạng và vị trí gần nhất; chấp nhận cả quyền vị trí gần đúng để tránh kẹt “Đang tìm”.
- Màn hình GPS ghi rõ trạng thái quyền/vị trí. Đây là vệt di chuyển offline, chưa có nền đường và tên đường.

## Vá lỗi 1.2.2

- Chuyển toàn bộ thao tác đọc trạng thái Media3 Player khi mở AI về Android main thread.
- Sửa lỗi `Player is accessed on the wrong thread` làm AI báo lỗi dù model đã sẵn sàng.

## Những gì bản 1.1 đã có

- Chụp khung AI 1280×720 và mặc định dùng luồng chính để giữ chi tiết ở khoảng cách hai hàng xe.
- Dò phóng đại luân phiên ba vùng trái/giữa/phải cho cả đèn và biển báo xa.
- Dùng hai model độc lập để nhận đèn: COCO traffic-light và lớp Green/Red Light của model Việt Nam.
- Đọc cảnh báo theo cụm, ví dụ `Đèn đỏ, còn 12 giây`; có nút **THỬ GIỌNG CẢNH BÁO**.

- Phát RTSP bằng Android Media3, hỗ trợ BASIC/DIGEST authentication, UDP unicast và RTP-over-RTSP/TCP.
- Preset IMOU/Dahua cho `subtype=1` (nhẹ, nên dùng cho AI) và `subtype=0` (luồng chính).
- Nhập URL RTSP đầy đủ cho camera hãng khác.
- ONNX Runtime chạy model ngay trên điện thoại; ảnh camera không phải gửi lên máy chủ.
- YOLO COCO tìm cụm đèn, sau đó kiểm tra màu đỏ/vàng/xanh bằng pixel và vị trí bóng đèn.
- YOLO11s 82 lớp tìm biển báo Việt Nam; dùng tile nhiều vùng, lượt xác nhận thứ hai và đồng thuận nhiều frame để giảm báo sai.
- Bộ đọc LED 7-segment 1–3 chữ số ở trái/phải/dưới cụm đèn.
- Countdown chỉ hiển thị số đã nhìn thấy đủ nhiều frame; không chấp nhận chuỗi tăng bất thường; đổi màu, mất bảng hoặc số 0 thì xóa số cũ.
- Đọc tiếng Việt bằng TextToSpeech; số ban đầu đọc một lần, từ 5 đến 1 đọc từng số.
- Mật khẩu không được ghi vào log hay SharedPreferences.
- Giao diện khởi động độc lập với model: model lỗi/tải chậm không làm màn hình trắng hoặc làm ứng dụng văng.

## Cách dùng với IMOU C22E-A

1. Điện thoại và camera phải nhìn thấy nhau trong cùng LAN/Wi-Fi, hoặc RTSP đã được định tuyến/VPN đúng cách.
2. Nhập `IP camera`, port `554`, user `admin`, mật khẩu/Safety Code.
3. Để nhận đèn/biển ở xa, giữ đường dẫn luồng chính mặc định:
   `/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif`
4. Giữ `RTP/TCP` để tương thích tốt hơn với IMOU, sau đó bấm **KẾT NỐI**.
5. Bấm **TẢI / MỞ AI**. Lần đầu ứng dụng tải khoảng 50 MB model; các lần sau dùng bản lưu trong máy.
6. Khi RTSP đã phát và AI báo đủ ba lõi `OK`, kết quả đèn, số giây và biển báo sẽ hiện bên dưới video và được đọc bằng tiếng Việt.

URL đã kiểm chứng về cấu trúc:

```text
rtsp://admin:MATKHAU@IP:554/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif
```

Nếu luồng chính chậm, đổi `subtype=0` thành `subtype=1`.

## Camera RTSP hãng khác

Dán URL đầy đủ vào ô đầu tiên. Khi ô này có dữ liệu, ứng dụng bỏ qua các ô IP/user/password/path bên dưới. Media3 RTSP chính thức hỗ trợ H.264 và âm thanh AAC/AC3; camera đang phát H.265 cần chuyển profile video sang H.264.

## Build APK trong Android Studio

1. Cài Android Studio hiện hành, Android SDK 36 và JDK 17.
2. Mở thư mục `TrafficAI_RTSP_Android`.
3. Chờ Gradle Sync tải Media3 và ONNX Runtime.
4. Chọn **Build > Build APK(s)**.

## Tạo APK cài trực tiếp bằng GitHub Actions

Project có workflow `.github/workflows/build-apk.yml`. Đẩy project lên nhánh
`main`, mở tab **Actions**, chạy **Build installable APK**, rồi tải artifact
`TrafficAI_RTSP_Android_Debug`. APK debug đã được Android build system ký
sẵn nên có thể cài trực tiếp trên điện thoại Android 8.0 trở lên.
5. APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

## Chạy bản Android Auto thử nghiệm cá nhân

1. Cài APK debug lên điện thoại và cấp quyền vị trí **Chính xác**.
2. Mở TrafficAI trên điện thoại, kết nối RTSP và bấm **TẢI / MỞ AI**.
3. Cài bản thử nghiệm qua **Google Play Internal App Sharing** hoặc **Internal Test Track**.
4. Kết nối điện thoại với Android Auto, mở trình khởi chạy ứng dụng và chọn **TrafficAI Auto**.
5. Nếu chưa thấy ứng dụng, vào **Tùy chỉnh trình khởi chạy** của Android Auto và bật TrafficAI Auto.

Android Auto thật yêu cầu Car App Library được cài từ nguồn tin cậy. Tùy chọn **Nguồn không xác định** không áp dụng cho loại app này, nên APK tải trực tiếp từ GitHub có thể cài trên điện thoại nhưng vẫn bị màn hình xe ẩn.

Màn hình xe chỉ là màn hình phụ. Ứng dụng trên điện thoại phải đang chạy để xử lý camera, GPS và giọng nói. Đây là APK debug dành cho thử nghiệm cá nhân, chưa phải bản phát hành Google Play và chưa có dẫn đường từng chặng.

Các dependency chính:

- `androidx.media3` 1.11.0
- `com.microsoft.onnxruntime:onnxruntime-android` 1.29.0
- `androidx.car.app` 1.7.0
- compile/target SDK 36, min SDK 26

## Model

- Đèn: `webnn/yolo11n`, COCO class `traffic light`.
- Biển Việt Nam: `star092304/traffic-sign-detection-vietnam-yolo`, 82 lớp, ONNX 640×640.
- Model được tải trực tiếp ở lần khởi tạo AI và lưu trong vùng riêng của ứng dụng.

## Kiểm thử logic không cần Android SDK

Từ thư mục dự án chạy:

```bash
bash tools/run_pure_logic_test.sh
```

Bài test kiểm tra URI-encode/masking mật khẩu, khóa nhiều frame, chặn countdown tăng và reset khi đèn đổi màu.

## Giới hạn cần hiểu đúng

- Độ chính xác thực địa phụ thuộc vị trí camera, độ phân giải, ánh sáng, khoảng cách và model. Số liệu đánh giá của model không phải cam kết độ chính xác trên mọi tuyến đường.
- LED dot-matrix hoặc kiểu số không theo 7-segment có thể cần một model OCR riêng sau khi thu thập clip thật.
- Đây là trợ lý thử nghiệm, không phải hệ thống ADAS/an toàn chức năng. Người lái luôn phải tự quan sát biển và tín hiệu thật.
- Tốc độ GPS, giới hạn đọc bởi AI và đồng hồ đèn có thể sai hoặc trễ; không dùng các giá trị này làm căn cứ duy nhất khi lái xe.
