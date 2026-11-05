# Dữ liệu FAQ và cẩm nang V19

`src/main/resources/db/migration/V19__local_faq_and_guide_test_data.sql` là snapshot
MySQL local ngày 05/11/2026: 10 FAQ, 24 cẩm nang (gồm 19 checklist mẫu cũ),
26 section, 12 nhiệm vụ chuẩn bị và 2 liên kết FAQ đến cẩm nang.

Pull code rồi khởi động lại backend. Flyway tự chạy V19 một lần khi kết nối MySQL.
Chạy profile `local` để tự chép 16 ảnh đóng gói trong `dev/guide-media/` sang
`app.media.local-dir` (mặc định `uploads/`); giữ nguyên file đã có.
Commit cả file SQL, thư mục ảnh, thay đổi `DevDataSeeder` và `sample-data.sql`.

V19 dùng ID do MySQL máy đích sinh, tìm lại ID qua khóa nội dung và storage key.
Bài cùng tiêu đề/giai đoạn/chủ đề hoặc FAQ cùng câu hỏi/chủ đề đã có sẽ được giữ
nguyên, bao gồm các chỉnh sửa local. Không nhập tài khoản cá nhân, mật khẩu,
lịch sử chat hay tiến độ của người dùng. Một tài khoản không mật khẩu với email
`seed-content@local.invalid` làm tác giả nội dung nhập mới.
Liên kết địa điểm/blog của checklist cũ không được xuất vì chúng thuộc dữ liệu
khác; các liên kết FAQ đến cẩm nang được ánh xạ đầy đủ.

Đây là migration Flyway thông thường nên phần SQL áp dụng trên mọi profile,
không chỉ `local`. Không sửa V19 sau khi cả nhóm đã chạy; dữ liệu mới về sau cần
migration mới. File này là snapshot cố định, không tự cập nhật khi tạo bài mới.
