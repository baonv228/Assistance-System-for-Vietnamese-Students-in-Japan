-- V2: email verification codes for sign-up, and the first version of the legal documents.

-- 6-digit codes sent by email. A code is tied to an email, not to a user, because the account
-- does not exist yet while the person is signing up. After the code is verified, a one-time
-- "ticket" lets the browser finish the sign-up (password step) without sending the code again.
-- Only SHA-256 hashes are stored.
CREATE TABLE email_verification_codes (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY,
  email              VARCHAR(255) NOT NULL,
  purpose            VARCHAR(20)  NOT NULL,
  code_hash          CHAR(64)     NOT NULL,
  attempts           TINYINT      NOT NULL DEFAULT 0,
  expires_at         DATETIME     NOT NULL,
  verified_at        DATETIME,
  ticket_hash        CHAR(64)     UNIQUE,
  ticket_expires_at  DATETIME,
  used_at            DATETIME,
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_codes_email (email, purpose, created_at),
  CONSTRAINT ck_codes_purpose CHECK (purpose IN ('REGISTER','RESET_PASSWORD'))
) ENGINE=InnoDB;

INSERT INTO system_settings (setting_key, setting_value) VALUES
 ('email_code_minutes','10'),('email_code_attempts','5'),('email_code_resend_seconds','60'),
 ('register_ticket_minutes','30');

-- Version 1.0 of the three documents users accept at sign-up.
-- Full text is still a draft (see the legal drafts artifact); replace it with a new migration
-- that inserts version 1.1 once the team finalises the wording.
INSERT INTO legal_documents (doc_type, version, content, effective_at) VALUES
 ('TOS', '1.0', 'Điều khoản sử dụng Senpai ơi (bản nháp 1.0). ASVSJ là nền tảng phi lợi nhuận do nhóm SEP490-G130 vận hành trong khuôn khổ đồ án tốt nghiệp. Người dùng phải từ đủ 18 tuổi, cung cấp thông tin chính xác và chịu trách nhiệm về hoạt động dưới tài khoản của mình. Tài khoản mới có thể bị giới hạn số bài đăng mỗi ngày để chống spam. Người dùng có thể xóa tài khoản bất kỳ lúc nào.', '2026-10-14 00:00:00'),
 ('PRIVACY', '1.0', 'Chính sách bảo mật Senpai ơi (bản nháp 1.0). Chúng tôi lưu email, tên hiển thị và nội dung bạn đăng. Mật khẩu được mã hóa một chiều, không ai đọc được. Khu vực sinh sống chỉ lưu ở mức phường – ga, không lưu địa chỉ nhà. Ảnh minh chứng xin tag bị xóa sau khi duyệt hoặc sau 14 ngày.', '2026-10-14 00:00:00'),
 ('COMMUNITY', '1.0', 'Quy tắc cộng đồng Senpai ơi (bản nháp 1.0). Chia sẻ trung thực, tôn trọng người khác, không lừa đảo, không spam, không quảng cáo trá hình. Nội dung vi phạm có thể bị gỡ; bạn có quyền khiếu nại trong 30 ngày.', '2026-10-14 00:00:00');
