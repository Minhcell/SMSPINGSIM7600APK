# SmsPing OTG (Android) — bản khớp giao diện & chức năng PING của bản PC

Ứng dụng PING SMS qua modem SIM cắm vào điện thoại bằng **OTG**. Dựng lại từ APK gốc:
**giữ nguyên phần kết nối OTG**, nâng giao diện + chức năng PING theo bản PC đã chạy ổn định.

## Đã có gì

- **OTG giữ nguyên** (`UsbAtManager.kt`): USB Host API thô, bulk transfer, lọc modem theo
  VID `0x1E0E`/`0x05C6` (SIM7600/Qualcomm) — đúng như bản gốc.
- **PING đã sửa như bản PC** (`PduCodec.kt` + `MainActivity.kt`):
  - Nhét địa chỉ **SMSC thẳng vào PDU** -> hết lỗi `SMSC address unknown`.
  - Khi Connect: tự nhận nhà mạng theo IMSI (Mobifone/Vinaphone/Viettel/Vietnamobile/Gmobile),
    dọn bộ nhớ SMS (chống `Memory full`), đặt chế độ mạng tự động.
  - **Tự giải mã báo cáo +CDS** -> hiện ngay ONLINE/OFFLINE ở khung KẾT QUẢ.
  - Nút **Kiểm tra TK**: USSD `*101#` (có thử lại), tự chuyển SMS `TK`→191 nếu là **Viettel**.
- Giao diện giống PC: Quét/Kết nối, chọn PING hoặc AT Command, ô nhập số + nút PING SMS,
  khung RAW CODE và khung KẾT QUẢ.

## Build ra APK bằng GitHub Actions

1. Đẩy toàn bộ thư mục này lên GitHub (nhánh `main`).
2. Vào tab **Actions** -> workflow **Build APK** chạy tự động (hoặc **Run workflow**).
3. Tải **Artifacts -> app-debug** (file `app-debug.apk`), cài lên điện thoại
   (bật "Cài từ nguồn không xác định").

Workflow tự tạo Gradle wrapper trên máy chủ (tránh lỗi gradle-wrapper.jar hỏng).

## Dùng trên điện thoại

Cắm modem SIM qua cáp OTG -> mở app -> **Quét** -> chọn đúng Interface có cổng dữ liệu
-> **Kết nối** (cấp quyền USB) -> nhập số cần PING (10 số, bắt đầu 0) -> **PING SMS**.
Kết quả online/offline hiện ở khung KẾT QUẢ khi báo cáo về.

## Ghi chú

- Nếu chọn Interface không đúng, app báo "không có endpoint bulk", thử Interface khác.
- Danh sách IMEI hợp lệ nằm ở `allowedImei` trong `MainActivity.kt` (giữ như bản PC).
  Thêm IMEI modem mới vào đó nếu muốn dùng máy khác.
