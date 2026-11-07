-- "Lưu" on a guide (Cẩm nang): the Member's saved guides, listed in "Đã lưu".
-- A guide is only soft-deleted (deleted_at, V12) or unpublished: its rows stay and are hidden by the reads, so the
-- guide comes back on "Đã lưu" if it is published again. The cascade only covers rows removed for good.
CREATE TABLE saved_guides (
  user_id    BIGINT   NOT NULL,
  guide_id   BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, guide_id),
  INDEX idx_saved_guides_user (user_id, created_at),
  CONSTRAINT fk_sguide_user  FOREIGN KEY (user_id)  REFERENCES users(id)       ON DELETE CASCADE,
  CONSTRAINT fk_sguide_guide FOREIGN KEY (guide_id) REFERENCES guide_items(id) ON DELETE CASCADE
) ENGINE=InnoDB;
