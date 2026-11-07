-- "Chủ đề quan tâm" a Member chose themselves (Tôi, or "Điều chỉnh" in "Dành cho bạn" on the Home): blog topics whose
-- posts "Dành cho bạn" ranks right after those near the Member, next to the topics learnt from what the Member liked,
-- saved, commented on or wrote. At most 5 per Member (checked by the application, "cần chốt"). "Khác" and topics the
-- Admin switched off are never offered nor read; their rows simply stay until the Member saves another choice.
CREATE TABLE user_interest_topics (
  user_id    BIGINT   NOT NULL,
  topic_id   BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, topic_id),
  INDEX idx_uit_topic (topic_id),
  CONSTRAINT fk_uit_user  FOREIGN KEY (user_id)  REFERENCES users(id)       ON DELETE CASCADE,
  CONSTRAINT fk_uit_topic FOREIGN KEY (topic_id) REFERENCES blog_topics(id) ON DELETE CASCADE
) ENGINE=InnoDB;
