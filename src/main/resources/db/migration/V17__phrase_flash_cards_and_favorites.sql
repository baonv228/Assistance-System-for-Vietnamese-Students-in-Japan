-- V16: "Nhật ngữ" (feature/phrase): flash card results and favourites. A Member flips a flash card and keeps it as
-- "Chưa thuộc" (is_learning) or "Đã thuộc" (is_learned); a card never answered is neither. "Sổ tay" becomes "Yêu
-- thích" (is_favorite, was in_notebook): entries saved before stay saved. Practice sentences are no longer offered:
-- practice_sentences stays (old rows, points history, notifications) but nothing writes to it any more.

ALTER TABLE user_phrase_marks
  RENAME COLUMN in_notebook TO is_favorite,
  ADD COLUMN is_learning BOOLEAN NOT NULL DEFAULT FALSE AFTER is_learned,
  ADD CONSTRAINT ck_mark_card CHECK (NOT (is_learned AND is_learning));
