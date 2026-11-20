-- "Nhiệm vụ hằng ngày" of "Nhật ngữ" (feature/quest) and "xu" (feature/coin), decided 18/11/2026: every Japan day a
-- Member gets 3 small study tasks (flash cards, the phrase of the day); finishing them gives xu, a currency of its own
-- that never counts toward the rank. Only the Member's own "Đổi" turns xu into rank points (point_transactions,
-- reason COIN_EXCHANGE), at a fixed rate and at most a few points a Japan month, because rank points carry real
-- privileges (featured posts, Senpai answers first, pins approved without a Moderator).

-- Balance of xu, kept in step with coin_transactions like users.points with point_transactions; never below 0.
ALTER TABLE users
  ADD COLUMN coins INT NOT NULL DEFAULT 0 AFTER points,
  ADD CONSTRAINT ck_users_coins CHECK (coins >= 0);

-- "Câu tiếng Nhật hôm nay": the phrase of each Japan day, chosen once and kept, so the Home and the quest
-- PHRASE_OF_DAY show the same phrase all day even when the Academic Manager adds or deletes phrases meanwhile.
-- Phrases are only soft-deleted; the cascade covers rows removed for good (tests).
CREATE TABLE daily_phrases (
  phrase_date DATE     NOT NULL PRIMARY KEY,
  phrase_id   BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dphrase_phrase FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- The quests of one Member for one Japan day (quest_date), made on first need and never changed that day.
-- params: what the quest is about (TOPIC: topicId; PHRASE_OF_DAY: phraseId…). title: kept as shown that day.
-- coins: xu actually received (0 until completed, may stay 0 under the daily cap).
CREATE TABLE user_daily_quests (
  id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  quest_date    DATE        NOT NULL,
  code          VARCHAR(30) NOT NULL,
  sort_order    TINYINT     NOT NULL,
  params        JSON,
  title         VARCHAR(160) NOT NULL,
  target        INT         NOT NULL,
  progress      INT         NOT NULL DEFAULT 0,
  coins         INT         NOT NULL DEFAULT 0,
  completed_at  DATETIME,
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_udq_user_day_code (user_id, quest_date, code),
  CONSTRAINT ck_udq_progress CHECK (progress >= 0 AND progress <= target),
  CONSTRAINT ck_udq_target   CHECK (target >= 1),
  CONSTRAINT fk_udq_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- The entries already counted for a quest: each entry counts once per quest; also tells whether an entry was ever
-- counted for LEARN_NEW on any day.
CREATE TABLE user_quest_items (
  user_daily_quest_id BIGINT   NOT NULL,
  phrase_id           BIGINT   NOT NULL,
  counted_at          DATETIME NOT NULL,
  PRIMARY KEY (user_daily_quest_id, phrase_id),
  INDEX idx_uqi_phrase (phrase_id),
  CONSTRAINT fk_uqi_quest  FOREIGN KEY (user_daily_quest_id) REFERENCES user_daily_quests(id) ON DELETE CASCADE,
  CONSTRAINT fk_uqi_phrase FOREIGN KEY (phrase_id)           REFERENCES phrases(id)           ON DELETE CASCADE
) ENGINE=InnoDB;

-- A Japan day on which every quest of the Member was completed ("xong"): all_done_at, and streak = the run of done
-- days up to this one, kept when the day was done (best streak = MAX(streak); "Học 7 ngày liên tiếp").
CREATE TABLE user_quest_days (
  id           BIGINT   AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT   NOT NULL,
  quest_date   DATE     NOT NULL,
  all_done_at  DATETIME,
  streak       INT,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_uqd_user_day (user_id, quest_date),
  CONSTRAINT fk_uqd_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Every flash card answer (POST /api/phrases/{id}/flash): when the Member answered, to the millisecond, so answers
-- given too fast are not counted for the quests (user_phrase_marks.updated_at does not change when the same answer is
-- given again). Usable later for spaced repetition.
CREATE TABLE phrase_answer_log (
  id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT      NOT NULL,
  phrase_id    BIGINT      NOT NULL,
  learned      BOOLEAN     NOT NULL,
  answered_at  DATETIME(3) NOT NULL,
  INDEX idx_pal_user (user_id, answered_at),
  CONSTRAINT fk_pal_user   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
  CONSTRAINT fk_pal_phrase FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Every change of xu, newest last; users.coins is the running total. title is kept with the row (the history never
-- joins other tables). ref: DAILY_QUEST → QUEST / user_daily_quests.id; DAILY_QUEST_ALL and QUEST_STREAK →
-- QUEST_DAY / user_quest_days.id; EXCHANGE and DEV_ADJUST → none. The unique key makes each quest, each day bonus and
-- each streak milestone pay once (MySQL lets several rows hold NULL in a unique key, so exchanges are not limited).
CREATE TABLE coin_transactions (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  delta       INT          NOT NULL,
  reason      VARCHAR(30)  NOT NULL,
  title       VARCHAR(160) NOT NULL,
  ref_type    VARCHAR(20),
  ref_id      BIGINT,
  quest_date  DATE,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_coin_ref (user_id, reason, ref_type, ref_id),
  INDEX idx_coin_user (user_id, created_at),
  INDEX idx_coin_user_day (user_id, quest_date),
  CONSTRAINT fk_coin_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
