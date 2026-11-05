# Senpai ơi – Phân tích tính năng cho mobile app

Ngày rà soát: 05-11-2026, nhánh `ductq` (sau khi merge PR #13 HandBook). Nguồn: toàn bộ controller trong
`src/main/java/com/sep490/vsa`, `SecurityConfig`, `CleanUrls`, các trang trong `static/pages`, `site-header.js`,
`staff-desk.js`, schema Flyway V1–V16 và `docs/database/database-design.md`.

## 1. Kết luận

| Vai trò | Có trên app? | Lý do |
|---|---|---|
| **Guest** (chưa đăng nhập) | **Có, đầy đủ phần xem** | Web đã cho guest xem mọi `GET /api/**`. Store (Apple 5.1.1) cũng yêu cầu xem được nội dung trước khi bắt đăng ký. |
| **User** (Thành viên) | **Có, đầy đủ** | Đây là đối tượng duy nhất prototype vẽ khung điện thoại; mọi tính năng cộng đồng (review, bài viết, hỏi-đáp, ghim, chat, thông báo) đều là của User và được lợi nhất từ camera, GPS, push. |
| **Moderator** | **Một phần, "Moderator lite", làm sau MVP** | Tối đa 3 người. Các hàng đợi duyệt ngắn (tag, ghim, báo đóng cửa, sau này báo cáo nội dung và khiếu nại) hợp với điện thoại và push. Các màn hình sửa dữ liệu nặng (form địa điểm có bản đồ, gộp trùng, soạn cẩm nang, FAQ, sự kiện) giữ trên web. |
| **Academic Manager** | **Không (web)** | Toàn bộ 5 module còn "Đang phát triển" trên web; chưa có API nào dưới `/api/academic/**`. |
| **Admin** | **Không (web)** | Bảng người dùng, cấu hình, từ khóa cấm, nhật ký: màn hình bảng rộng, nhạy cảm, 1–2 người dùng. |

Thứ tự làm đề xuất: **Đợt 1** Guest + User không có chat; **Đợt 2** Tin nhắn (mã hóa đầu cuối), Cắm ghim, sửa bài có
media; **Đợt 3** Moderator lite. Phần 4 liệt kê những gì backend phải thêm trước khi app chạy được.

## 2. Bảng tính năng × vai trò

Ký hiệu: **Xem** = chỉ đọc; **Làm** = thao tác ghi; **–** = không có; **Web** = giữ trên web, không đưa lên app.
Cột "Đợt" là đề xuất cho app. "Trạng thái web" nói tính năng đó trên web đã có hay chưa (app chỉ gọi lại API đã có).

### 2.1 Tài khoản và xác thực (`feature/auth`, `feature/legal`, `feature/user`)

| Tính năng | API | Guest | User | Mod | AM | Admin | Trạng thái web | Đợt |
|---|---|---|---|---|---|---|---|---|
| Đăng ký email: gửi mã → nhập mã → hoàn tất (tên, mật khẩu, 18+, đồng ý điều khoản) | `POST /api/auth/register/*` | Làm | – | – | – | – | Có | 1 |
| Đăng nhập email + mật khẩu (khóa 30 phút sau 5 lần sai) | `POST /api/auth/login` | Làm | Làm | Làm | Làm | Làm | Có | 1 |
| Đăng nhập Google | OAuth2 redirect (`oauth2Login`) | Làm | Làm | – | – | – | Có (khi có client id) | 1, **cần API mới** (mục 4) |
| Đăng nhập Facebook | – | – | – | – | – | – | Chưa ("sau này") | Sau |
| Đăng nhập Apple | – | – | – | – | – | – | Chưa | 1 trên iOS, **bắt buộc** (Apple 4.8 khi có Google) |
| Hoàn tất đăng ký qua mạng xã hội (tên, 18+, điều khoản) | `GET /api/auth/social/pending`, `POST …/social/complete` | Làm | – | – | – | – | Có | 1 |
| Quên mật khẩu (link email) và đặt lại | `POST /api/auth/password/forgot|check|reset` | Làm | – | – | – | – | Có | 1 (link `/reset-password?token=` cần deep link) |
| Đổi / tạo mật khẩu, đăng xuất mọi thiết bị khác | `PUT /api/me/password` | – | Làm | Làm | Làm | Làm | Có | 1 |
| Xem Điều khoản, Chính sách, Quy tắc cộng đồng | `GET /api/legal/{type}` | Xem | Xem | Xem | Xem | Xem | Có | 1 (store yêu cầu link chính sách) |
| Đăng xuất | `POST /api/auth/logout` | – | Làm | Làm | Làm | Làm | Có | 1 |
| Xóa tài khoản (tự xóa; Admin cuối cùng bị từ chối) | `DELETE /api/me` | – | Làm | Làm | Làm | Làm | Có | 1 (Apple 5.1.1(v) bắt buộc) |

Lưu ý: tài khoản staff đăng nhập vào app sẽ chỉ xem được nội dung công khai và (Moderator) hàng đợi của mình; mọi
thao tác cộng đồng (thích, bình luận, review, chat) web đã từ chối staff bằng MSG18 (403). App nên hiện câu
"Tài khoản quản lý: dùng trang web để làm việc" thay vì giấu lỗi.

### 2.2 Trang chủ (`feature/home`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Trang chủ khách: số liệu, 2 review "Hữu ích", mẫu câu trong ngày | `GET /api/home` | Xem | – | Có | 1 |
| Trang chủ thành viên: lời chào, "Dành cho bạn" (5 bài, lý do gợi ý, chip Dựa trên), "Việc tiếp theo" (cẩm nang theo giai đoạn), "Review mới quanh …", sự kiện, mẫu câu, "Bài nổi bật" | `GET /api/me/home` | – | Xem | Có | 1 |
| Nhắc chọn khu vực + giai đoạn ("Để sau", "Điều chỉnh") | `PUT /api/me/profile` | – | Làm | Có | 1 |
| Trang "Review mới" (theo khu vực / tỉnh / tất cả, 10 một trang) | `GET /api/home/reviews?scope=&page=` | Xem | Xem | Có | 1 |

### 2.3 Bản đồ và địa điểm (`feature/place`, `feature/review`, `feature/area`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Xem bản đồ: mọi địa điểm ACTIVE có tọa độ, danh mục + số lượng, ghim sáng/mờ/vuông/xám, "!" cảnh báo lừa đảo, tâm bản đồ = khu vực của tôi | `GET /api/map` | Xem | Xem (+ ghim PENDING của mình) | Có | 1 |
| Lớp sự kiện 15 / 30 ngày (ghim đèn lồng) | `GET /api/map/events?days=` | Xem | Xem | Có | 1 |
| Tìm địa điểm / danh mục | `GET /api/map/search?q=` | Xem (chỉ địa điểm Senpai ơi, `external: LOGIN`) | Xem (+ OpenStreetMap) | Có | 1 |
| "Địa điểm quanh đây" (bấm lên bản đồ) | `GET /api/map/nearby` | – (chỉ client-side với POI của style) | Xem | Có | 1 |
| Chi tiết địa điểm (điểm TB, số review, đã đóng cửa, đã lưu, nhãn cảnh báo) | `GET /api/map/places/{id}` | Xem | Xem | Có | 1 |
| Review của địa điểm (10/trang) | `GET /api/map/places/{id}/reviews` | Xem | Xem | Có | 1 |
| Từ vựng và mẫu câu theo danh mục của địa điểm (+ "Nghe") | `GET /api/map/places/{id}/phrases` | Xem | Xem | Có | 1 |
| Lưu / bỏ lưu địa điểm | `POST|DELETE …/save` | – | Làm | Có | 1 |
| Báo "đã đóng cửa" (1 lần/người) | `POST …/closed-report` | – | Làm | Có | 1 |
| Thích / báo cáo review | `POST|DELETE /api/reviews/{id}/like`, `POST …/report` | – | Làm | Có | 1 |
| Viết review (1–5 sao, 10–2.000 ký tự, ≤ 5 ảnh, 1 video ≤ 30 s), 1 review/người/địa điểm, review cho địa điểm OSM chưa có | `GET /api/reviews/compose`, `POST /api/reviews` | – | Làm | Có | 1 (ảnh), video đợt 2 |
| Sửa / xóa review của mình | `GET …/edit`, `PUT`, `DELETE /api/reviews/{id}` | – | Làm | Có | 1 |
| Cắm ghim địa điểm mới (kéo ghim, kiểm tra trùng 200 m, review đầu tiên, chờ Moderator) | `GET /api/map/pins/compose|duplicates`, `POST /api/map/pins` | – | Làm | Có | 2 |
| Vị trí của tôi (GPS) | client | Làm | Làm | Có (trình duyệt) | 1 (GPS native) |
| Chỉ đường (mở Google Maps / Apple Maps) | client | Làm | Làm | Có | 1 |
| Khu vực sinh sống: tỉnh → quận/thành phố, tìm, gần nhất theo GPS | `GET /api/areas*` | – | Làm | Có | 1 |

### 2.4 Blog (`feature/blog`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Danh sách bài: tìm, lọc chủ đề, sắp xếp, phân trang; Bài nổi bật tuần/tháng; thống kê | `GET /api/blogs`, `GET /api/blogs/overview` | Xem | Xem (+ bài PROCESSING của mình) | Có | 1 |
| Chi tiết bài: media, địa điểm liên quan (bản đồ nhỏ, Chỉ đường), cây bình luận | `GET /api/blogs/{id}` | Xem | Xem | Có | 1 |
| Thích, lưu, báo cáo bài | `POST|DELETE …/like|save`, `POST …/report` | – | Làm | Có | 1 |
| Bình luận, trả lời (15 s/lần, 20/ngày tài khoản mới) | `POST …/comments` | – | Làm | Có | 1 |
| Thích, báo cáo, sửa, xóa bình luận | `/api/comments/{id}/*` | – | Làm | Có | 1 |
| Viết bài: tiêu đề 150, nội dung, 1–3 chủ đề, ≤ 5 địa điểm (tìm OSM), ≤ 5 ảnh, 1 video; kiểm tra link (BR-22), QR, từ khóa cấm, giới hạn tài khoản mới | `GET /api/blogs/compose`, `GET /api/places/search`, `POST /api/blogs` | – | Làm | Có | 1 (ảnh), video đợt 2 |
| Sửa bài của mình (giữ media `keepMediaIds`) | `GET …/edit`, `PUT /api/blogs/{id}` | – | Làm | Có | 2 |
| Xóa bài của mình | `DELETE /api/blogs/{id}` | – | Làm | Có | 1 |
| Cảnh báo lừa đảo theo địa điểm (cho "!" trên Map) | `GET /api/blogs/scam-warnings` | Xem | Xem | Có | 1 |
| Hồ sơ công khai của tác giả | – | – | – | **Chưa** (coming-soon) | Khi web có |

### 2.5 Hỏi-Đáp (`feature/qa`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Danh sách câu hỏi: tìm, chủ đề, trạng thái (Chưa trả lời / Chưa giải quyết / Đã giải quyết), sắp xếp | `GET /api/questions`, `GET …/overview` | Xem | Xem | Có | 1 |
| Chi tiết câu hỏi, câu trả lời, câu trả lời hay nhất | `GET /api/questions/{id}` | Xem | Xem | Có | 1 |
| Đặt câu hỏi (như Viết bài) | `GET …/compose`, `POST /api/questions` | – | Làm | Có | 1 |
| Trả lời, trả lời lồng | `POST …/answers` | – | Làm | Có | 1 |
| Chọn câu trả lời hay nhất (+5 điểm cho người trả lời) | `PUT …/best-answer` | – | Làm (người hỏi) | Có | 1 |
| Thích, lưu, báo cáo, sửa, xóa câu hỏi | như Blog | – | Làm | Có | 1 (sửa: 2) |

### 2.6 Lễ hội và sự kiện (`feature/event`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Danh sách (Đang diễn ra / 30 ngày tới / Sau này / Đã diễn ra), tìm | `GET /api/events?q=` | Xem | Xem | Có | 1 |
| Chi tiết: ảnh bìa, vị trí, chi phí, mẹo của Moderator, album ảnh review, review | `GET /api/events/{id}` | Xem | Xem | Có | 1 |
| Viết review sự kiện (sau khi bắt đầu, 1/người/năm, ảnh + video) | `GET …/review-form`, `POST …/reviews` | – | Làm | Có | 1 |
| Sửa / xóa / thích / báo cáo review sự kiện | `/api/event-reviews/{id}/*` | – | Làm | Có | 1 |

### 2.7 Cẩm nang (`feature/guide`, V7–V8, V11–V13)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Duyệt cẩm nang theo giai đoạn (PRE / ARR / LEAVE / HOME) và chủ đề, tìm, sắp xếp, 12/trang | `GET /api/guides/browse`, `/topics`, `/subjects` | Xem | Xem | Có | 1 |
| Chi tiết: các mục (section) có ảnh, nguồn | `GET /api/guides/{id}` | Xem | Xem | Có | 1 |
| Checklist chuẩn bị, % tiến độ | `GET /api/guides/{id}/preparation` | Xem (không lưu) | Làm (`PUT …/preparation/{taskId}`) | Có | 1 |
| "Việc tiếp theo" trên trang chủ theo giai đoạn | trong `/api/me/home` | – | Xem | Có | 1 |

### 2.8 FAQ (`feature/faq`, V16)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Danh sách theo chủ đề, tìm, chi tiết (liên kết xem thêm) | `GET /api/faqs`, `/topics`, `/{id}` | Xem | Xem | Có | 1 |

### 2.9 Học tiếng Nhật (tab "Học", `phrases`, `learning_topics`)

| Tính năng | API | Guest | User | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Mẫu câu trong ngày (trang chủ) + "Nghe" | trong `/api/home`, `/api/me/home` | Xem | Xem | Có | 1 (TTS native) |
| Mẫu câu theo danh mục địa điểm (trong panel địa điểm) | `GET /api/map/places/{id}/phrases` | Xem | Xem | Có | 1 |
| Tab "Học": thư viện mẫu câu theo chủ đề, Sổ tay / Đã học, câu luyện tập | – | – | – | **Chưa** (tab → coming-soon; bảng `user_phrase_marks`, `practice_sentences` có sẵn) | Khi web có |

### 2.10 Tôi – hồ sơ cá nhân (`feature/user`, `feature/tag`, `feature/saved`)

| Tính năng | API | Guest | User | Mod/AM/Admin | Trạng thái web | Đợt |
|---|---|---|---|---|---|---|
| Hồ sơ: tên, email, vai trò, tag, tài khoản mới (hạn mức hôm nay), nhà cung cấp đã liên kết | `GET /api/me/profile` | – | Xem | Xem | Có | 1 |
| Điểm, cấp bậc, đặc quyền, lịch sử điểm (20/trang) | `/api/me/profile`, `GET /api/me/points` | – | Xem | – | Có | 1 |
| Khu vực và giai đoạn | `PUT /api/me/profile` | – | Làm | – | Có | 1 |
| Xin tag định danh: chọn tag, loại minh chứng, chụp/chọn ảnh, đồng ý cho Moderator xem; 1 yêu cầu chờ | `GET /api/me/tag-requests/compose`, `POST /api/me/tag-requests` | – | Làm | – | Có | 1 (camera là điểm mạnh của app) |
| Đã lưu: địa điểm, bài viết, câu hỏi | `GET /api/me/saved` | – | Xem/Làm (bỏ lưu) | – | Có | 1 |
| Hồ sơ công khai, Sổ tay tiếng Nhật | – | – | – | – | **Chưa** | Khi web có |

### 2.11 Thông báo (`feature/notification`, `common/live`)

| Tính năng | API | User | Staff | Trạng thái web | Đợt |
|---|---|---|---|---|---|
| Danh sách thông báo (20/trang), link tới bài / câu hỏi / review / địa điểm | `GET /api/me/notifications?page=` | Xem | Xem | Có | 1 |
| Số chưa đọc, nội dung đang quét, 5 thông báo mới | `GET …/summary?after=` | Xem | Xem | Có | 1 |
| Đánh dấu đã đọc | `POST …/read` | Làm | Làm | Có | 1 |
| Luồng trực tiếp (SSE) báo có thông báo / chat mới | `GET /api/me/live` | Xem | Xem | Có | 1 (chỉ khi app mở) |
| **Thông báo đẩy khi app đóng** (bài đã duyệt / bị từ chối, bình luận mới, câu trả lời hay nhất, tag được cấp, ghim được duyệt) | – | – | – | **Chưa** (web chỉ push cho chat) | 1, **cần FCM** (mục 4) |

### 2.12 Tin nhắn (`feature/chat`, V14–V15) – chỉ User ↔ User

| Tính năng | API | User | Trạng thái web | Đợt |
|---|---|---|---|---|
| Danh sách cuộc trò chuyện, tab "Lời mời", số chưa đọc | `GET /api/me/chats`, `/counts` | Làm | Có | 2 |
| Nhắn tin từ bài / bình luận / câu trả lời (`?to=`) | `GET|POST /api/me/chats/with/{userId}[/messages]` | Làm | Có | 2 |
| Lời mời: tối đa 3 tin, Chấp nhận / Từ chối / Xóa | `POST …/accept|decline`, `DELETE /{id}` | Làm | Có | 2 |
| Chặn / bỏ chặn, danh sách người đã chặn | `/api/me/chats/blocks*` | Làm | Có | 2 |
| Báo cáo tin nhắn (kèm bằng chứng giải mã + khóa franking) | `POST …/messages/{id}/report` | Làm | Có | 2 |
| **Mã hóa đầu cuối** sau khi chấp nhận: khóa ECDH P-256, "mật khẩu khôi phục" (PBKDF2 600.000), AES-GCM, HMAC franking; server **từ chối** tin nhắn plain trong hội thoại E2EE | `/api/me/chats/keys*` | Làm | Có | 2, **phải port `chat-crypto.js`** |
| Ảnh (≤ 5), video (≤ 30 s, bỏ metadata ở client), tin nhắn thoại (≤ 120 s), mã hóa từng file | `POST|GET …/files` | Làm | Có | 2 (port `mp4-strip.js`) |
| Sửa (15 phút, 5 lần), thu hồi, cảm xúc, trả lời trích dẫn, "đang soạn tin", đã xem, tìm trong hội thoại | `PUT|DELETE …/messages/{id}`, `/reaction`, `/typing`, `/read`, `/updates` | Làm | Có | 2 |
| Cài đặt thông báo (e-mail, đẩy) | `/api/me/chats/notify/settings`, `/push-subscriptions` | Làm | Có (Web Push) | 2, **cần FCM** |

### 2.13 Moderator (`/api/moderator/**`)

| Module (staff-desk) | API | Trạng thái web | Đề xuất app |
|---|---|---|---|
| Duyệt tag định danh: hàng đợi, xem ảnh minh chứng (chỉ khi PENDING), Duyệt / Từ chối (lý do) | `/api/moderator/tag-requests*` | Có | **Đợt 3 (lite)**: việc 1 phút, ảnh xem trên điện thoại ổn |
| Duyệt ghim mới: hàng đợi, địa điểm quanh 200 m, Duyệt (tên VI, danh mục) / Từ chối / Gộp vào | `/api/moderator/pins*` | Có | **Đợt 3 (lite)**: Duyệt, Từ chối; Gộp để web |
| Báo cáo đóng cửa: chờ xử lý, đặt / bỏ nhãn, xóa báo cáo | `/api/moderator/closed`, `PUT …/places/{id}/closed` | Có | **Đợt 3 (lite)** |
| Báo cáo nội dung (UC-M03), Khiếu nại (UC-M04) | – | **Chưa** | Khi web có → lite, kèm push "Khẩn" |
| Sự kiện: tạo / sửa / xóa, chọn vị trí trên bản đồ, ảnh bìa | `/api/moderator/events*` | Có | Web |
| Cẩm nang: soạn nhiều mục có ảnh, nháp / xuất bản, checklist | `/api/moderator/guides*` | Có | Web |
| Quản lý FAQ, Đề xuất FAQ từ câu trả lời hay nhất | `/api/moderator/faqs*` | Có | Web |
| Địa điểm: tìm, xem lịch sử, thêm (OSM / tự nhập, vị trí trên bản đồ), sửa, gỡ (lý do), khôi phục | `/api/moderator/places*` | Có | Web |
| Gộp trùng lặp: hàng đợi tự tính, so sánh, chọn trường giữ lại, "Không trùng" | `/api/moderator/merges*` | Có | Web |
| Chủ đề blog (UC-M09) | – | **Chưa** | Web |

### 2.14 Academic Manager và Admin

| Vai trò | Module | Trạng thái web | Đề xuất app |
|---|---|---|---|
| AM | Thư viện mẫu câu, Chủ đề học, Báo cáo mẫu câu, Báo cáo câu luyện tập, Duyệt câu luyện tập (UC-A01..A05) | **Chưa** (toàn bộ "Đang phát triển") | Web |
| Admin | Người dùng và vai trò (khóa / mở, đổi vai trò, tối đa 3 Moderator), Cấu hình hệ thống, Từ khóa cấm, Nhật ký, Hạn mức dịch vụ | Có (trừ Hạn mức) | Web |

## 3. Bảng tổng hợp theo vai trò (rút gọn)

| Nhóm tính năng | Guest | User | Moderator (lite) | AM | Admin |
|---|---|---|---|---|---|
| Đăng ký / đăng nhập / quên mật khẩu | ✔ | ✔ | ✔ (đăng nhập) | ✔ (đăng nhập) | ✔ (đăng nhập) |
| Trang chủ | khách | cá nhân hóa | – | – | – |
| Bản đồ: xem, tìm, chi tiết, review, mẫu câu | ✔ | ✔ | xem | xem | xem |
| Lưu địa điểm, báo đóng cửa, viết / sửa / xóa review, thích, báo cáo | – | ✔ | – | – | – |
| Cắm ghim địa điểm mới | – | ✔ (đợt 2) | – | – | – |
| Blog, Hỏi-Đáp: xem | ✔ | ✔ | xem | xem | xem |
| Blog, Hỏi-Đáp: viết, bình luận, trả lời, thích, lưu, báo cáo, chọn trả lời hay nhất | – | ✔ | – | – | – |
| Sự kiện: xem | ✔ | ✔ | xem | xem | xem |
| Sự kiện: review | – | ✔ | – | – | – |
| Cẩm nang, FAQ: xem | ✔ | ✔ | xem | xem | xem |
| Checklist chuẩn bị (lưu tiến độ) | – | ✔ | – | – | – |
| Mẫu câu trong ngày, mẫu câu theo địa điểm, Nghe | ✔ | ✔ | xem | xem | xem |
| Tôi: điểm, cấp bậc, lịch sử điểm, khu vực / giai đoạn, xin tag, Đã lưu | – | ✔ | hồ sơ + mật khẩu | hồ sơ + mật khẩu | hồ sơ + mật khẩu |
| Thông báo (danh sách + push) | – | ✔ | ✔ | ✔ | ✔ |
| Tin nhắn (E2EE, media, thoại) | – | ✔ (đợt 2) | – (MSG18) | – | – |
| Duyệt tag, duyệt ghim, báo đóng cửa | – | – | ✔ (đợt 3) | – | – |
| Sự kiện, cẩm nang, FAQ, địa điểm, gộp trùng (quản lý) | – | – | web | – | – |
| Mẫu câu, câu luyện tập (quản lý) | – | – | – | web | – |
| Người dùng, cấu hình, từ khóa, nhật ký | – | – | – | – | web |
| Đổi mật khẩu, đăng xuất, xóa tài khoản | – | ✔ | ✔ | ✔ | ✔ |

## 4. Backend phải thêm hoặc đổi gì cho app

Đọc từ code hiện tại; mọi thứ khác app gọi được ngay vì API đã tách khỏi giao diện (`ApiResponse`, lỗi theo `fields`).

1. **Xác thực.** Web dùng session cookie `JSESSIONID` + CSRF (`X-XSRF-TOKEN` từ `GET /api/auth/csrf`), và
   `SessionUserRefreshFilter` đọc lại vai trò / trạng thái mỗi request. Hai hướng:
   - *Giữ session cookie*: HTTP client của app (OkHttp, URLSession, Dio) có cookie jar, app đọc cookie `XSRF-TOKEN`
     và gửi header như `client.js`. **Không đổi backend**, giữ nguyên khóa tài khoản tức thì, "đăng xuất mọi thiết bị"
     (`UserSessions`) và session 14 ngày. Nhược: cookie dễ mất khi hệ điều hành dọn app; cần test kỹ 401 → đăng nhập lại.
   - *Token (Bearer)*: thêm bảng token / refresh token, filter đọc header, bỏ CSRF cho `/api/**` có Bearer, làm lại
     "đăng xuất thiết bị khác" và đóng luồng live theo token. Sạch hơn cho app, nhưng là việc mới cho backend.
   Đề xuất: đợt 1 dùng session cookie để có app sớm; chuyển token khi cần nhiều thiết bị / Apple sign-in.
2. **Google sign-in native.** Web đi qua redirect OAuth2 của Spring. App dùng Google Sign-In SDK và nhận **ID token**;
   cần `POST /api/auth/google {idToken}` xác minh token (thư viện `google-api-client` hoặc endpoint `tokeninfo`), rồi gọi
   `SocialLoginService.findOrLink` → `SessionSignIn` (vẫn tôn trọng LOCKED / DELETED và luồng "complete" 18+ / điều khoản).
3. **Sign in with Apple** (iOS): bắt buộc khi app có Google. Thêm provider APPLE vào `user_auth_providers`, endpoint
   `POST /api/auth/apple {identityToken}` xác minh JWT của Apple; Apple chỉ trả email lần đầu (hoặc email ẩn
   `@privaterelay.appleid.com`).
4. **Push FCM.** Web Push hiện chỉ cho chat (`ChatPushNotifier`, VAPID, `push_subscriptions`, chỉ endpoint https của
   các dịch vụ push). App cần Firebase Cloud Messaging (Android; iOS qua APNs cũng gửi được từ FCM, miễn phí): thêm
   cột `platform` / `fcm_token` vào `push_subscriptions` (hoặc bảng mới), gửi qua FCM HTTP v1 bằng service account,
   và **mở rộng push cho `NotificationService.send`** (web chưa push loại này). Giữ nguyên quy tắc không gửi nội dung
   tin nhắn, 30 s giãn cách, không push khi đang mở đúng hội thoại (`ChatViewers`).
5. **Deep link / Universal Links.** Link trong email (`app.public-url` + `/reset-password?token=`, `/messages/{id}`) và
   trong thông báo (`/blog/{id}`, `/qa/{id}`, `/events/{id}`, `/map?place=`) đã là địa chỉ sạch, chỉ cần phục vụ
   `/.well-known/apple-app-site-association` và `/.well-known/assetlinks.json` từ `static/`, và app đăng ký các path đó.
6. **Tin nhắn mã hóa đầu cuối.** Server từ chối tin plain trong hội thoại E2EE, nên app **phải** có mã hóa ngay khi làm
   chat: port `lib/chat-crypto.js` (ECDH P-256 → HKDF, AES-GCM với AAD `conversationId|senderId|senderKeyId|recipientKeyId`,
   PBKDF2-SHA256 600.000 bọc khóa riêng, HMAC-SHA256 franking) và kiểm tra bằng test vector chung trong
   `ChatEncryptionFlowTest` / `src/test/js/chat-crypto.test.mjs`. Khóa giữ trong Keychain / Keystore thay cho IndexedDB.
   Video chat phải bỏ metadata ở client (`lib/mp4-strip.js`); với bài viết / review thì server đã làm
   (`Mp4Duration.stripMetadata`), app chỉ cần kiểm tra ≤ 30 s hoặc để server báo lỗi.
7. **Media.** Upload multipart giữ nguyên; app nên thu ảnh về JPEG ≤ 1600 px trước khi gửi như web. File riêng tư
   (`/media/` của chủ sở hữu, ảnh minh chứng tag, file chat) cần gửi cookie / token kèm request ảnh, nên thư viện ảnh
   của app phải dùng chung HTTP client đã đăng nhập.
8. **Bản đồ.** Dữ liệu là OpenStreetMap: app dùng MapLibre Native (miễn phí) với style OpenFreeMap "Liberty" như web,
   hoặc OSM raster tiles (chính sách tile của OSM yêu cầu User-Agent riêng và không dùng nặng). Photon / Nominatim
   vẫn gọi qua server (`/api/map/search`, `/api/places/search`, `/api/map/nearby`), nên app không chạm dịch vụ ngoài.
9. **Luồng live (SSE)** `GET /api/me/live` dùng được trong app khi mở (OkHttp-SSE, URLSession); khi app ở nền thì
   dựa vào FCM. Giữ polling chậm như web làm lưới an toàn.
10. **CORS** chỉ cho `localhost:8080`; app native không gửi `Origin` nên không ảnh hưởng. Nếu chọn Flutter Web /
    Capacitor thì phải thêm origin.
11. **Yêu cầu của kho ứng dụng** (đã có sẵn phần lớn): xóa tài khoản trong app (có), chính sách bảo mật (có, `/terms`),
    báo cáo nội dung (có), **chặn người dùng** (Apple 1.2 cho nội dung do người dùng tạo: web chỉ có chặn trong chat;
    nên cân nhắc "ẩn bài của người này" hoặc dùng chặn chat làm cơ sở), xác nhận 18+ (có).
12. **Hồ sơ công khai, tab Học (Sổ tay, câu luyện tập), Báo cáo nội dung / Khiếu nại của Moderator, Chủ đề blog**: chưa
    có API trên web; app chờ web làm trước, không tự xây để tránh hai nơi hai luật.

## 5. Lộ trình đề xuất

| Đợt | Gồm | Vai trò |
|---|---|---|
| **1 – Lõi** | Đăng ký / đăng nhập (email, Google native, Apple), quên / đổi mật khẩu, xóa tài khoản; Trang chủ khách và thành viên; Bản đồ (xem, tìm, chi tiết, review, mẫu câu, lưu, báo đóng cửa, viết / sửa / xóa review có ảnh); Review mới; Blog và Hỏi-Đáp (xem, viết có ảnh, bình luận / trả lời, thích, lưu, báo cáo, trả lời hay nhất, xóa); Sự kiện (xem, review); Cẩm nang + checklist; FAQ; Tôi (điểm, lịch sử, khu vực / giai đoạn, xin tag bằng camera, Đã lưu); Thông báo + FCM; mẫu câu trong ngày + Nghe | Guest, User |
| **2 – Giao tiếp** | Tin nhắn đầy đủ (E2EE, ảnh / video / thoại, sửa, thu hồi, cảm xúc, chặn, báo cáo, cài đặt thông báo); Cắm ghim; sửa bài / câu hỏi / review có thêm video | User |
| **3 – Moderator lite** | Duyệt tag, duyệt ghim (duyệt / từ chối), báo đóng cửa; khi web có: báo cáo nội dung và khiếu nại với push "Khẩn" | Moderator |
| Web | Mọi màn hình quản lý còn lại của Moderator, toàn bộ AM và Admin | Mod, AM, Admin |

Chưa chốt: framework (Flutter / React Native / native). Với nhóm sinh viên không ngân sách, Flutter một mã nguồn cho
hai nền tảng là hợp lý; điểm rủi ro nhất ở bất kỳ lựa chọn nào là port mã hóa chat đúng test vector, nên để chat ở đợt 2.
