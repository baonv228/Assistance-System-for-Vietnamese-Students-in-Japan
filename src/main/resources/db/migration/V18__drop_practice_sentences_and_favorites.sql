-- V17: removes what "Nhật ngữ" no longer uses (user decisions): the practice sentences ("Câu luyện tập") and
-- "Yêu thích" (user_phrase_marks.is_favorite, was in_notebook "Sổ tay"). No code reads or writes them. Only the
-- development databases that ran the unreleased practice feature hold rows; they are removed first, so the CHECK
-- constraints can drop the PRACTICE target. On a database without such rows every DELETE below changes nothing.

-- Points earned with practice sentences: taken back from the total (never below 0), then their history lines go
UPDATE users u
JOIN (SELECT user_id, SUM(delta) AS delta FROM point_transactions
      WHERE ref_type = 'PRACTICE' OR reason LIKE 'PRACTICE%' GROUP BY user_id) p ON p.user_id = u.id
SET u.points = GREATEST(u.points - p.delta, 0);
DELETE FROM point_transactions WHERE ref_type = 'PRACTICE' OR reason LIKE 'PRACTICE%';

DELETE FROM notifications WHERE ref_type = 'PRACTICE';
DELETE FROM report_cases WHERE target_type = 'PRACTICE';      -- their reports go with them (fk_report_case CASCADE)
DELETE FROM moderation_scans WHERE target_type = 'PRACTICE';
DELETE FROM appeals WHERE target_type = 'PRACTICE';

DROP TABLE practice_sentences;

ALTER TABLE moderation_scans
  DROP CHECK ck_scan_target,
  ADD CONSTRAINT ck_scan_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT','MEDIA'));
ALTER TABLE report_cases
  DROP CHECK ck_case_target,
  ADD CONSTRAINT ck_case_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT','PHRASE','MESSAGE'));
ALTER TABLE appeals
  DROP CHECK ck_appeal_target,
  ADD CONSTRAINT ck_appeal_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT'));

ALTER TABLE user_phrase_marks DROP COLUMN is_favorite;
