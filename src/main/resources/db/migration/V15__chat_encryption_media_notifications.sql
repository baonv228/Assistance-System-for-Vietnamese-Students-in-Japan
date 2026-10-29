-- V15: "Tin nhắn" phase 2 (feature/chat): end-to-end encryption once a request is accepted, photos, videos and voice
-- messages in encrypted conversations, edit, recall, reactions, quoted replies, and new-message notifications (e-mail
-- and Web Push).
--
-- Encryption: each member has one identity key pair (ECDH P-256) made in their browser. The server keeps the public
-- key and the private key wrapped with a key derived from the member's "mật khẩu khôi phục" (PBKDF2), so it can never
-- read it. A message of an encrypted conversation is stored as AES-GCM ciphertext; the server only sees who wrote to
-- whom and when. Requests (first messages to someone) stay plain text and filtered as before.

-- Identity keys. The current key of a member is their newest row without revoked_at; older keys stay so messages
-- written with them can still be read.
CREATE TABLE chat_keys (
  id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
  user_id              BIGINT        NOT NULL,
  public_key           VARCHAR(120)  NOT NULL,   -- base64 of the raw uncompressed P-256 point (65 bytes)
  wrapped_private_key  VARCHAR(1000) NOT NULL,   -- base64 AES-GCM(PKCS8 private key), key = PBKDF2(passphrase)
  wrap_salt            VARCHAR(64)   NOT NULL,   -- base64
  wrap_iv              VARCHAR(32)   NOT NULL,   -- base64, 12 bytes
  wrap_iterations      INT           NOT NULL,
  created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at           DATETIME,
  INDEX idx_chat_keys_user (user_id, revoked_at),
  CONSTRAINT fk_chat_key_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Messages: a plain message has content; an encrypted one has ciphertext + iv + the two key ids + the franking tag
-- (HMAC that lets a reporter prove what the message said). A recalled message keeps its row; its content and
-- ciphertext are emptied 30 days after the recall.
ALTER TABLE chat_messages
  MODIFY content VARCHAR(2000) NULL,
  ADD COLUMN ciphertext       MEDIUMTEXT  NULL AFTER content,
  ADD COLUMN iv               VARCHAR(32) NULL AFTER ciphertext,
  ADD COLUMN sender_key_id    BIGINT      NULL AFTER iv,
  ADD COLUMN recipient_key_id BIGINT      NULL AFTER sender_key_id,
  ADD COLUMN franking_tag     CHAR(64)    NULL AFTER recipient_key_id,
  ADD COLUMN reply_to_id      BIGINT      NULL AFTER franking_tag,
  ADD COLUMN edited_at        DATETIME    NULL,
  ADD COLUMN recalled_at      DATETIME    NULL,
  ADD COLUMN changed_at       DATETIME(3) NULL,           -- last edit, recall or reaction: the open page asks for these
  ADD INDEX idx_chat_messages_changed (conversation_id, changed_at),
  ADD CONSTRAINT fk_chat_message_reply FOREIGN KEY (reply_to_id) REFERENCES chat_messages(id) ON DELETE SET NULL,
  ADD CONSTRAINT fk_chat_message_sender_key FOREIGN KEY (sender_key_id) REFERENCES chat_keys(id),
  ADD CONSTRAINT fk_chat_message_recipient_key FOREIGN KEY (recipient_key_id) REFERENCES chat_keys(id),
  ADD CONSTRAINT ck_chat_message_body CHECK (content IS NOT NULL OR ciphertext IS NOT NULL OR recalled_at IS NOT NULL);

-- Earlier versions of an edited message (kept for reports, removed with the message's content after a recall).
CREATE TABLE chat_message_edits (
  id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
  message_id    BIGINT        NOT NULL,
  content       VARCHAR(2000),
  ciphertext    MEDIUMTEXT,
  iv            VARCHAR(32),
  franking_tag  CHAR(64),
  replaced_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat_edits_message (message_id),
  CONSTRAINT fk_chat_edit_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- "Thả cảm xúc": one reaction per member per message.
CREATE TABLE chat_reactions (
  message_id  BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  emoji       VARCHAR(16) NOT NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (message_id, user_id),
  CONSTRAINT fk_chat_reaction_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Photos, videos and voice messages of encrypted conversations: the file is encrypted in the browser, the server only
-- stores the bytes (MediaStorage key "chat/yyyy/MM/uuid.bin") and gives them to the two members. Not in `media`
-- (never scanned, never public). message_id is set when the message that carries the file is sent.
CREATE TABLE chat_files (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
  conversation_id  BIGINT       NOT NULL,
  uploader_id      BIGINT       NOT NULL,
  message_id       BIGINT,
  kind             VARCHAR(10)  NOT NULL,
  storage_key      VARCHAR(500) NOT NULL UNIQUE,
  size_bytes       BIGINT       NOT NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at       DATETIME,
  INDEX idx_chat_files_message (message_id),
  INDEX idx_chat_files_uploader (uploader_id, created_at),
  CONSTRAINT ck_chat_file_kind CHECK (kind IN ('IMAGE','VIDEO','AUDIO')),
  CONSTRAINT fk_chat_file_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_file_uploader FOREIGN KEY (uploader_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_file_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- A reported encrypted message: the decrypted text the reporter revealed, after the server checked it against the
-- message's franking tag. The Moderator queue reads it later.
CREATE TABLE chat_report_evidence (
  id          BIGINT     AUTO_INCREMENT PRIMARY KEY,
  report_id   BIGINT     NOT NULL,
  message_id  BIGINT     NOT NULL,
  plaintext   MEDIUMTEXT NOT NULL,
  created_at  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_chat_evidence_report (report_id),
  CONSTRAINT fk_chat_evidence_report FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_evidence_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- New-message notifications. notified_up_to_id: newest message already announced to this member by e-mail;
-- emailed_at / pushed_at: when the last e-mail / push about this conversation went out (to space them).
ALTER TABLE chat_members
  ADD COLUMN notified_up_to_id BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN emailed_at        DATETIME    NULL,
  ADD COLUMN pushed_at         DATETIME(3) NULL;

-- A member's choice; no row = both on.
CREATE TABLE chat_notify_settings (
  user_id        BIGINT   PRIMARY KEY,
  email_enabled  BOOLEAN  NOT NULL DEFAULT TRUE,
  push_enabled   BOOLEAN  NOT NULL DEFAULT TRUE,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_chat_notify_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Web Push subscriptions (one per browser). endpoint_hash = SHA-256 hex of the endpoint, for the unique key.
CREATE TABLE push_subscriptions (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT        NOT NULL,
  endpoint       VARCHAR(1000) NOT NULL,
  endpoint_hash  CHAR(64)      NOT NULL UNIQUE,
  p256dh         VARCHAR(120)  NOT NULL,
  auth           VARCHAR(48)   NOT NULL,
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at   DATETIME,
  failure_count  INT           NOT NULL DEFAULT 0,
  INDEX idx_push_user (user_id),
  CONSTRAINT fk_push_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- The server's VAPID key pair for Web Push (one row), made at the first start when missing.
CREATE TABLE web_push_keys (
  id           TINYINT      PRIMARY KEY,
  public_key   VARCHAR(120) NOT NULL,   -- base64url of the raw uncompressed P-256 point
  private_key  VARCHAR(64)  NOT NULL,   -- base64url of the 32-byte private scalar
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_web_push_keys_one CHECK (id = 1)
) ENGINE=InnoDB;
