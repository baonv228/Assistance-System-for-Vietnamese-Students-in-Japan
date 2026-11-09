# Senpai ơi – Phân tích tính năng cho mobile app

Ngày rà soát: 07-11-2026, nhánh `ductq` (commit `a6a8b6f`, sau PR #22: Tìm kiếm chung, FAQ thành tab của Hỏi-Đáp),
kể cả phần chưa commit: cẩm nang của thành viên (lưu, đã xong, tiến độ, V20) và "Chủ đề quan tâm" (V21).
Thay bản 05-11. App được làm bằng Flutter trong project `myasvsj` (hiện mới là project mẫu).
Nguồn: mọi `@RestController` trong `src/main/java/com/sep490/vsa`, `SecurityConfig`, `CleanUrls`, `site-header.js` (`TABS`), `staff-desk.js` và `admin-layout.js` (module của staff), các trang trong `static/pages`,
`CLAUDE.md`, và prototype `Prototype_System_Test` nhánh `tienhung` (thanh tab điện thoại, đặc tả UC Moderator / AM).

## 0. Thay đổi so với bản 05-11

- **Nhật ngữ đã xong trên web** (thư viện theo Chủ đề / Địa điểm, tìm, "Nghe", "Đưa cho nhân viên xem", thẻ ghi nhớ,
  "Chưa thuộc" / "Đã thuộc", báo lỗi mẫu câu) → vào Đợt 1 cho Guest và User. Sổ tay, Yêu thích và câu luyện tập đã bị
  nhóm bỏ, app không làm.
- **Biên tập viên nội dung tiếng Nhật (AM)** đã có 3 module trên web (Thư viện mẫu câu, Chủ đề học, Báo cáo mẫu câu) →
  vẫn để web.
- **Tìm kiếm chung** (`GET /api/search`) và trang **"Dành cho bạn"** (`GET /api/me/for-you`, có tìm trong bài) mới →
  Đợt 1.
- **FAQ** thành tab "Câu hỏi thường gặp" của Hỏi-Đáp; "Đặt câu hỏi" gợi ý FAQ liên quan khi đang gõ tiêu đề → app
  làm giống.
- Moderator có thêm **Quản lý FAQ** và **Đề xuất FAQ** (web). Admin có **Tổng quan có biểu đồ, Chi tiết tài khoản,
  cấu hình an toàn** (web).
- **Cẩm nang của thành viên đã có API** (chưa commit): lưu cẩm nang, "Đánh dấu đã xong", tiến độ trên trang cẩm nang
  (`GuideMemberController`), "Cẩm nang của tôi" trên Tôi (`MyGuidesController`), "Cẩm nang cho giai đoạn …" và
  "Việc tiếp theo" trên Trang chủ (`GuideProgressService`); "Đã lưu" có thêm cẩm nang (V20) → Đợt 1.
- **"Chủ đề quan tâm"** mới (`GET|PUT /api/me/interests`, V21): User chọn tối đa 5 chủ đề blog trên Tôi và trong
  "Điều chỉnh" ở Trang chủ, "Dành cho bạn" xếp bài theo đó → Đợt 1.
- **Staff đăng nhập được bằng Google**: `SocialLoginSuccessHandler` không chặn vai trò, tài khoản đã có chuyển về trang
  quản lý của vai trò đó → sửa bảng 2.1 và mục 3.
- **Thông báo**: liệt kê 14 loại server đang gửi; thông báo về ghim / địa điểm / tag hiện **không có `link`** → mục 2.10
  và 5.4.
- **Framework đã chốt: Flutter** (project `myasvsj`) → mục 7 bỏ câu hỏi framework; đổi id ứng dụng ở mục 5.16, thư
  viện Flutter ở mục 6.
- **API app không gọi**: `/api/scholarships` (luôn trả 404 "sắp ra mắt", ngoài phạm vi), `/api/events/pins` (trùng
  `/api/map/events`), `/api/blogs/scam-warnings` (dấu "!" đã có trong `/api/map`) → mục 5.15.
- Prototype có **"Khiếu nại quyết định này"** phía User (đi cùng UC-M04) mà web chưa làm → xếp vào "khi web có".

## 1. Kết luận theo vai trò

| Vai trò | Có trên app? | Lý do |
|---|---|---|
| **Guest** (chưa đăng nhập) | **Có – toàn bộ phần xem** | `GET /api/**` đã công khai. Apple 5.1.1 yêu cầu xem được nội dung trước khi bắt đăng ký. Đây cũng là "phễu" để người mới sang Nhật tải app. |
| **User** (Thành viên) | **Có – đầy đủ** | Prototype chỉ vẽ khung điện thoại cho User (nút "Điện thoại / Máy tính" chỉ có ở vai trò Người dùng). Mọi tính năng cộng đồng là của User và được lợi nhất từ camera, GPS, push, dùng offline. |
| **Moderator** | **Một phần ("Moderator lite"), sau MVP** | Tối đa 3 người. Hàng đợi ngắn (tag, ghim mới, báo đóng cửa; sau này báo cáo nội dung, khiếu nại) hợp với điện thoại + push. Màn hình nhập liệu nặng (địa điểm có bản đồ, gộp trùng, cẩm nang, FAQ, sự kiện) giữ trên web. |
| **AM** (Biên tập viên tiếng Nhật) | **Không – web** | Công việc là soạn / sửa tiếng Nhật trong bảng, số báo cáo mẫu câu ít, 1–2 người. |
| **Admin** | **Không – web** | Bảng người dùng, cấu hình, từ khóa cấm, nhật ký: màn hình rộng, nhạy cảm, 1–2 người. |

Tài khoản staff đăng nhập vào app: vẫn cho đăng nhập, nhưng chỉ xem nội dung công khai, xem "Tôi" (đổi mật khẩu, đăng
xuất, xóa tài khoản) và thông báo; mọi thao tác cộng đồng web đã từ chối staff bằng MSG18 (403), nên app hiện sẵn dòng
"Tài khoản quản lý: hãy dùng trang web để làm việc" kèm nút mở trang quản lý, thay vì để người dùng gặp lỗi.

## 2. Tính năng × vai trò (chi tiết)

Ký hiệu: **Làm** = thao tác ghi; **Xem** = chỉ đọc; **–** = không có; **Web** = giữ trên web.
Cột "Đợt" là đề xuất cho app; "Web" cho biết web đã có hay chưa (app chỉ gọi lại API có sẵn).
Staff (Mod / AM / Admin) xem được mọi thứ Guest xem được; các bảng 2.1–2.11 chỉ ghi riêng staff khi khác Guest.

### 2.1 Tài khoản và xác thực (`feature/auth`, `feature/legal`, `feature/user`)

| Tính năng | API | Guest | User | Staff | Web | Đợt |
|---|---|---|---|---|---|---|
| Đăng ký email: gửi mã → nhập mã → hoàn tất (tên, mật khẩu, 18+, đồng ý điều khoản) | `POST /api/auth/register/send-code|verify-code|complete` | Làm | – | – | Có | 1 |
| Đăng nhập email + mật khẩu (5 lần sai → khóa 30 phút) | `POST /api/auth/login` | Làm | Làm | Làm | Có | 1 |
| Đăng nhập Google | web: OAuth2 redirect | Làm | Làm | Làm (server không chặn staff) | Có (khi có client id) | 1, **cần API mới** (mục 5.2) |
| Đăng nhập Apple | – | Làm | Làm | – | Chưa | 1 trên iOS, **bắt buộc** khi có Google (mục 5.3) |
| Đăng nhập Facebook | code có sẵn, chưa bật | – | – | – | Chưa bật | Sau |
| Hoàn tất đăng ký mạng xã hội (tên, 18+, điều khoản) | `GET /api/auth/social/pending`, `POST …/social/complete` | Làm | – | – | Có | 1 |
| Quên mật khẩu → link email → đặt lại | `POST /api/auth/password/forgot|check|reset` | Làm | – | – | Có | 1 (link cần deep link) |
| Đổi / tạo mật khẩu (đăng xuất thiết bị khác) | `PUT /api/me/password` | – | Làm | Làm | Có | 1 |
| Điều khoản, Chính sách bảo mật, Quy tắc cộng đồng | `GET /api/legal/{type}` | Xem | Xem | Xem | Có | 1 (store bắt buộc) |
| Đăng xuất | `POST /api/auth/logout` | – | Làm | Làm | Có | 1 |
| Xóa tài khoản (Admin cuối cùng bị từ chối) | `DELETE /api/me` | – | Làm | Làm | Có | 1 (Apple 5.1.1(v) bắt buộc) |

### 2.2 Trang chủ, "Dành cho bạn", Tìm kiếm (`feature/home`, `feature/search`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Trang chủ khách: số liệu, review "Hữu ích", mẫu câu trong ngày, ô tìm kiếm | `GET /api/home` | Xem | – | Có | 1 |
| Trang chủ thành viên: lời chào, tìm kiếm, "Dành cho bạn" (5 bài + lý do + chip "Dựa trên"), "Cẩm nang cho giai đoạn …" (≤ 3 cẩm nang chưa xong của giai đoạn), "Việc tiếp theo" (x / y cẩm nang của giai đoạn), "Review mới quanh …", sự kiện, mẫu câu, "Bài nổi bật", điểm | `GET /api/me/home` | – | Xem | Có (cẩm nang: chưa commit) | 1 |
| Nhắc chọn khu vực + giai đoạn ("Để sau", "Điều chỉnh") | `PUT /api/me/profile` | – | Làm | Có | 1 |
| "Chủ đề quan tâm" trong "Điều chỉnh": chọn tối đa 5 chủ đề blog, chủ đề học được từ thích / lưu / bình luận / viết hiện làm gợi ý | `GET|PUT /api/me/interests` | – | Làm | Có (chưa commit) | 1 |
| Trang "Dành cho bạn": lọc Tất cả / Gần bạn / Giai đoạn / Quan tâm, tìm trong bài, 10 bài một trang | `GET /api/me/for-you?scope=&page=&q=` | – | Xem | Có | 1 |
| Trang "Review mới": Quanh khu vực / Cả tỉnh / Tất cả | `GET /api/home/reviews?scope=&page=` | Xem (chỉ Tất cả) | Xem | Có | 1 |
| Tìm kiếm chung: bài viết, câu hỏi, cẩm nang, sự kiện, mẫu câu, FAQ, địa điểm (5 mỗi phần, "Xem tất cả") | `GET /api/search?q=` | Xem | Xem (+ "Chỉ tìm trong bài dành cho bạn") | Có | 1 |

### 2.3 Bản đồ, địa điểm, review (`feature/place`, `feature/review`, `feature/area`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Bản đồ: địa điểm ACTIVE, danh mục + số lượng, ghim sáng / mờ / vuông / xám, "!" cảnh báo lừa đảo, tâm = khu vực của tôi | `GET /api/map` | Xem | Xem (+ ghim chờ duyệt của mình) | Có | 1 |
| Lớp sự kiện 15 / 30 ngày (ghim đèn lồng) | `GET /api/map/events?days=` | Xem | Xem | Có | 1 |
| Tìm địa điểm / danh mục | `GET /api/map/search?q=` | Xem (chỉ địa điểm Senpai ơi) | Xem (+ OpenStreetMap) | Có | 1 |
| "Địa điểm quanh đây" (chạm bản đồ ở zoom ≥ 15) | `GET /api/map/nearby` | – | Xem | Có | 1 |
| Chi tiết địa điểm: điểm TB, số review, đã đóng cửa, đã lưu, cảnh báo | `GET /api/map/places/{id}` | Xem | Xem | Có | 1 |
| Review của địa điểm (10 / trang) | `GET /api/map/places/{id}/reviews` | Xem | Xem | Có | 1 |
| Từ vựng / mẫu câu theo danh mục địa điểm + "Nghe" | `GET /api/map/places/{id}/phrases` | Xem | Xem | Có | 1 |
| Lưu / bỏ lưu địa điểm | `POST|DELETE /api/map/places/{id}/save` | – | Làm | Có | 1 |
| Báo "đã đóng cửa" (1 lần / người) | `POST …/closed-report` | – | Làm | Có | 1 |
| Thích / báo cáo review | `POST|DELETE /api/reviews/{id}/like`, `POST …/report` | – | Làm | Có | 1 |
| Viết review: 1–5 sao, 10–2.000 ký tự, ≤ 5 ảnh, 1 video ≤ 30 s; review địa điểm OSM chưa có | `GET /api/reviews/compose`, `POST /api/reviews` | – | Làm | Có | 1 (ảnh), video: 2 |
| Sửa / xóa review của mình | `GET /api/reviews/{id}/edit`, `PUT`, `DELETE` | – | Làm | Có | 1 |
| Cắm ghim địa điểm mới (kéo ghim, kiểm tra trùng 200 m, review đầu tiên, chờ Moderator) | `GET /api/map/pins/compose|duplicates`, `POST /api/map/pins` | – | Làm | Có | 2 |
| Vị trí của tôi, Chỉ đường (mở Google Maps / Apple Maps) | client | Làm | Làm | Có | 1 (GPS native) |
| Khu vực sinh sống: tỉnh → quận / thành phố, tìm, gần nhất theo GPS | `GET /api/areas*` | – | Làm | Có | 1 |

### 2.4 Blog (`feature/blog`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Danh sách: tìm, lọc chủ đề, sắp xếp; Bài nổi bật tuần / tháng; thống kê | `GET /api/blogs`, `/overview` | Xem | Xem (+ bài đang quét của mình) | Có | 1 |
| Chi tiết: media, địa điểm liên quan (bản đồ nhỏ, Chỉ đường), cây bình luận | `GET /api/blogs/{id}` | Xem | Xem | Có | 1 |
| Thích, lưu, báo cáo bài | `POST|DELETE …/like|save`, `POST …/report` | – | Làm | Có | 1 |
| Bình luận, trả lời (15 s / lần; tài khoản mới 20 / ngày) | `POST /api/blogs/{id}/comments` | – | Làm | Có | 1 |
| Thích, báo cáo, sửa, xóa bình luận | `/api/comments/{id}[/like|/report]` | – | Làm | Có | 1 |
| Viết bài: tiêu đề, nội dung, chủ đề, ≤ 5 địa điểm, ≤ 5 ảnh, 1 video; link (BR-22), QR, từ khóa cấm, giới hạn tài khoản mới | `GET /api/blogs/compose`, `GET /api/places/search`, `POST /api/blogs` | – | Làm | Có | 1 (ảnh), video: 2 |
| Sửa bài (giữ / bỏ media) | `GET /api/blogs/{id}/edit`, `PUT /api/blogs/{id}` | – | Làm | Có | 1 (chữ), media: 2 |
| Xóa bài của mình | `DELETE /api/blogs/{id}` | – | Làm | Có | 1 |
| "Nhắn tin" với tác giả / người bình luận | mở Tin nhắn `?to=` | → đăng nhập | Làm | Có | 2 |
| Hồ sơ công khai của tác giả, huy hiệu cấp bậc cạnh tên | – | – | – | **Chưa** | Khi web có |

### 2.5 Hỏi-Đáp và Câu hỏi thường gặp (`feature/qa`, `feature/faq`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Danh sách câu hỏi: tìm, chủ đề, Chưa trả lời / Chưa giải quyết / Đã giải quyết, sắp xếp; FAQ liên quan trên kết quả tìm | `GET /api/questions`, `/overview`, `GET /api/faqs/related` | Xem | Xem | Có | 1 |
| Chi tiết câu hỏi, câu trả lời, câu trả lời hay nhất | `GET /api/questions/{id}` | Xem | Xem | Có | 1 |
| Đặt câu hỏi (như Viết bài) + gợi ý FAQ khi gõ tiêu đề (≥ 8 ký tự) | `GET …/compose`, `POST /api/questions`, `GET /api/faqs/related` | – | Làm | Có | 1 |
| Trả lời, trả lời lồng | `POST /api/questions/{id}/answers` | – | Làm | Có | 1 |
| Chọn câu trả lời hay nhất (+5 điểm) | `PUT …/best-answer` | – | Làm (người hỏi) | Có | 1 |
| Thích, lưu, báo cáo, sửa, xóa câu hỏi | như Blog | – | Làm | Có | 1 (sửa media: 2) |
| Tab "Câu hỏi thường gặp": chủ đề + số lượng, tìm, mở tại chỗ | `GET /api/faqs`, `/topics`, `/{id}` | Xem | Xem | Có | 1 |

### 2.6 Lễ hội và sự kiện (`feature/event`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Danh sách (Đang diễn ra / 30 ngày tới / Sau này / Đã diễn ra), tìm | `GET /api/events?q=` | Xem | Xem | Có | 1 |
| Chi tiết: ảnh bìa, vị trí, chi phí, mẹo, album ảnh review, review | `GET /api/events/{id}` | Xem | Xem | Có | 1 |
| Viết review sự kiện (sau khi bắt đầu, 1 / người / năm) | `GET …/review-form`, `POST /api/events/{id}/reviews` | – | Làm | Có | 1 (ảnh), video: 2 |
| Sửa / xóa / thích / báo cáo review sự kiện | `/api/event-reviews/{id}[/like|/report]` | – | Làm | Có | 1 |

### 2.7 Cẩm nang (`feature/guide`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Duyệt theo giai đoạn (Sắp sang Nhật / Mới đến / Sắp về nước / Đã về nước) và chủ đề, tìm, sắp xếp | `GET /api/guides/browse`, `/topics`, `/subjects` | Xem | Xem | Có | 1 |
| Chi tiết: các mục có ảnh, nguồn | `GET /api/guides/{id}` | Xem | Xem | Có | 1 |
| Checklist chuẩn bị, % tiến độ | `GET /api/guides/{id}/preparation`, `PUT …/preparation/{taskId}` | Xem (không lưu) | Làm | Có | 1 |
| Trạng thái người xem: đã lưu, đã xong, "Tiến độ x / y việc", việc tiếp theo | `GET /api/guides/{id}/viewer` | Xem (chưa xong việc nào) | Xem | Có (chưa commit) | 1 |
| Lưu / bỏ lưu cẩm nang, "Đánh dấu đã xong" / bỏ đánh dấu | `POST|DELETE /api/guides/{id}/save`, `POST|DELETE /api/guides/{id}/done` | → đăng nhập | Làm | Có (chưa commit) | 1 |
| "Cẩm nang của tôi" trên Tôi: tiến độ 4 giai đoạn, checklist đang làm, cẩm nang đã xong | `GET /api/me/guides/progress` | – | Xem | Có (chưa commit) | 1 |

### 2.8 Nhật ngữ (`feature/phrase`)

| Tính năng | API | Guest | User | Web | Đợt |
|---|---|---|---|---|---|
| Trang đầu: "Chủ đề" và "Địa điểm", mỗi phần chia Từ vựng / Mẫu câu, số lượng, thanh tiến độ (User) | `GET /api/phrases/overview` | Xem | Xem (+ tiến độ "Đã thuộc") | Có | 1 |
| Một bộ theo chủ đề / địa điểm, tìm (tiếng Nhật, romaji, nghĩa) | `GET /api/phrases?topic=&category=&q=` | Xem | Xem | Có | 1 |
| "Nghe" (giọng ja-JP) | client | Làm | Làm | Có | 1 (TTS native) |
| "Đưa cho nhân viên xem" (toàn màn hình, chỉ mẫu câu) | client | Làm | Làm | Có | 1 |
| Thẻ ghi nhớ / "Luyện tập" (Tất cả / Chủ đề / Địa điểm, loại, trạng thái, xáo trộn) | `GET /api/phrases`, `POST|DELETE /api/phrases/{id}/flash` | Lật thẻ (không lưu) | Làm (lưu "Chưa thuộc" / "Đã thuộc") | Có | 1 |
| Danh sách "Chưa thuộc" / "Đã thuộc", lọc Chưa luyện | `GET /api/phrases?mine=learning|learned` | → đăng nhập | Xem | Có | 1 |
| Báo lỗi mẫu câu (Sai tiếng Nhật, Sai nghĩa, Xúc phạm, Spam) | `POST /api/phrases/{id}/report` | – | Làm | Có | 1 |
| **Dùng offline** (cửa hàng, quầy hành chính hay mất sóng) | cache `GET /api/phrases` (≤ 200 mục) | Xem | Xem | Không (web) | 1, đề xuất riêng app |

### 2.9 Tôi (`feature/user`, `feature/tag`, `feature/saved`)

| Tính năng | API | Guest | User | Staff | Web | Đợt |
|---|---|---|---|---|---|---|
| Hồ sơ: tên, email, vai trò, tag, tài khoản mới (hạn mức hôm nay), nhà cung cấp đã liên kết | `GET /api/me/profile` | – | Xem | Xem | Có | 1 |
| Điểm, cấp bậc, đặc quyền, luật điểm, lịch sử điểm (20 / trang) | `/api/me/profile`, `GET /api/me/points?page=` | – | Xem | – | Có | 1 |
| Khu vực và giai đoạn | `PUT /api/me/profile` | – | Làm | – | Có | 1 |
| Chủ đề quan tâm (tối đa 5, như "Điều chỉnh" ở Trang chủ) | `GET|PUT /api/me/interests` | – | Làm | – | Có (chưa commit) | 1 |
| Cẩm nang của tôi | xem 2.7 | – | Xem | – | Có (chưa commit) | 1 |
| Xin tag định danh: chọn tag, loại minh chứng, chụp ảnh, đồng ý cho Moderator xem | `GET /api/me/tag-requests/compose`, `POST /api/me/tag-requests` | – | Làm | – | Có | 1 (camera) |
| Đã lưu: địa điểm, bài viết, câu hỏi, cẩm nang; Bỏ lưu | `GET /api/me/saved` + API lưu của từng loại | – | Làm | – | Có (cẩm nang: chưa commit) | 1 |
| Đổi mật khẩu, đăng xuất, xóa tài khoản | xem 2.1 | – | Làm | Làm | Có | 1 |
| Khiếu nại nội dung bị gỡ (prototype: "Khiếu nại quyết định này", ≥ 10 ký tự, trong 30 ngày) | – | – | Làm | – | **Chưa** | Khi web có |

### 2.10 Thông báo (`feature/notification`, `common/live`)

| Tính năng | API | User | Staff | Web | Đợt |
|---|---|---|---|---|---|
| Danh sách (20 / trang), `link` mở bài / câu hỏi / review / địa điểm / sự kiện | `GET /api/me/notifications?page=` | Xem | Xem | Có | 1 |
| Số chưa đọc, nội dung đang quét, thông báo mới | `GET /api/me/notifications/summary?after=` | Xem | Xem | Có | 1 |
| Đánh dấu đã đọc | `POST /api/me/notifications/read` | Làm | Làm | Có | 1 |
| Luồng trực tiếp (SSE) khi app đang mở | `GET /api/me/live` | Xem | Xem | Có | 1 |
| **Push khi app đóng** cho 14 loại thông báo (bảng dưới) | – | Nhận | – | **Chưa** (Web Push chỉ cho chat) | 1, **cần FCM** (mục 5.4) |

Thông báo server đang gửi (`NotificationService.send`, chỉ gửi cho người dùng, chưa có loại nào cho staff). `link` do
`NotificationRepository` tính theo đối tượng: bài / câu hỏi → `/blog/{id}` / `/qa/{id}`, review → `/map?place=` hoặc
`/events/{id}`; đối tượng `PLACE` và `TAG` **không có link**.

| Loại | Khi nào | Đối tượng | App mở |
|---|---|---|---|
| `CONTENT_PUBLISHED`, `CONTENT_REJECTED` | Bài / câu hỏi / review qua kiểm duyệt hoặc bị từ chối | bài, câu hỏi, review | theo `link` |
| `NEW_COMMENT` | Có bình luận / câu trả lời mới | bài, câu hỏi | theo `link` |
| `BEST_ANSWER` | Câu trả lời được chọn hay nhất (+5 điểm) | câu hỏi | theo `link` |
| `TAG_GRANTED`, `TAG_REJECTED`, `TAG_EXPIRED` | Yêu cầu tag được cấp, bị từ chối, quá hạn chưa duyệt | tag | Tôi → Tag (không có link, app tự ánh xạ theo loại) |
| `PIN_APPROVED`, `PIN_REJECTED`, `PIN_MERGED` | Ghim được duyệt (+điểm), bị từ chối, bị gộp vào địa điểm đã có | địa điểm | Bản đồ tại địa điểm (từ chối: chỉ hiện nội dung) — **cần server trả link** |
| `PLACE_CLOSED`, `PLACE_UPDATED`, `PLACE_REMOVED`, `PLACE_MERGED` | Địa điểm mình cắm ghim bị gắn nhãn đóng cửa, được sửa, bị gỡ, được gộp | địa điểm | Bản đồ tại địa điểm (gỡ: chỉ hiện nội dung) — **cần server trả link** |

### 2.11 Tin nhắn (`feature/chat`) – chỉ User ↔ User

| Tính năng | API | User | Web | Đợt |
|---|---|---|---|---|
| Danh sách hội thoại, tab "Lời mời", số chưa đọc | `GET /api/me/chats`, `/counts` | Làm | Có | 2 |
| Nhắn tin từ bài / bình luận / câu trả lời | `GET|POST /api/me/chats/with/{userId}[/messages]` | Làm | Có | 2 |
| Lời mời (tối đa 3 tin), Chấp nhận / Từ chối / Xóa | `POST …/{id}/accept|decline`, `DELETE …/{id}` | Làm | Có | 2 |
| Chặn / bỏ chặn, "Người đã chặn" | `/api/me/chats/blocks*` | Làm | Có | 2 |
| Báo cáo tin nhắn (kèm bằng chứng giải mã + khóa franking) | `POST /api/me/chats/messages/{id}/report` | Làm | Có | 2 |
| **Mã hóa đầu cuối** sau khi chấp nhận (ECDH P-256, AES-GCM, "mật khẩu khôi phục" PBKDF2 600.000, franking HMAC); server **từ chối** tin plain trong hội thoại E2EE | `/api/me/chats/keys*` | Làm | Có | 2, **phải port `chat-crypto.js`** |
| Ảnh (≤ 5), video (≤ 30 s, bỏ metadata ở máy), thoại (≤ 120 s), mã hóa từng file | `POST|GET /api/me/chats/{id}/files` | Làm | Có | 2 (port `mp4-strip.js`) |
| Sửa (15 phút, 5 lần), thu hồi, cảm xúc, trả lời trích dẫn, "đang soạn tin", đã xem, tìm trong hội thoại | `PUT|DELETE …/messages/{id}`, `/reaction`, `/typing`, `/read`, `/updates` | Làm | Có | 2 |
| Cài đặt thông báo (e-mail, đẩy) | `/api/me/chats/notify/*` | Làm | Có (Web Push) | 2, **cần FCM** |
| Chat nhóm | – | – | Chưa | Sau |

Staff không có Tin nhắn (server trả MSG18), Guest bấm "Nhắn tin" → đăng nhập.

### 2.12 Moderator (`/api/moderator/**`)

| Module (staff-desk) | API | Web | Đề xuất app |
|---|---|---|---|
| Duyệt tag định danh (UC-M01): hàng đợi, ảnh minh chứng (chỉ khi PENDING), Duyệt / Từ chối có lý do | `/api/moderator/tag-requests*` | Có | **Đợt 3 (lite)**: việc 1 phút; màn hình ảnh chặn chụp màn hình (mục 4) |
| Duyệt ghim mới (UC-M06): ghim chờ, địa điểm trong 200 m, Duyệt (tên VI, danh mục) / Từ chối / Gộp vào | `/api/moderator/pins*` | Có | **Đợt 3 (lite)**: Duyệt, Từ chối; Gộp để web |
| Báo đóng cửa (UC-M05): chờ xử lý, đặt / bỏ nhãn, xóa báo cáo | `/api/moderator/closed`, `PUT …/places/{id}/closed`, `DELETE …/closed-reports` | Có | **Đợt 3 (lite)** |
| Báo cáo nội dung (UC-M03), Khiếu nại (UC-M04) | – | **Chưa** | Khi web có → lite, kèm push "Khẩn" |
| Sự kiện (UC-M07) | `/api/moderator/events*` | Có | Web |
| Cẩm nang (UC-M08) | `/api/moderator/guides*` | Có | Web |
| Quản lý FAQ, Đề xuất FAQ | `/api/moderator/faqs*` | Có | Web |
| Địa điểm (UC-M10–M13): tìm, thêm (OSM / tự nhập), sửa, gỡ, khôi phục | `/api/moderator/places*` | Có | Web |
| Gộp trùng lặp (UC-M14) | `/api/moderator/merges*` | Có | Web |
| Chủ đề blog (UC-M09), Danh mục tag (UC-M02) | – | **Chưa** | Web |

### 2.13 AM và Admin – giữ trên web

| Vai trò | Module | Web | App |
|---|---|---|---|
| AM | Tổng quan, Thư viện mẫu câu, Chủ đề học, Báo cáo mẫu câu (`/api/academic/**`) | Có | Web |
| Admin | Tổng quan (biểu đồ), Người dùng và vai trò + chi tiết tài khoản (khóa / mở, đổi vai trò, tối đa 3 Moderator), Cấu hình và từ khóa cấm, tham số an toàn, Nhật ký (`/api/admin/**`) | Có | Web |
| Admin | Hạn mức dịch vụ | Chưa | Web |

## 3. Ma trận rút gọn

✔ = làm được; 👁 = chỉ xem; – = không.

| Nhóm tính năng | Guest | User | Moderator | AM | Admin |
|---|---|---|---|---|---|
| Đăng ký, quên mật khẩu | ✔ | – | – | – | – |
| Đăng nhập (email, Google, Apple) | ✔ | ✔ | ✔ (email, Google) | ✔ (email, Google) | ✔ (email, Google) |
| Trang chủ | khách | cá nhân hóa | 👁 | 👁 | 👁 |
| Tìm kiếm chung | ✔ | ✔ | 👁 | 👁 | 👁 |
| "Dành cho bạn", Chủ đề quan tâm, "Review mới" theo khu vực | – | ✔ | – | – | – |
| Bản đồ: xem, tìm, chi tiết, review, mẫu câu | ✔ | ✔ | 👁 | 👁 | 👁 |
| Lưu địa điểm, báo đóng cửa, viết / sửa / xóa review, thích, báo cáo | – | ✔ | – | – | – |
| Cắm ghim địa điểm mới | – | ✔ (đợt 2) | – | – | – |
| Blog, Hỏi-Đáp, FAQ: xem | ✔ | ✔ | 👁 | 👁 | 👁 |
| Viết bài, đặt câu hỏi, bình luận, trả lời, thích, lưu, báo cáo, chọn hay nhất | – | ✔ | – | – | – |
| Sự kiện: xem / review | 👁 | ✔ | 👁 | 👁 | 👁 |
| Cẩm nang: xem | 👁 | 👁 | 👁 | 👁 | 👁 |
| Cẩm nang: checklist có lưu, lưu cẩm nang, đã xong, tiến độ | – | ✔ | – | – | – |
| Nhật ngữ: xem, Nghe, Đưa nhân viên xem, lật thẻ | ✔ | ✔ | 👁 | 👁 | 👁 |
| Nhật ngữ: lưu Chưa thuộc / Đã thuộc, báo lỗi | – | ✔ | – | – | – |
| Tôi: điểm, lịch sử, khu vực / giai đoạn, Chủ đề quan tâm, Cẩm nang của tôi, xin tag, Đã lưu | – | ✔ | – | – | – |
| Đổi mật khẩu, đăng xuất, xóa tài khoản | – | ✔ | ✔ | ✔ | ✔ |
| Thông báo (danh sách + push) | – | ✔ | ✔ (đợt 3, khi có thông báo cho staff) | – | – |
| Tin nhắn (E2EE, media, thoại) | – | ✔ (đợt 2) | – | – | – |
| Duyệt tag, duyệt ghim, báo đóng cửa | – | – | ✔ (đợt 3) | – | – |
| Quản lý sự kiện, cẩm nang, FAQ, địa điểm, gộp trùng | – | – | web | – | – |
| Quản lý mẫu câu, chủ đề học, báo cáo mẫu câu | – | – | – | web | – |
| Người dùng, cấu hình, từ khóa, nhật ký | – | – | – | – | web |

## 4. Những điều app làm tốt hơn web (nên có ngay)

1. **Camera**: chụp thẳng cho review, bài viết, câu hỏi, ảnh minh chứng tag; app tự chuyển HEIC (mặc định của iPhone)
   sang JPEG ≤ 1600 px trước khi gửi, vì server chỉ đọc được JPEG và PNG (`MediaService.IMAGE_FORMATS`; web làm việc này trong trình duyệt).
2. **GPS**: "Vị trí của tôi", gợi ý khu vực sinh sống gần nhất (`/api/areas/nearest`), cắm ghim tại chỗ đang đứng.
3. **Push FCM** cho mọi thông báo (mục 5.4), không chỉ chat như Web Push.
4. **Nhật ngữ dùng offline**: lưu thư viện (≤ 200 mục, một lần gọi `GET /api/phrases`) để "Đưa cho nhân viên xem" và
   "Nghe" vẫn chạy khi mất sóng; màn hình "Đưa cho nhân viên xem" giữ sáng, chữ to.
5. **Chia sẻ** bài / câu hỏi / địa điểm / sự kiện bằng link web sạch; người nhận có app thì link mở thẳng trong app
   (deep link, mục 5.5).
6. **Chỉ đường**: mở Google Maps hoặc Apple Maps có sẵn trên máy.
7. **Sinh trắc học** (Face ID / vân tay) để mở khóa chat thay vì gõ "mật khẩu khôi phục" (khóa riêng giữ trong
   Keychain / Keystore).
8. **Ảnh minh chứng tag** (Moderator lite): bật `FLAG_SECURE` trên Android, không lưu ảnh vào cache, giống web chặn menu
   chuột phải.
9. **Giữ nháp** bài viết, bình luận, tin nhắn khi app bị hệ điều hành đóng.

## 5. Backend phải thêm hoặc đổi cho app

Mọi API đã tách khỏi giao diện (`ApiResponse`, lỗi theo `fields`), nên phần lớn app gọi được ngay. Phải thêm:

1. **Xác thực.** Web dùng session cookie `JSESSIONID` (14 ngày) + CSRF (`X-XSRF-TOKEN`, `GET /api/auth/csrf`), và
   `SessionUserRefreshFilter` đọc lại vai trò / trạng thái mỗi request.
   - *Giữ session cookie* (đề xuất cho Đợt 1): HTTP client của app có cookie jar, đọc cookie `XSRF-TOKEN` và gửi header
     như `client.js`. Không đổi backend; giữ nguyên khóa tài khoản tức thì, "đăng xuất thiết bị khác" (`UserSessions`).
     Phải test kỹ 401 → đăng nhập lại và 403 CSRF → gửi lại một lần.
   - *Bearer token* (khi cần): bảng refresh token, filter đọc header, bỏ CSRF cho request có Bearer, làm lại "đăng xuất
     thiết bị khác" và đóng luồng live theo token.
2. **Google sign-in native**: `POST /api/auth/google {idToken}` xác minh ID token, rồi `SocialLoginService.findOrLink` →
   `SessionSignIn` (vẫn chặn LOCKED / DELETED, vẫn qua bước hoàn tất 18+ / điều khoản).
3. **Sign in with Apple** (iOS): Apple 4.8 yêu cầu một cách đăng nhập bảo vệ quyền riêng tư khi app có Google; thêm
   provider APPLE và `POST /api/auth/apple {identityToken}`; Apple chỉ trả email lần đầu, có thể là email ẩn
   `@privaterelay.appleid.com`.
4. **Push FCM**: thêm token theo thiết bị (`platform`, `fcm_token`), gửi qua FCM HTTP v1 (miễn phí, iOS qua APNs), và
   **mở rộng push cho `NotificationService.send`** (hiện chỉ chat có push). Giữ luật chat: không gửi nội dung tin,
   giãn cách 30 s, không push khi đang mở đúng hội thoại (`ChatViewers`). Payload push mang `type` và `link` để app mở
   đúng màn hình; vì thông báo đối tượng `PLACE` (7 loại ghim / địa điểm) và `TAG` (3 loại) hiện không có `link`, nên
   cho `NotificationRepository` trả thêm `/map?place={id}` cho địa điểm còn hiện trên bản đồ và `/profile` cho tag
   (web cũng được lợi: bấm thông báo mở được trang). Đợt 3 cần thêm thông báo cho Moderator (hiện server không gửi
   thông báo nào cho staff): ghim mới, yêu cầu tag mới, địa điểm đủ báo đóng cửa.
5. **Deep link / Universal Links / App Links**: link trong email (`/reset-password?token=`, `/messages/{id}`) và `link`
   của thông báo (`/blog/{id}`, `/qa/{id}`, `/events/{id}`, `/map?place=`, `/guides/{id}`) đã là địa chỉ sạch; chỉ cần
   phục vụ `/.well-known/apple-app-site-association` và `/.well-known/assetlinks.json`, và app có bảng ánh xạ path →
   màn hình (dùng chung cho thông báo).
6. **Tin nhắn E2EE**: port `lib/chat-crypto.js` (ECDH P-256 → HKDF, AES-GCM với AAD
   `conversationId|senderId|senderKeyId|recipientKeyId`, PBKDF2-SHA256 600.000, franking HMAC-SHA256) và kiểm bằng test
   vector chung (`ChatEncryptionFlowTest`, `src/test/js/chat-crypto.test.mjs`); port `lib/mp4-strip.js` cho video chat.
   Với bài viết / review thì server đã tự bỏ metadata video.
7. **Media**: URL media là đường dẫn tương đối (`/media/…`), app ghép với địa chỉ server; file riêng tư (file của chính
   mình đang quét, ảnh minh chứng, file chat) cần cookie / token, nên thư viện ảnh của app dùng chung HTTP client đã
   đăng nhập. Giới hạn upload: 50 MB / file, 160 MB / request; nên nén video iPhone (HEVC MOV) trước khi gửi.
8. **Bản đồ**: MapLibre Native (miễn phí) với style OpenFreeMap "Liberty" như web; Photon / Nominatim vẫn đi qua
   server (`/api/map/search`, `/api/places/search`, `/api/map/nearby`), app không gọi dịch vụ ngoài.
9. **SSE** `GET /api/me/live` khi app mở; khi app ở nền thì dựa vào FCM; giữ polling chậm làm lưới an toàn.
10. **Giới hạn theo địa chỉ IP**: đăng ký đang giới hạn 5 mã / giờ / IP (`request.getRemoteAddr()`); mạng di động Nhật
    và Việt Nam dùng CGNAT (rất nhiều máy chung một IP), nên giới hạn này có thể chặn nhầm người dùng app → cần chốt lại
    (nới cho app, hoặc dựa vào giới hạn theo hộp thư và toàn trang đã có).
11. **Phiên bản app tối thiểu**: app trên store không cập nhật ngay được; thêm `GET /api/app/config` (phiên bản tối thiểu,
    thông báo bảo trì) và giữ API tương thích ngược (chỉ thêm trường, không đổi nghĩa trường cũ).
12. **CORS** hiện chỉ cho `localhost:8080`; app native không gửi `Origin` nên không ảnh hưởng (chỉ phải sửa nếu dùng
    Flutter Web / Capacitor).
13. **Yêu cầu của kho ứng dụng**: xóa tài khoản trong app (có), chính sách bảo mật (có), báo cáo nội dung (có), lọc từ
    khóa (có), 18+ (có); **chặn người dùng** (Apple 1.2): web chỉ chặn trong chat, nên cân nhắc mở rộng thành "ẩn nội
    dung của người này" ở feed / bình luận; khai báo **App Privacy / Data safety**: ảnh và video gửi tới OpenAI
    Moderation và Google Gemini (bản miễn phí: Google có thể dùng để cải thiện sản phẩm), e-mail, khu vực sinh sống.
14. **Chưa có trên web, app chờ web làm trước** (tránh hai nơi hai luật): hồ sơ công khai, huy hiệu cấp bậc cạnh tên,
    khiếu nại của User + Báo cáo nội dung / Khiếu nại của Moderator, chủ đề blog, đăng nhập Facebook, chat nhóm.
15. **API app không gọi**: `/api/scholarships` (luôn trả 404 "sắp ra mắt", học bổng ngoài phạm vi); `/api/events/pins`
    (trùng `/api/map/events`, app chỉ dùng cái sau); `/api/blogs/scam-warnings` (dấu "!" đã có trong dữ liệu
    `/api/map`).
16. **Id ứng dụng**: project Flutter còn `com.example.myasvsj` (Android `namespace` / `applicationId`, iOS bundle id);
    Google Play và App Store không nhận `com.example`, phải đổi (ví dụ `vn.senpaioi.app`) trước khi tạo OAuth client
    Google, cấu hình Firebase và file `assetlinks.json` / `apple-app-site-association`, vì cả ba gắn với id này.

## 6. Lộ trình đề xuất

| Đợt | Gồm | Vai trò |
|---|---|---|
| **1 – Lõi** | Đăng ký / đăng nhập (email, Google native, Apple), quên / đổi mật khẩu, xóa tài khoản; Trang chủ khách và thành viên; Tìm kiếm chung; "Dành cho bạn" + Chủ đề quan tâm; Bản đồ (xem, tìm, chi tiết, review, mẫu câu, lưu, báo đóng cửa, viết / sửa / xóa review có ảnh); "Review mới"; Blog và Hỏi-Đáp (xem, viết có ảnh, bình luận / trả lời, thích, lưu, báo cáo, trả lời hay nhất, sửa chữ, xóa); FAQ; Sự kiện (xem, review có ảnh); Cẩm nang (checklist, lưu, đã xong, tiến độ); Nhật ngữ (đầy đủ + offline); Tôi (điểm, lịch sử, khu vực / giai đoạn, Chủ đề quan tâm, Cẩm nang của tôi, xin tag bằng camera, Đã lưu); Thông báo + push FCM | Guest, User (staff: chỉ xem) |
| **2 – Giao tiếp** | Tin nhắn đầy đủ (E2EE, ảnh / video / thoại, sửa, thu hồi, cảm xúc, chặn, báo cáo, cài đặt thông báo); Cắm ghim; video trong bài / câu hỏi / review; sửa bài có thay media | User |
| **3 – Moderator lite** | Duyệt tag, duyệt / từ chối ghim, báo đóng cửa, push khi có việc mới; khi web có: báo cáo nội dung và khiếu nại với push "Khẩn" | Moderator |
| Web | Phần quản lý còn lại của Moderator, toàn bộ AM và Admin | Mod, AM, Admin |

Thanh tab điện thoại giữ như web và prototype: **Trang chủ · Bản đồ · Blog · Hỏi-Đáp · Thêm** (Thêm gom Nhật ngữ,
Sự kiện, Cẩm nang).

Thư viện Flutter đề xuất:

| Phần | Thư viện |
|---|---|
| Gọi API, giữ session cookie + header XSRF | `dio` + `cookie_jar` / `dio_cookie_manager` |
| Bản đồ (style OpenFreeMap như web), GPS | `maplibre_gl`, `geolocator` |
| Push | `firebase_messaging` |
| "Nghe" tiếng Nhật, giữ màn hình sáng | `flutter_tts`, `wakelock_plus` |
| Ảnh, video (HEIC → JPEG, nén video) | `image_picker`, `flutter_image_compress`, `video_compress` |
| Deep link, mở link ngoài / Chỉ đường, chia sẻ | `app_links`, `url_launcher`, `share_plus` |
| Mã hóa chat (ECDH P-256, AES-GCM, PBKDF2, HKDF, HMAC) | `webcrypto` |
| Giữ khóa chat, mở bằng vân tay / Face ID | `flutter_secure_storage`, `local_auth` |
| Tin nhắn thoại | `record` |

## 7. Cần chốt

Framework đã chốt là Flutter (project `myasvsj`), còn lại:

1. Xác thực Đợt 1: giữ session cookie (không đổi backend) hay làm Bearer token ngay.
2. Staff đăng nhập app: cho xem (đề xuất) hay chặn hẳn.
3. Có làm Moderator lite (Đợt 3) không.
4. Nhật ngữ offline và "chặn người dùng" ngoài chat.
5. Có cho server trả `link` cho thông báo địa điểm / tag (mục 5.4) không.
6. Phát hành: iOS cần tài khoản Apple Developer (99 USD / năm, cả TestFlight cũng cần) và một máy Mac để build;
   Google Play 25 USD một lần. Không có ngân sách: Android trước (APK cài trực tiếp cho buổi bảo vệ), iOS chạy thử trên
   iPhone của nhóm bằng Xcode với Apple ID miễn phí (phải cài lại sau 7 ngày).
