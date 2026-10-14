# Hướng dẫn cấu trúc code dự án

## 0. Cài đặt và chạy trên máy

Cần có: **JDK 17**, **MySQL 8.0.16 trở lên** (MySQL Installer kèm MySQL Workbench), IntelliJ hoặc Maven.

1. Cài MySQL Server 8 và nhớ mật khẩu tài khoản `root`.
2. Trong `src/main/resources/`, chép `application-local.properties.example` thành `application-local.properties`
   rồi điền mật khẩu MySQL của máy mình. File này đã nằm trong `.gitignore`, **không commit**.
3. Chạy ứng dụng (`VietnameseStudentAssistanceApplication` trong IntelliJ, hoặc `mvn spring-boot:run`).
   - Lần đầu chạy, database `asvsj` được tạo tự động và **Flyway** tạo đủ các bảng.
   - Mở MySQL Workbench, kết nối `localhost:3306`, sẽ thấy schema `asvsj`.

### Tài khoản mẫu (chỉ trên máy dev)
Khi chạy với profile `local` (mặc định), lần đầu khởi động app tự tạo các tài khoản sau, mật khẩu đều là `123456`:

| Email | Vai trò | Sau khi đăng nhập |
|---|---|---|
| `user@gmail.com` | Người dùng (Trần Minh, mới đến Nippori) | Trang chủ `/` |
| `moderator@gmail.com` | Moderator | `/pages/moderator/index.html` |
| `academic@gmail.com` | Người quản lý học thuật | `/pages/academic/index.html` |
| `admin@gmail.com` | Admin | `/pages/admin/index.html` |
| `mentor@gmail.com`, `kouhai@gmail.com` | Người dùng viết review, bài viết mẫu | Trang chủ `/` |

Cùng lúc đó app nạp dữ liệu mẫu từ prototype (địa điểm, review, sự kiện, blog, cẩm nang, mẫu câu) trong
`src/main/resources/dev/sample-data.sql`, chỉ khi bảng `places` còn trống.
**Không bao giờ bật profile `local` trên server deploy**: khi deploy, đặt `SPRING_PROFILES_ACTIVE=prod`.

### Thay đổi database (quan trọng)
- Mọi bảng được tạo bởi các file **Flyway migration** trong `src/main/resources/db/migration/`.
  Hibernate **không** tự tạo hay sửa bảng (`ddl-auto=none`).
- Muốn đổi database, tạo file mới với số tiếp theo, ví dụ `V2__add_avatar_to_users.sql`.
  **Không sửa file migration đã push**: Flyway sẽ báo lỗi checksum trên máy những người đã chạy file đó.
- Pull code của người khác về rồi chạy app, Flyway tự chạy các file còn thiếu. Không cần nhắn lệnh SQL cho nhau.
- Nếu hai người cùng tạo `V2__...`, người merge sau đổi số file của mình thành `V3__...`.
- Database trên máy chỉ để code thử. Bản deploy dùng một MySQL chung trên cloud, cấu hình qua biến môi
  trường `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

Thiết kế từng bảng: `docs/database/database-design.md`.

## 1. Cấu trúc Backend

Mã nguồn Java nằm trong `src/main/java/com/sep490/vsa/`.

### `common/` — Thành phần dùng chung
Chứa code được nhiều chức năng sử dụng.

- `config/`: cấu hình ứng dụng. Hiện có `CorsConfig.java`, cấu hình những frontend origin được phép gọi API.
- `dto/`: dữ liệu dùng chung khi trao đổi API. Hiện có `ApiResponse.java`, định nghĩa định dạng response chung nếu các API sử dụng nó.
- `exception/`: xử lý lỗi chung. Hiện có `ApiExceptionHandler.java`, tập trung chuyển exception thành HTTP response.
- `seed/`: tạo dữ liệu ban đầu hoặc dữ liệu mẫu. Hiện có `DataSeeder.java`.

Chỉ đặt code dùng chung tại `common/`. Code phục vụ riêng một chức năng phải đặt trong feature tương ứng.

### `feature/` — Các chức năng nghiệp vụ
Mỗi thư mục con là một chức năng của website.

- `auth/`: đăng ký, đăng nhập, đăng xuất và xác thực tài khoản. Chứa Controller, Service, DTO hoặc các class cần cho luồng xác thực.
- `user/`: thông tin tài khoản và hồ sơ người dùng. Chứa `User.java` (mô hình dữ liệu người dùng) và `UserRepository.java` (truy cập dữ liệu người dùng), cùng các class khác khi được phát triển.
- `guide/`: chức năng xem, tìm kiếm và quản lý nội dung hướng dẫn. Chứa các class xử lý API, nghiệp vụ, truy cập dữ liệu và DTO của hướng dẫn.
  - `Guide.java`: mô hình dữ liệu hướng dẫn, thường là Entity nếu được ánh xạ với database bằng JPA.
  - `GuideController.java`: nhận request HTTP liên quan đến hướng dẫn và trả response.
  - `GuideService.java`: xử lý nghiệp vụ guide.
  - `GuideRepository.java`: truy cập dữ liệu guide.
  - `GuideResponse.java`: định nghĩa dữ liệu hướng dẫn trả về qua API.

### `VietnameseStudentAssistanceApplication.java`
Class khởi động Spring Boot. Không đặt nghiệp vụ riêng của feature vào class này.

## 2. Cấu trúc Frontend

Frontend nằm trong `src/main/resources/static/`. Spring Boot có thể phục vụ các file tĩnh tại đây.

- `index.html`: trang vào chính của website.
- `assets/`: hình ảnh, icon, font và tài nguyên tĩnh.
- `components/`: các phần giao diện dùng lại, ví dụ navbar hoặc footer. Với HTML thuần, cần có cơ chế nạp các file component; chúng không tự được ghép vào trang.
- `pages/`: các trang HTML theo chức năng, ví dụ trang đăng nhập, hướng dẫn, học bổng và hồ sơ người dùng.

Khi dự án có thêm CSS và JavaScript, nên tạo:
- `css/`: định dạng giao diện.
- `js/api/`: các hàm gọi REST API.
- `js/pages/`: xử lý sự kiện và hiển thị dữ liệu cho từng trang.

## 3. Luồng hoạt động của code

Ví dụ người dùng mở danh sách học bổng:

1. Người dùng mở trang HTML trong `static/pages/`.
2. JavaScript của trang gọi API, ví dụ `GET /api/scholarships`.
3. `ScholarshipController` nhận request.
4. Controller gọi `ScholarshipService`.
5. Service xử lý nghiệp vụ và gọi `ScholarshipRepository` để lấy dữ liệu.
6. Repository truy vấn database và trả dữ liệu về Service.
7. Service trả kết quả cho Controller.
8. Controller trả JSON (có thể dùng `ScholarshipResponse` và/hoặc `ApiResponse`, tùy code thực tế).
9. JavaScript nhận JSON và hiển thị danh sách lên trang HTML.

## 4. Quy tắc để cả nhóm làm thống nhất

1. Chức năng nào thì đặt code trong feature đó: hướng dẫn ở `guide/`, tài khoản ở `user/`, xác thực ở `auth/`.
2. Controller nhận request và trả response; Service xử lý nghiệp vụ; Repository truy cập database; Entity mô tả dữ liệu lưu trữ; DTO mô tả dữ liệu API.
3. Không truy vấn database trực tiếp trong Controller.
4. Không đặt class riêng của một feature vào `common/`.
5. Khi sửa API hoặc cấu trúc JSON, báo cho thành viên phụ trách Frontend và các module liên quan.
6. Tên file và package cần thống nhất trong nhóm; không tạo thêm class trùng chức năng nếu chưa kiểm tra code hiện có.


