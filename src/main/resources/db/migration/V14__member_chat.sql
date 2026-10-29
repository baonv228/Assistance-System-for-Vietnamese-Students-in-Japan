-- V14: "Tin nhắn", one-to-one chat between Members (feature/chat).
--
-- One conversation per pair of members (user_low_id < user_high_id). Each side has its own chat_members row:
--   state REQUEST  : the other member wrote first and this one has not answered yet ("Lời mời nhắn tin"). Until the
--                    request is accepted (or answered) the writer may send only a few messages.
--   state ACCEPTED : normal inbox. The member who writes first is ACCEPTED from the start.
--   state DECLINED : the request was refused: it leaves this member's lists, and the writer cannot send more until
--                    this member writes back.
--   last_read_id   : newest message this member has read (unread = the other side's messages after it).
--   cleared_id     : "Xóa cuộc trò chuyện" for this member only: messages up to this id are no longer shown to them.
-- Messages of LOCKED or DELETED accounts are hidden from the other side like their posts and comments (shown again
-- when the account is unlocked); nothing is deleted.

CREATE TABLE chat_conversations (
  id            BIGINT   AUTO_INCREMENT PRIMARY KEY,
  user_low_id   BIGINT   NOT NULL,
  user_high_id  BIGINT   NOT NULL,
  started_by    BIGINT   NOT NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_chat_pair (user_low_id, user_high_id),
  INDEX idx_chat_started (started_by, created_at),
  CONSTRAINT ck_chat_pair CHECK (user_low_id < user_high_id),
  CONSTRAINT fk_chat_low     FOREIGN KEY (user_low_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_high    FOREIGN KEY (user_high_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_starter FOREIGN KEY (started_by) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE chat_members (
  conversation_id  BIGINT      NOT NULL,
  user_id          BIGINT      NOT NULL,
  state            VARCHAR(10) NOT NULL,
  last_read_id     BIGINT      NOT NULL DEFAULT 0,
  cleared_id       BIGINT      NOT NULL DEFAULT 0,
  updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (conversation_id, user_id),
  INDEX idx_chat_members_user (user_id, state),
  CONSTRAINT ck_chat_member_state CHECK (state IN ('REQUEST','ACCEPTED','DECLINED')),
  CONSTRAINT fk_chat_member_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  conversation_id  BIGINT        NOT NULL,
  sender_id        BIGINT        NOT NULL,
  content          VARCHAR(2000) NOT NULL,
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat_messages_conv (conversation_id, id),
  INDEX idx_chat_messages_sender (sender_id, created_at),
  CONSTRAINT fk_chat_message_conv   FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_message_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- "Chặn": neither member can send the other a message while the row exists (the blocker can unblock).
CREATE TABLE chat_blocks (
  blocker_id  BIGINT   NOT NULL,
  blocked_id  BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blocker_id, blocked_id),
  INDEX idx_chat_blocks_blocked (blocked_id),
  CONSTRAINT ck_chat_block_self CHECK (blocker_id <> blocked_id),
  CONSTRAINT fk_chat_blocker FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_blocked FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- A reported message gets a report case like a post or comment (target MESSAGE, handled by a Moderator later).
-- A CHECK constraint cannot be changed in place, so it is dropped and added again (MySQL 8.0.16+).
ALTER TABLE report_cases DROP CHECK ck_case_target;

ALTER TABLE report_cases
  ADD CONSTRAINT ck_case_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT','PHRASE','PRACTICE','MESSAGE'));
