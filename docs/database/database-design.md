# ASVSJ – Thiết kế cơ sở dữ liệu (v1, theo prototype hiện tại)

- **Hệ quản trị:** MySQL 8.0 (từ 8.0.16 trở lên để ràng buộc CHECK có hiệu lực), bảng mã `utf8mb4`. File tạo bảng: [asvsj_schema.sql](asvsj_schema.sql).
- **Quy ước:**
  - Tên bảng và cột dùng `snake_case` tiếng Anh. Khóa chính `id BIGINT` tự tăng.
  - Thời gian dùng `DATETIME`, lưu theo giờ UTC, ứng dụng Java tự đổi sang giờ Nhật.
  - Trạng thái dùng `VARCHAR` + `CHECK` (dễ đọc hơn số).
  - **Xóa mềm** bằng cột `deleted_at` hoặc trạng thái `REMOVED`. Riêng nội dung người dùng tự xóa thì xóa cứng (theo quyết định của nhóm) và ghi log.
- **Ký hiệu:** PK = khóa chính, FK = khóa ngoại, UQ = duy nhất, NN = bắt buộc.
- ⚠️ Nhóm 9 (Học tiếng Nhật) có thể bị thu gọn theo góp ý của hội đồng. Các bảng đánh dấu **(có thể bỏ)**.
- Chức năng mới (việc làm thêm, hỏi đáp chuyển tới senpai, đăng nhập Facebook…) **chưa** có trong bản này, chờ chốt với giảng viên.

## Tổng quan (54 bảng, 1 view)

| Nhóm | Bảng |
|---|---|
| 1. Tài khoản | `areas`, `users`, `user_auth_providers`, `user_tokens`, `legal_documents`, `user_consents`, `user_daily_usage` |
| 2. Tag định danh | `tag_types`, `tag_proof_types`, `tag_requests`, `user_tags` |
| 3. Điểm và cấp bậc | `ranks`, `point_transactions` |
| 4. Media | `media` |
| 5. Địa điểm và bản đồ | `place_categories`, `category_google_types`, `places`, `place_history`, `place_closed_reports`, `saved_places`, `place_merges`, `duplicate_dismissals` |
| 6. Review | `reviews`, `review_media`, `review_likes` |
| 7. Sự kiện | `events` |
| 8. Blog | `blog_topics`, `blogs`, `blog_topic_links`, `blog_place_links`, `blog_media`, `blog_likes`, `saved_blogs`, `comments`, `comment_likes`, `featured_snapshots` |
| 9. Học tiếng Nhật | `learning_topics`, `phrases`, `phrase_topic_links`, `user_phrase_marks`, `practice_sentences` |
| 10. Kiểm duyệt và báo cáo | `reason_options`, `banned_keywords`, `moderation_scans`, `report_cases`, `reports`, `appeals` |
| 11. Cẩm nang | `guide_stages`, `guide_items`, `user_guide_progress` |
| 12. Thông báo và hệ thống | `notifications`, `system_settings`, `activity_logs`, `api_usage` |

---

## 1. Tài khoản

### `areas` – Khu vực (phường – ga)
Danh sách cố định cho người dùng chọn. Không bao giờ lưu địa chỉ nhà.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| ward | VARCHAR(80) | NN | Phường, quận (Arakawa) |
| station | VARCHAR(80) | NN | Ga (Nippori) |
| display_name | VARCHAR(160) | NN, UQ | "Arakawa – Nippori" |
| sort_order | INT | NN, mặc định 0 | Thứ tự hiển thị |
| is_active | BOOLEAN | NN, mặc định true | |

### `users` – Người dùng (mọi vai trò)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| email | VARCHAR(255) | NN, UQ | Email đăng nhập (nhập khi đăng ký hoặc lấy từ Google) |
| password_hash | VARCHAR(255) | | Mật khẩu mã hóa bằng **BCrypt**, không lưu mật khẩu gốc. NULL nếu chỉ đăng nhập bằng Google/Facebook |
| email_verified_at | DATETIME | | Thời điểm xác thực email qua link |
| failed_login_count | TINYINT | NN, mặc định 0 | Số lần sai mật khẩu liên tiếp |
| locked_until | DATETIME | | Khóa đăng nhập đến lúc này (sai 5 lần → khóa 30 phút) |
| display_name | VARCHAR(60) | NN | Tên hiển thị |
| avatar_url | VARCHAR(500) | | |
| role | VARCHAR(20) | NN, CHECK | `USER`, `MODERATOR`, `ACADEMIC_MANAGER`, `ADMIN` (tối đa 3 Moderator, kiểm tra ở tầng ứng dụng) |
| status | VARCHAR(20) | NN, mặc định `ACTIVE` | `ACTIVE`, `LOCKED`, `DELETED` |
| is_adult_confirmed | BOOLEAN | NN | Đã xác nhận đủ 18 tuổi |
| area_id | BIGINT | FK → areas, NULL được | Khu vực tự khai (tùy chọn) |
| stage | VARCHAR(10) | CHECK, NULL được | Giai đoạn tự khai: `PRE`, `ARR`, `LEAVE`, `HOME` |
| event_window_days | SMALLINT | NN, mặc định 30 | Hiện sự kiện sắp diễn ra trong 15 hoặc 30 ngày |
| points | INT | NN, mặc định 0 | Tổng điểm (cache, tính từ `point_transactions`) |
| false_report_count | INT | NN, mặc định 0 | Số báo cáo sai |
| report_muted_until | DATETIME | | Bị khóa quyền báo cáo đến khi nào (7 ngày) |
| created_at | DATETIME | NN | Ngày tạo, dùng để tính "tài khoản mới < 7 ngày" |
| updated_at | DATETIME | NN | |
| last_login_at | DATETIME | | |
| deleted_at | DATETIME | | Người dùng tự xóa tài khoản |

### `user_auth_providers` – Tài khoản đăng nhập liên kết
Tách riêng để sau này thêm đăng nhập Facebook mà không phải sửa bảng `users`.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| provider | VARCHAR(20) | NN | `GOOGLE` (sau này `FACEBOOK`) |
| provider_user_id | VARCHAR(255) | NN | Mã `sub` của Google |
| linked_at | DATETIME | NN | |
| | | UQ(provider, provider_user_id) | |

### `user_tokens` – Mã xác thực email và đặt lại mật khẩu
Chỉ lưu bản băm SHA-256 của mã; mã gốc chỉ nằm trong link gửi qua email.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| purpose | VARCHAR(20) | NN | `VERIFY_EMAIL`, `RESET_PASSWORD` |
| token_hash | CHAR(64) | NN, UQ | SHA-256 của mã trong link |
| expires_at | DATETIME | NN | 24 giờ (xác thực email), 30 phút (đặt lại mật khẩu) |
| used_at | DATETIME | | Đã dùng thì không dùng lại được |
| created_at | DATETIME | NN | |

### `legal_documents` – Điều khoản, Chính sách bảo mật, Quy tắc cộng đồng
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| doc_type | VARCHAR(20) | NN | `TOS`, `PRIVACY`, `COMMUNITY` |
| version | VARCHAR(20) | NN | "1.0" |
| content | MEDIUMTEXT | NN | |
| effective_at | DATETIME | NN | Ngày có hiệu lực |
| | | UQ(doc_type, version) | |

### `user_consents` – Lịch sử đồng ý điều khoản
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| document_id | BIGINT | FK → legal_documents, NN | Phiên bản đã đồng ý |
| accepted_at | DATETIME | NN | Thời điểm đồng ý |

### `user_daily_usage` – Đếm lượt đăng trong ngày (giới hạn tài khoản mới)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| user_id | BIGINT | PK, FK → users | |
| usage_date | DATE | PK | Ngày (giờ Nhật) |
| posts | SMALLINT | NN, mặc định 0 | Bài viết + review (tối đa 3) |
| pins | SMALLINT | NN, mặc định 0 | Ghim mới (tối đa 2) |
| videos | SMALLINT | NN, mặc định 0 | Video (tối đa 1) |

---

## 2. Tag định danh

### `tag_types` – Danh mục tag (Moderator quản lý)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| name | VARCHAR(60) | NN, UQ | Kouhai, Mentor, Người Nhật… |
| description | VARCHAR(255) | NN | Ai được xin tag này |
| color | VARCHAR(20) | NN | Mã màu hiển thị |
| selfie_only | BOOLEAN | NN, mặc định false | Chỉ cần ảnh chân dung (Người Nhật) |
| is_active | BOOLEAN | NN, mặc định true | Ngừng cấp mới |
| created_by | BIGINT | FK → users | |
| created_at | DATETIME | NN | |

### `tag_proof_types` – Loại giấy tờ chấp nhận cho từng tag
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| tag_type_id | BIGINT | FK → tag_types, NN | |
| name | VARCHAR(120) | NN | "Thẻ sinh viên (学生証)" |

### `tag_requests` – Yêu cầu cấp tag
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| tag_type_id | BIGINT | FK → tag_types, NN | |
| proof_type_id | BIGINT | FK → tag_proof_types, NN | |
| proof_media_id | BIGINT | FK → media | Ảnh minh chứng (xóa sau khi duyệt hoặc sau 14 ngày) |
| status | VARCHAR(20) | NN | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED` |
| reject_reason | VARCHAR(255) | | |
| submitted_at | DATETIME | NN | |
| expires_at | DATETIME | NN | submitted_at + 14 ngày |
| reviewed_by | BIGINT | FK → users | Moderator duyệt |
| reviewed_at | DATETIME | | |

### `user_tags` – Tag người dùng đang có
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| user_id | BIGINT | PK, FK → users | |
| tag_type_id | BIGINT | PK, FK → tag_types | |
| request_id | BIGINT | FK → tag_requests, NULL được | NULL khi Moderator cấp trực tiếp |
| granted_by | BIGINT | FK → users, NN | |
| granted_at | DATETIME | NN | |
| revoked_at | DATETIME | | Thu hồi |

---

## 3. Điểm và cấp bậc

### `ranks` – Cấp bậc
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| name | VARCHAR(60) | NN, UQ | Người mới, Người chỉ đường, Senpai tin cậy |
| min_points | INT | NN, UQ | Điểm tối thiểu |
| featured_bonus | INT | NN, mặc định 0 | Điểm ưu tiên khi xếp hạng Bài nổi bật (+5, +10) |

### `point_transactions` – Lịch sử cộng/trừ điểm
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| delta | INT | NN | +10, -20… |
| reason | VARCHAR(40) | NN | `REVIEW_CREATED`, `BLOG_CREATED`, `BEST_ANSWER`, `PRACTICE_CORRECT`, `CONTENT_REMOVED`, `CONTENT_DELETED`… |
| ref_type | VARCHAR(20) | | `REVIEW`, `BLOG`, `COMMENT`, `PRACTICE` |
| ref_id | BIGINT | | |
| created_at | DATETIME | NN | |

---

## 4. Media

### `media` – Ảnh và video (lưu trên dịch vụ lưu trữ, DB chỉ giữ đường dẫn)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| owner_id | BIGINT | FK → users, NN | Người tải lên |
| media_type | VARCHAR(10) | NN | `IMAGE`, `VIDEO` |
| storage_key | VARCHAR(500) | NN, UQ | Khóa file trên kho lưu trữ |
| mime_type | VARCHAR(50) | NN | image/jpeg, image/png, image/heic, video/mp4 |
| size_bytes | BIGINT | NN | ≤ 20 MB với ảnh |
| duration_sec | SMALLINT | | Video ≤ 30 giây |
| width, height | INT | | |
| scan_status | VARCHAR(20) | NN | `PROCESSING`, `PASSED`, `REJECTED` |
| created_at | DATETIME | NN | |
| deleted_at | DATETIME | | |

---

## 5. Địa điểm và bản đồ

### `place_categories` – Danh mục địa điểm
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | VARCHAR(20) | PK | `konbini`, `food`, `station`… |
| name_vi | VARCHAR(80) | NN | "Cửa hàng tiện lợi" |
| icon | VARCHAR(50) | NN | Tên icon |
| sort_order | INT | NN | |

### `category_google_types` – Ánh xạ loại của Google sang danh mục
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| google_type | VARCHAR(50) | PK | `convenience_store`, `restaurant`… |
| category_id | VARCHAR(20) | FK → place_categories, NN | |

### `places` – Địa điểm
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| google_place_id | VARCHAR(255) | UQ, NULL được | Chỉ lưu `place_id` (không lưu dữ liệu Google lâu dài) |
| name_vi | VARCHAR(160) | NN | Tên hiển thị tiếng Việt |
| name_ja | VARCHAR(160) | | Tên tiếng Nhật |
| name_en | VARCHAR(160) | | Tên tiếng Anh |
| category_id | VARCHAR(20) | FK → place_categories, NN | |
| area_id | BIGINT | FK → areas | |
| address | VARCHAR(255) | | |
| lat, lng | DECIMAL(9,6) | | Tọa độ (ghim thủ công bắt buộc có) |
| source | VARCHAR(20) | NN | `GOOGLE`, `TEAM`, `USER`, `MODERATOR` |
| status | VARCHAR(20) | NN | `PENDING`, `ACTIVE`, `REJECTED`, `REMOVED`, `MERGED` |
| is_closed | BOOLEAN | NN, mặc định false | Moderator xác nhận đã đóng cửa |
| closed_at | DATETIME | | |
| reject_reason | VARCHAR(255) | | Lý do từ chối ghim |
| removed_reason | VARCHAR(255) | | Lý do gỡ khỏi bản đồ |
| merged_into_id | BIGINT | FK → places | Địa điểm đích khi bị gộp |
| created_by | BIGINT | FK → users, NN | |
| reviewed_by | BIGINT | FK → users | Moderator duyệt, từ chối hoặc gỡ |
| reviewed_at | DATETIME | | |
| created_at, updated_at | DATETIME | NN | |

### `place_history` – Lịch sử thay đổi địa điểm (tab "Lịch sử")
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| place_id | BIGINT | FK → places, NN | |
| action | VARCHAR(30) | NN | `CREATED`, `APPROVED`, `REJECTED`, `EDITED`, `CLOSED`, `REOPENED`, `REMOVED`, `RESTORED`, `MERGED` |
| actor_id | BIGINT | FK → users, NN | |
| detail | JSON | | Giá trị cũ và mới, lý do |
| created_at | DATETIME | NN | |

### `place_closed_reports` – Báo cáo "đã đóng cửa"
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| place_id | BIGINT | PK, FK → places | |
| user_id | BIGINT | PK, FK → users | Mỗi người báo cáo một lần |
| created_at | DATETIME | NN | Đủ 5 báo cáo thì vào hàng chờ của Moderator |
| resolved_at | DATETIME | | Moderator đã xử lý |

### `saved_places` – Địa điểm đã lưu
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| user_id | BIGINT | PK, FK → users | |
| place_id | BIGINT | PK, FK → places | |
| saved_at | DATETIME | NN | |

### `place_merges` – Lịch sử gộp địa điểm trùng (không hoàn tác)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| kept_place_id | BIGINT | FK → places, NN | Địa điểm giữ lại |
| merged_place_id | BIGINT | FK → places, NN, UQ | Địa điểm bị gộp |
| field_choices | JSON | NN | Trường nào lấy từ bên nào |
| moved_reviews | INT | NN | Số review đã chuyển |
| merged_by | BIGINT | FK → users, NN | |
| merged_at | DATETIME | NN | |

### `duplicate_dismissals` – Cặp "không phải trùng lặp"
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| place_a_id | BIGINT | PK, FK → places | place_a_id < place_b_id |
| place_b_id | BIGINT | PK, FK → places | |
| dismissed_by | BIGINT | FK → users, NN | |
| dismissed_at | DATETIME | NN | |

---

## 6. Review

### `reviews` – Review địa điểm hoặc sự kiện
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| author_id | BIGINT | FK → users, NN | |
| place_id | BIGINT | FK → places | Một trong hai: place_id hoặc event_id |
| event_id | BIGINT | FK → events | |
| event_year | SMALLINT | | Năm tổ chức (review sự kiện) |
| rating | SMALLINT | NN, CHECK 1–5 | Số sao |
| content | VARCHAR(2000) | NN | |
| status | VARCHAR(20) | NN | `PROCESSING`, `PUBLISHED`, `REJECTED`, `HIDDEN`, `REMOVED` |
| is_edited | BOOLEAN | NN, mặc định false | Hiện "đã sửa" |
| removed_reason | VARCHAR(255) | | |
| like_count | INT | NN, mặc định 0 | > 10 → "Hữu ích" |
| created_at, updated_at | DATETIME | NN | |
| | | CHECK (place_id hoặc event_id, đúng một) | |
| removed_at, purge_at | DATETIME | | Thời điểm gỡ; purge_at = removed_at + 30 ngày, giữ để khiếu nại rồi xóa hẳn (áp dụng cả cho `blogs`, `comments`, `practice_sentences`) |
| | | UQ(author_id, place_id), UQ(author_id, event_id, event_year) | Mỗi người một review cho một địa điểm, một review cho mỗi năm của sự kiện |

### `review_media` – Ảnh và video của review (tối đa 5 ảnh, 1 video)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| review_id | BIGINT | PK, FK → reviews | |
| media_id | BIGINT | PK, FK → media | |
| sort_order | SMALLINT | NN | |

### `review_likes` – Lượt thích review
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| review_id | BIGINT | PK, FK → reviews | |
| user_id | BIGINT | PK, FK → users | |
| created_at | DATETIME | NN | |

---

## 7. Sự kiện

### `events` – Lễ hội, sự kiện (Moderator đăng)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| title | VARCHAR(160) | NN | |
| cover_media_id | BIGINT | FK → media | Ảnh bìa |
| start_date | DATE | NN | |
| end_date | DATE | NN, CHECK ≥ start_date | |
| location_name | VARCHAR(160) | NN | "Công viên Yanaka" |
| lat, lng | DECIMAL(9,6) | | NULL = chỉ hiện trong danh sách, không có ghim |
| cost_note | VARCHAR(120) | | "Miễn phí" |
| tip | VARCHAR(500) | | Mẹo từ Moderator |
| created_by | BIGINT | FK → users, NN | |
| created_at, updated_at | DATETIME | NN | |
| deleted_at | DATETIME | | Xóa sự kiện (review đi kèm cũng bị gỡ) |

> Album sự kiện = ảnh của các review sự kiện (`reviews.event_id` + `review_media`), không cần bảng riêng.

---

## 8. Blog

### `blog_topics` – Chủ đề bài viết (Moderator quản lý)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| name | VARCHAR(60) | NN, UQ | Kinh nghiệm, Cảnh báo lừa đảo… |
| is_other | BOOLEAN | NN, mặc định false | "Khác", luôn đứng cuối |
| sort_order | INT | NN | |
| is_active | BOOLEAN | NN, mặc định true | |

### `blogs` – Bài viết và câu hỏi
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| author_id | BIGINT | FK → users, NN | |
| blog_type | VARCHAR(10) | NN | `POST`, `QA` |
| title | VARCHAR(200) | NN | |
| content | MEDIUMTEXT | NN | |
| status | VARCHAR(20) | NN | `PROCESSING`, `PUBLISHED`, `REJECTED`, `HIDDEN`, `REMOVED` |
| is_pinned | BOOLEAN | NN, mặc định false | Moderator ghim lên đầu |
| best_comment_id | BIGINT | FK → comments | Câu trả lời hay nhất (Q&A, +5 điểm) |
| is_edited | BOOLEAN | NN, mặc định false | |
| removed_reason | VARCHAR(255) | | |
| like_count | INT | NN, mặc định 0 | Cache |
| comment_count | INT | NN, mặc định 0 | Cache |
| created_at, updated_at | DATETIME | NN | |

### `blog_topic_links` – Bài viết ↔ chủ đề (1–3 chủ đề)
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| blog_id | BIGINT | PK, FK → blogs |
| topic_id | BIGINT | PK, FK → blog_topics |

### `blog_place_links` – Bài viết ↔ địa điểm (tối đa 5)
Bài "Cảnh báo lừa đảo" gắn địa điểm thì ghim đó hiện dấu "!".

| Cột | Kiểu | Ràng buộc |
|---|---|---|
| blog_id | BIGINT | PK, FK → blogs |
| place_id | BIGINT | PK, FK → places |

### `blog_media` – Ảnh và video của bài viết
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| blog_id | BIGINT | PK, FK → blogs |
| media_id | BIGINT | PK, FK → media |
| sort_order | SMALLINT | NN |

### `blog_likes`, `saved_blogs` – Thích và lưu bài viết
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| blog_id | BIGINT | PK, FK → blogs |
| user_id | BIGINT | PK, FK → users |
| created_at | DATETIME | NN |

### `comments` – Bình luận, câu trả lời và trả lời bình luận
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| blog_id | BIGINT | FK → blogs, NN | |
| author_id | BIGINT | FK → users, NN | |
| parent_id | BIGINT | FK → comments | Khác NULL = trả lời một bình luận |
| content | VARCHAR(2000) | NN | |
| status | VARCHAR(20) | NN | `PROCESSING`, `PUBLISHED`, `REJECTED`, `HIDDEN`, `REMOVED` |
| is_edited | BOOLEAN | NN, mặc định false | |
| like_count | INT | NN, mặc định 0 | |
| created_at, updated_at | DATETIME | NN | |

### `comment_likes` – Thích bình luận
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| comment_id | BIGINT | PK, FK → comments |
| user_id | BIGINT | PK, FK → users |
| created_at | DATETIME | NN |

### `featured_snapshots` – Kết quả xếp hạng Bài nổi bật (hệ thống tự tính)
Điểm = lượt thích + 2 × bình luận + điểm ưu tiên theo cấp bậc. Tối thiểu 10, mỗi tác giả một bài, lấy top 3.

| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| period | VARCHAR(10) | NN | `WEEK`, `MONTH` |
| computed_at | DATETIME | NN | Lần tính |
| blog_id | BIGINT | FK → blogs, NN | |
| rank_no | SMALLINT | NN, CHECK 1–3 | |
| score | INT | NN | |

---

## 9. Học tiếng Nhật (có thể bỏ hoặc thu gọn)

### `learning_topics` – Chủ đề học
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | VARCHAR(20) | PK | `pay`, `order`, `train`… |
| name | VARCHAR(60) | NN | Thanh toán, Gọi món… |
| icon | VARCHAR(50) | NN | |

### `phrases` – Mẫu câu và từ vựng (gắn với danh mục địa điểm)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| category_id | VARCHAR(20) | FK → place_categories, NN | |
| kind | VARCHAR(10) | NN | `PHRASE`, `VOCAB` |
| japanese | VARCHAR(200) | NN | 袋はいりません。 |
| romaji | VARCHAR(200) | NN | |
| meaning_vi | VARCHAR(300) | NN | |
| created_by | BIGINT | FK → users, NN | Người quản lý học thuật |
| created_at, updated_at | DATETIME | NN | |
| deleted_at | DATETIME | | |

### `phrase_topic_links` – Mẫu câu ↔ chủ đề học
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| phrase_id | BIGINT | PK, FK → phrases |
| topic_id | VARCHAR(20) | PK, FK → learning_topics |

### `user_phrase_marks` – Đã học / Sổ tay của người dùng
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| user_id | BIGINT | PK, FK → users | |
| phrase_id | BIGINT | PK, FK → phrases | |
| is_learned | BOOLEAN | NN, mặc định false | |
| in_notebook | BOOLEAN | NN, mặc định false | |
| updated_at | DATETIME | NN | |

### `practice_sentences` – Câu luyện tập của người dùng
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| phrase_id | BIGINT | FK → phrases, NN | |
| author_id | BIGINT | FK → users, NN | |
| content | VARCHAR(500) | NN | |
| status | VARCHAR(20) | NN | `PUBLISHED`, `HIDDEN`, `REMOVED` |
| is_correct | BOOLEAN | NN, mặc định false | "Câu đúng" (+3 điểm) |
| correction | VARCHAR(500) | | Góp ý sửa |
| reviewed_by | BIGINT | FK → users | Người quản lý học thuật |
| reviewed_at | DATETIME | | |
| created_at, updated_at | DATETIME | NN | |

---

## 10. Kiểm duyệt và báo cáo

### `reason_options` – Danh sách lý do có sẵn
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| scope | VARCHAR(20) | NN | `REPORT` (Lừa đảo, Sai sự thật…), `ACADEMIC_REPORT` (Sai tiếng Nhật…), `PIN_REJECT`, `PLACE_REMOVE` |
| label | VARCHAR(120) | NN | |
| sort_order | INT | NN | |
| is_active | BOOLEAN | NN, mặc định true | |

### `banned_keywords` – Từ khóa cấm (Admin cấu hình)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| keyword | VARCHAR(100) | NN, UQ | "visa nhanh", "việc nhẹ lương cao" |
| created_by | BIGINT | FK → users | NULL với từ khóa có sẵn khi khởi tạo |
| created_at | DATETIME | NN | |

### `moderation_scans` – Kết quả kiểm duyệt tự động
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| target_type | VARCHAR(20) | NN | `REVIEW`, `BLOG`, `COMMENT`, `PRACTICE`, `MEDIA` |
| target_id | BIGINT | NN | |
| engine | VARCHAR(20) | NN | `KEYWORD`, `OPENAI` |
| result | VARCHAR(10) | NN | `PASS`, `FLAG`, `ERROR` |
| detail | JSON | | Từ khóa trúng, nhãn vi phạm, điểm |
| created_at | DATETIME | NN | |

### `report_cases` – Hồ sơ báo cáo (một nội dung = một hồ sơ, gom nhiều lượt báo cáo)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| target_type | VARCHAR(20) | NN | `REVIEW`, `BLOG`, `COMMENT`, `PHRASE`, `PRACTICE` |
| target_id | BIGINT | NN | |
| handler_role | VARCHAR(20) | NN | `MODERATOR` hoặc `ACADEMIC_MANAGER` |
| report_count | INT | NN, mặc định 0 | |
| priority | VARCHAR(10) | NN | `NORMAL` (1–5), `URGENT` (> 5, nội dung tự ẩn) |
| status | VARCHAR(20) | NN | `OPEN`, `RESOLVED` |
| decision | VARCHAR(20) | | `KEEP`, `REMOVE`, `CORRECTED` |
| decision_note | VARCHAR(500) | | |
| handled_by | BIGINT | FK → users | |
| handled_at | DATETIME | | |
| created_at | DATETIME | NN | |
| open_flag | TINYINT | cột tự sinh | = 1 khi `OPEN`, NULL khi đã xử lý. MySQL không có partial index nên dùng UQ(target_type, target_id, open_flag) để mỗi nội dung chỉ có một hồ sơ đang mở |

### `reports` – Từng lượt báo cáo
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| case_id | BIGINT | FK → report_cases, NN | |
| reporter_id | BIGINT | FK → users, NN | |
| reason_id | BIGINT | FK → reason_options, NN | |
| note | VARCHAR(500) | | |
| is_false | BOOLEAN | | Moderator kết luận báo cáo sai → tăng `false_report_count` |
| created_at | DATETIME | NN | |
| | | UQ(case_id, reporter_id) | |

### `appeals` – Khiếu nại nội dung bị gỡ
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | |
| target_type | VARCHAR(20) | NN | `REVIEW`, `BLOG`, `COMMENT`, `PRACTICE` |
| target_id | BIGINT | NN | |
| content | VARCHAR(1000) | NN | Lý do khiếu nại |
| status | VARCHAR(20) | NN | `PENDING`, `ACCEPTED`, `REJECTED` |
| decided_by | BIGINT | FK → users | |
| decided_at | DATETIME | | |
| decision_note | VARCHAR(500) | | |
| created_at | DATETIME | NN | |
| | | UQ(target_type, target_id) | Mỗi nội dung bị gỡ chỉ được khiếu nại một lần |

---

## 11. Cẩm nang

### `guide_stages` – Giai đoạn
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| code | VARCHAR(10) | PK | `PRE`, `ARR`, `LEAVE`, `HOME` |
| title | VARCHAR(120) | NN | "Khi mới đến (tuần đầu đến 3 tháng)" |
| icon | VARCHAR(50) | NN | |
| sort_order | INT | NN | |

### `guide_items` – Mục trong cẩm nang
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| stage_code | VARCHAR(10) | FK → guide_stages, NN | |
| title | VARCHAR(255) | NN | |
| due_note | VARCHAR(80) | | "Trong 14 ngày" |
| place_id | BIGINT | FK → places | Địa điểm liên quan |
| blog_id | BIGINT | FK → blogs | Bài viết liên quan |
| sort_order | INT | NN | |
| created_by, updated_by | BIGINT | FK → users | |
| created_at, updated_at | DATETIME | NN | |

### `user_guide_progress` – Người dùng đánh dấu đã làm
| Cột | Kiểu | Ràng buộc |
|---|---|---|
| user_id | BIGINT | PK, FK → users |
| item_id | BIGINT | PK, FK → guide_items |
| done_at | DATETIME | NN |

---

## 12. Thông báo và hệ thống

### `notifications` – Thông báo trong app
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → users, NN | Người nhận |
| type | VARCHAR(30) | NN | `CONTENT_REMOVED`, `TAG_APPROVED`, `PIN_REJECTED`, `NEW_COMMENT`, `BEST_ANSWER`, `APPEAL_RESULT`… |
| content | VARCHAR(500) | NN | |
| ref_type | VARCHAR(20) | | |
| ref_id | BIGINT | | Bấm vào để mở / khiếu nại |
| is_read | BOOLEAN | NN, mặc định false | |
| created_at | DATETIME | NN | |

### `system_settings` – Cấu hình hệ thống (Admin chỉnh)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| setting_key | VARCHAR(50) | PK | `report_hide_threshold`=5, `false_report_max`=5, `mute_days`=7, `proof_keep_days`=14, `new_account_days`=7, `posts_per_day`=3, `pins_per_day`=2, `videos_per_day`=1, `closed_report_threshold`=5, `removed_keep_days`=30, `log_keep_days`=90, `max_moderators`=3, `password_min_length`=8, `lock_attempts`=5, `lock_minutes`=30, `verify_token_hours`=24, `reset_token_minutes`=30, `featured_top_n`=3, `featured_min_score`=10, `duplicate_warn_radius_m`=200, `merge_suggest_radius_m`=50 |
| setting_value | VARCHAR(255) | NN | (`key` và `value` là từ khóa của MySQL nên phải đổi tên) |
| updated_by | BIGINT | FK → users | |
| updated_at | DATETIME | NN | |

### `activity_logs` – Nhật ký hệ thống
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| id | BIGINT | PK | |
| actor_id | BIGINT | FK → users | NULL = hệ thống tự làm |
| action | VARCHAR(50) | NN | `REVIEW_REMOVED`, `PIN_APPROVED`, `PLACE_MERGED`, `ROLE_CHANGED`… |
| target_type | VARCHAR(20) | | |
| target_id | BIGINT | | |
| old_value, new_value | JSON | | Trạng thái trước và sau khi thay đổi |
| description | VARCHAR(1000) | | Mô tả dễ đọc |
| created_at | DATETIME | NN | |

### `api_usage` – Lượt dùng dịch vụ ngoài (hạn mức trên dashboard Admin)
| Cột | Kiểu | Ràng buộc | Mô tả |
|---|---|---|---|
| service | VARCHAR(40) | PK | `GOOGLE_MAPS_LOAD`, `GOOGLE_PLACE_DETAILS`, `OPENAI_MODERATION`, `STORAGE_GB` |
| usage_date | DATE | PK | |
| amount | DECIMAL(12,2) | NN | |

---

## Phần mềm đề xuất (MySQL)

| Việc | Công cụ | Ghi chú |
|---|---|---|
| Hệ quản trị CSDL | **MySQL 8.0** (Community, miễn phí) | Tạo database với `utf8mb4` để lưu tiếng Nhật, tiếng Việt và emoji. Bảng mã `utf8mb4_0900_ai_ci` không phân biệt dấu khi tìm kiếm, nên gõ "pho" cũng tìm thấy "Phở". Tính khoảng cách (cảnh báo ghim trùng trong 200 m, gợi ý gộp trong 50 m) bằng `ST_Distance_Sphere(POINT(lng, lat), POINT(lng2, lat2))`. |
| Quản lý DB (giao diện) | **MySQL Workbench** | Miễn phí, của chính MySQL. Có Reverse Engineer để vẽ ERD từ DB, xuất ảnh ERD cho báo cáo. Thay thế: **DBeaver Community** (giao diện gọn hơn, dùng được cho nhiều loại DB). |
| Vẽ ERD cho báo cáo | MySQL Workbench, **dbdiagram.io** hoặc Visual Paradigm (nhóm đang dùng) | dbdiagram.io nhập thẳng được file SQL này. |
| Quản lý phiên bản schema | **Flyway** | Tích hợp sẵn với Spring Boot. Mỗi lần đổi bảng là một file `V2__...sql`, cả nhóm luôn có cùng một DB. |
| Truy cập DB từ Java | **Spring Boot + Spring Data JPA (Hibernate)** + driver `mysql-connector-j` | |
| Chạy DB ở máy cá nhân | **Docker** (image `mysql:8.0`) hoặc MySQL Installer | Docker giúp cả nhóm dùng chung một cấu hình. |

### Lưu ý riêng khi dùng MySQL
- **Khóa ngoại phải viết ở cuối bảng** (`CONSTRAINT ... FOREIGN KEY ... REFERENCES ...`). MySQL 8.0 bỏ qua `REFERENCES` viết ngay sau cột mà không báo lỗi. File SQL đã viết theo cách đúng.
- **CHECK chỉ có hiệu lực từ MySQL 8.0.16.** Bản cũ hơn bỏ qua CHECK, nên phải kiểm tra lại ở tầng Java.
- **Cột có CHECK thì không được có `ON DELETE CASCADE`** (quy định của MySQL). Vì vậy `duplicate_dismissals` không tự xóa theo địa điểm. Địa điểm chỉ gỡ mềm nên không ảnh hưởng.
- **`key`, `value` là từ khóa** của MySQL nên bảng `system_settings` dùng `setting_key`, `setting_value`.

## View

### `v_place_rating` – Điểm trung bình của từng địa điểm
Tính từ các review `PUBLISHED`: `place_id`, `review_count`, `avg_rating` (làm tròn 1 chữ số).

## Đăng nhập bằng email và mật khẩu
- **Mã hóa mật khẩu:** dùng `BCryptPasswordEncoder` của Spring Security. BCrypt tự thêm salt và chậm có chủ đích, nên dù lộ database cũng không đọc ngược ra mật khẩu. Không bao giờ lưu, ghi log hay gửi email mật khẩu gốc.
- **Đăng ký bằng email:** tạo `users` với `password_hash`, gửi link xác thực (`user_tokens`, `VERIFY_EMAIL`). Chưa xác thực thì chưa được đăng bài.
- **Đăng nhập bằng Google:** tạo `users` với `password_hash = NULL`, `email_verified_at` = lúc đăng ký, và một dòng `user_auth_providers`. Nếu email trùng một tài khoản đã có thì liên kết vào tài khoản đó.
- **Quên mật khẩu:** tạo token `RESET_PASSWORD` hạn 30 phút; đặt mật khẩu mới xong thì đánh dấu `used_at` và xóa các token còn lại.
- **Sai mật khẩu:** tăng `failed_login_count`; đủ 5 lần thì đặt `locked_until` = bây giờ + 30 phút. Đăng nhập đúng thì về 0.
