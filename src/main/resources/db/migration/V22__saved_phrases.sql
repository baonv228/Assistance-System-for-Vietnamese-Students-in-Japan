-- "Lưu" on a phrase or word of "Nhật ngữ": the Member's saved entries, listed in "Đã lưu" and in the "Đã lưu" list of
-- /phrases (user decision, like saved_guides of V20; the "Yêu thích" column dropped in V18 is not brought back).
-- A phrase is only soft-deleted (deleted_at): its rows stay and are hidden by the reads, so the entry comes back on
-- "Đã lưu" if it is restored. The cascade only covers rows removed for good.
CREATE TABLE saved_phrases (
  user_id    BIGINT   NOT NULL,
  phrase_id  BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, phrase_id),
  INDEX idx_saved_phrases_user (user_id, created_at),
  CONSTRAINT fk_sphrase_user   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
  CONSTRAINT fk_sphrase_phrase FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
) ENGINE=InnoDB;
