# TrafficAI Drive 2.3.2 • Camera Orientation Fix ARM64

Ứng dụng Android nhận luồng RTSP, nhận diện biển báo giao thông Việt Nam, nhận màu đèn tín hiệu, đọc số LED đếm ngược và quan sát xe/người trong hành lang chạy phía trước. Dự án được thiết kế trước hết cho Samsung S23 Ultra + IMOU IPC-C22E-A, đồng thời cho phép nhập URL của camera RTSP H.264 khác.

## Bản 2.3.2: sửa chiều camera điện thoại

- Bỏ phép xoay thừa 90° khi S23 Ultra đang ở màn hình dọc.
- Tự bù đúng −90° hoặc +90° theo `Surface.ROTATION_90/270` khi điện thoại xoay ngang và bù 180° khi gắn lộn ngược.
- Thêm nút **XOAY CAMERA 90° NẾU ẢNH CHƯA ĐÚNG** trong Cài đặt; góc bù 0/90/180/270 được lưu trên điện thoại.
- Khung hình AI được lấy sau ma trận hiển thị nên hộp nhận diện tiếp tục trùng với hình camera đã xoay.

## Bản 2.3.1: camera điện thoại và cảnh báo tốc độ theo biển AI

- Có nút **DÙNG CAMERA SAU ĐIỆN THOẠI** trong Cài đặt; app mở Camera2 trực tiếp, không yêu cầu camera RTSP và không mở luồng mic.
- Camera điện thoại tự mở AI, hiển thị rõ nguồn đang dùng và vẫn có thể chuyển lại IMOU/RTSP; khi mở bản đồ, luồng hình được giữ ẩn để cảnh báo AI tiếp tục chạy.
- Hạ ngưỡng ứng viên biển từ 0,16 xuống 0,13 (vùng đã học 0,11), nhưng biển yếu chỉ được đọc khi cùng một vị trí có đa số 3–4 khung để hạn chế báo nhầm.
- Biển giới hạn tốc độ đã xác nhận sẽ cập nhật vòng tròn `AI`, đọc giới hạn bằng tiếng Việt và cảnh báo khi GPS vượt quá biển hơn 3 km/h trong ít nhất 1,2 giây.
- Nguồn giới hạn được ghi rõ `AI`, `MAP` hoặc `TAY`; biển “Hết giới hạn tốc độ” xóa giới hạn AI cũ.

Đây vẫn là trợ lý thử nghiệm. Nhận diện biển, tốc độ GPS và cảnh báo có thể sai hoặc trễ; người lái phải tuân theo biển và tín hiệu thực tế.

## Bản 2.3: giao diện trợ lý lái xe

- Màn hình chính mới tập trung vào camera/bản đồ, tìm đường, hướng rẽ, tốc độ, giới hạn, đèn, số giây, biển báo và cảnh báo phía trước.
- Toàn bộ URL RTSP, tài khoản camera, nút mở AI, tải map, máy chủ bản đồ và giới hạn thủ công được giấu trong bảng cài đặt toàn màn hình mở bằng biểu tượng bánh răng.
- Tìm điểm đến và tìm bằng giọng nói nằm ngay đầu màn hình; sau khi tạo tuyến hiển thị quãng đường, thời gian di chuyển và giờ đến dự kiến.
- Có lựa chọn làn **trái / giữa / phải**. AI ưu tiên vùng cụm đèn theo làn đã chọn nhưng vẫn quét toàn cảnh định kỳ để tránh bỏ sót.
- Bổ sung truy vấn điểm camera tốc độ/giám sát, giao cắt đường sắt và trạm thu phí từ dữ liệu OpenStreetMap/Overpass; cache cùng biển và đèn để dùng lại khi mất mạng.
- Cảnh báo bản đồ gần xe hiển thị nổi trên video và đọc bằng tiếng Việt; Android Auto nhận thêm làn đang ưu tiên.
- Giao diện màu xanh đậm, thẻ bo tròn, đồng hồ tốc độ và biển giới hạn dạng tròn để nhìn nhanh khi lái xe.

TrafficAI không dùng dữ liệu giao thông thời gian thực hoặc cơ sở dữ liệu độc quyền của VietMap. Các điểm cảnh báo bản đồ chỉ xuất hiện khi OpenStreetMap có dữ liệu tương ứng và không được coi là danh sách camera/phạt nguội đầy đủ.

## Vá lỗi 2.2.2: giảm bỏ sót biển ở xa

- Thay bộ cộng phiếu theo class bằng tracker riêng cho từng biển dựa trên vị trí, chuyển động và kích thước hộp.
- Giữ ứng viên từ 0,16; biển rõ xác nhận sau hai khung, biển xa cần đa số ít nhất ba phiếu trên cùng track.
- Prior cột phải/độ cao chỉ giảm tối đa khoảng 6,3%, không còn làm mất mạnh biển giữa hoặc bên trái.
- Chu kỳ quét biển mới: phải → giữa → phải → trái → toàn cảnh; điểm OSM/Map Memory vẫn kích hoạt vùng quét đã học.
- Màn hình AI hiển thị `BIỂN RAW` và `TRACK` để phân biệt model không thấy biển với bộ xác nhận chưa đủ phiếu.
- Giọng nói ưu tiên nguy hiểm, màu đèn/số giây rồi mới đọc biển; cùng một track không bị đọc lặp liên tục.

Bản vá này cải thiện 82 lớp hiện có và chưa bổ sung model nhận diện biển ngoài tập huấn luyện. Detector biển tổng quát và bộ phân loại theo mã QCVN được để dành cho nhánh sau vì cần thêm model/dữ liệu thực địa.

## Bản 2.2: dữ liệu biển/đèn OSM, tìm giọng nói và dẫn đường

### Vá lỗi 2.2.1: không nạp được OSM mới

- Tăng thời gian chờ Overpass vì máy chủ công cộng có thể xếp hàng lâu hơn 20 giây.
- Thử GET rồi POST để vượt lỗi proxy/nhà mạng chặn một kiểu yêu cầu.
- Nếu URL đang cấu hình là máy chủ Overpass công cộng mặc định, app tự thử lần lượt `overpass-api.de`, `overpass.kumi.systems` và `overpass.private.coffee`; URL riêng không bị tự động chuyển sang nhà cung cấp khác.
- Tọa độ gửi tới Overpass được làm tròn khoảng 1 km trước khi truy vấn bán kính 5 km, không gửi GPS chính xác.
- Phân biệt rõ “đã kết nối nhưng khu vực chưa có dữ liệu” với “máy chủ lỗi hoặc mạng chặn”.

- Nạp các node `highway=traffic_signals`, `traffic_sign`, `stop`, `give_way`, `speed_camera`, `railway=level_crossing` và `barrier=toll_booth` trong bán kính 5 km qua Overpass; cache SQLite để lần sau vẫn hiển thị khi mất mạng.
- Gộp marker OSM với các điểm biển/đèn do AI Map Memory học được. Marker ghi rõ nguồn OSM hay điểm AI đã xác nhận.
- Tìm điểm đến tiếng Việt bằng Nominatim chỉ khi người dùng bấm **ĐI** hoặc **NÓI**; không có autocomplete, giới hạn một luồng truy vấn và cache kết quả 30 ngày.
- Nhận điểm đến qua `RecognizerIntent` tiếng Việt; kết quả được đưa thẳng vào ô tìm kiếm và tạo tuyến.
- OSRM trả tuyến lái xe, hình học GeoJSON và maneuver; app vẽ đường màu xanh, hiển thị quãng đường/ETA và đổi maneuver thành câu tiếng Việt cho TTS.
- Theo dõi bước rẽ 300 m / 100 m / 40 m, nhận biết lệch tuyến trên 100 m và tự tính lại sau 8 giây với khoảng nghỉ tối thiểu 30 giây.
- Android Auto nhận điểm đến, bước rẽ kế tiếp, khoảng cách cùng trạng thái Map Memory; video vẫn chỉ chạy trên điện thoại.
- Ba URL Nominatim/OSRM/Overpass nằm trong Cấu hình và được lưu cục bộ, nên có thể đổi nhà cung cấp mà không cập nhật APK.

Tìm kiếm và tạo tuyến mới cần Internet. Tuyến và dữ liệu từ máy chủ công cộng chỉ dành cho bản thử nghiệm cá nhân, không có SLA và không thay thế ứng dụng dẫn đường thương mại. Nền bản đồ offline không đồng nghĩa với định tuyến offline; những điểm OSM đã tải được vẫn còn trong cache. Khi bấm nạp OSM, app gửi tâm vùng đã làm tròn cho máy chủ Overpass đang cấu hình; nếu đó là một trong các máy chủ công cộng kể trên, app có thể thử các máy chủ công cộng còn lại khi lỗi.

## Bản 2.1: nền bản đồ thật và bộ nhớ tuyến đường

- Hiển thị nền đường MapLibre từ dữ liệu OpenStreetMap/OpenFreeMap, có tên đường và nút chuyển Camera/Bản đồ.
- Nút **TẢI MAP OFFLINE QUANH ĐÂY 25 KM** lưu vùng đang đứng ở zoom 8–15 trong bộ nhớ riêng của ứng dụng; sau khi hoàn tất có thể xem vùng đó khi không có Internet.
- Nếu nền trực tuyến chưa nạp được, lớp GPS dạng lưới vẫn xuất hiện làm chế độ dự phòng thay vì màn hình đen.
- AI ghi điểm kích hoạt GPS của biển báo/cụm đèn sau ít nhất ba khung ổn định, kèm hướng tiếp cận và vị trí chuẩn hóa trong ảnh. Không lưu ảnh camera, màu đèn hoặc số đếm.
- Điểm cùng loại, cùng hướng trong bán kính 45 m được gộp. Khi quay lại trong 160 m, AI ưu tiên đúng model và vùng ảnh đã học để tìm lại nhanh hơn.
- Điểm đã học được đánh dấu trên bản đồ và trạng thái gần điểm được chuyển sang Android Auto.

Lần mở nền bản đồ đầu và lần bấm tải vùng offline cần Internet. Dữ liệu địa điểm AI học được lưu hoàn toàn trong SQLite trên điện thoại.

## Kiến trúc thị giác video kế thừa từ bản 2.0

- Giữ mục tiêu đèn bằng tracker vận tốc/IoU qua nhiều khung hình để giảm đổi nhầm sang cột khác.
- Khi đã khóa đèn, model nhìn tập trung vào vùng quanh mục tiêu; cứ bốn lượt lại quét toàn cảnh để chống mất dấu.
- Kết hợp độ tin cậy, độ mới và đồng thuận nhiều frame trước khi đổi màu đèn hoặc nhận số giây.
- Biển cùng loại nhưng nằm ở vị trí khác không còn bị cộng phiếu đồng thuận với nhau.
- Cùng lượt model COCO nhận đèn được tận dụng để quan sát người, xe đạp, xe máy, ô tô, xe buýt và xe tải phía trước; không chạy thêm model nặng.
- Android Auto debug chấp nhận host thử nghiệm khi điện thoại đã bật Developer mode và Unknown sources; màn hình xe chỉ hiển thị dữ liệu, không chiếu video.
- Giao diện báo đúng FPS kết quả thực tế thay vì FPS ước tính.

Phần cảnh báo phía trước chỉ dựa trên vị trí/kích thước tương đối trong ảnh, không đo khoảng cách hay TTC và không thay thế phanh tự động.

Bản APK 2.3.2 chỉ đóng gói ABI `arm64-v8a` cho Samsung S23 Ultra và phần lớn điện thoại Android 64-bit hiện đại. Việc bỏ thư viện x86/32-bit giúp giảm đáng kể dung lượng và tránh lỗi tải tệp lớn.

## Tối ưu thời gian thực trong bản 1.4

- Mỗi nhịp chỉ chạy một model YOLO, luân phiên đèn và biển thay vì ba inference liên tiếp.
- Giữ hộp đèn tối đa 1,6 giây để đọc màu/LED bằng pixel ở các nhịp YOLO biển.
- Luân phiên full-frame và tile phóng đại cho đèn, vẫn ưu tiên vùng bên phải.
- Giảm chu kỳ lấy khung từ 280 ms xuống 90 ms; cơ chế busy gate tiếp tục chặn hàng đợi frame cũ.
- Tái sử dụng bitmap 1280×720 để giảm cấp phát bộ nhớ và giật do garbage collection.
- Bật XNNPACK 4 luồng cho model FP32, tự trở về CPU EP nếu tăng tốc không khởi tạo được.
- Giảm bộ đệm Media3 RTSP xuống khoảng 0,3–1,0 giây để hình gần thời gian thực hơn.

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
- Màn hình GPS đời 1.2 chỉ vẽ hướng/vệt di chuyển; từ bản 2.1 đã có nền đường và vùng tải offline.
- Android Auto hiển thị thẻ tốc độ/giới hạn, màu đèn/số giây, biển báo và trạng thái camera/AI.
- Không chiếu video camera lên màn hình Android Auto; đây là giới hạn chủ động để giảm xao nhãng.

## Vá lỗi 1.2.1

- Hai model AI được đóng gói sẵn trong APK; lần mở AI đầu chỉ chép model vào vùng riêng, không cần Internet.
- Vị trí dùng GPS, nhà mạng và vị trí gần nhất; chấp nhận cả quyền vị trí gần đúng để tránh kẹt “Đang tìm”.
- Màn hình GPS ghi rõ trạng thái quyền/vị trí; lưới cũ nay là lớp dự phòng khi nền MapLibre chưa nạp.

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
5. Bấm **TẢI / MỞ AI**. Model đã được workflow đóng gói trong APK và được chép vào vùng riêng trong lần mở đầu.
6. Khi RTSP đã phát và AI báo đủ ba lõi `OK`, kết quả đèn, số giây và biển báo sẽ hiện bên dưới video và được đọc bằng tiếng Việt.

Cấu trúc đã kiểm chứng gồm: giao thức RTSP, tên người dùng, Safety Code/mật khẩu,
địa chỉ IP camera, cổng `554` và đường dẫn IMOU đang hiển thị trong ứng dụng.
Không ghi URL có thông tin đăng nhập hoàn chỉnh vào tài liệu, ảnh chụp hoặc GitHub.

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
- `org.maplibre.gl:android-sdk` 13.0.2
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
