-- ASVSJ ("Senpai ơi") – database schema v1, built from the current prototype
-- MySQL 8.0.16 or later (CHECK constraints are enforced from 8.0.16).
-- Run: mysql -u root -p < asvsj_schema.sql
-- Details and Vietnamese descriptions: database-design.md
--
-- Notes for MySQL:
--  * Foreign keys are written as table-level FOREIGN KEY clauses. MySQL 8.0 silently
--    ignores REFERENCES written inline on a column.
--  * All times are stored in UTC as DATETIME; the Java app converts to Japan time.
--  * utf8mb4 is required for Japanese text and emoji.

CREATE DATABASE IF NOT EXISTS asvsj CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE asvsj;
SET NAMES utf8mb4;

-- Drop everything first so the script can rebuild the database from scratch
SET FOREIGN_KEY_CHECKS = 0;
DROP VIEW IF EXISTS v_place_rating;
DROP TABLE IF EXISTS
  api_usage, activity_logs, system_settings, notifications,
  user_guide_progress, guide_items, guide_stages,
  appeals, reports, report_cases, moderation_scans, banned_keywords, reason_options,
  practice_sentences, user_phrase_marks, phrase_topic_links, phrases, learning_topics,
  featured_snapshots, comment_likes, comments, saved_blogs, blog_likes, blog_media,
  blog_place_links, blog_topic_links, blogs, blog_topics,
  review_likes, review_media, reviews, events,
  duplicate_dismissals, place_merges, saved_places, place_closed_reports, place_history,
  places, category_google_types, place_categories,
  point_transactions, ranks, user_tags, tag_requests, tag_proof_types, tag_types, media,
  user_daily_usage, user_consents, legal_documents, user_tokens, user_auth_providers, users, areas;
SET FOREIGN_KEY_CHECKS = 1;

-- =========================================================
-- 1. Accounts
-- =========================================================
CREATE TABLE areas (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
  ward          VARCHAR(80)  NOT NULL,
  station       VARCHAR(80)  NOT NULL,
  display_name  VARCHAR(160) NOT NULL UNIQUE,
  sort_order    INT          NOT NULL DEFAULT 0,
  is_active     BOOLEAN      NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE users (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
  email               VARCHAR(255) NOT NULL UNIQUE,
  -- BCrypt hash (Spring Security BCryptPasswordEncoder). Never the plain password.
  -- NULL when the account only signs in with Google/Facebook.
  password_hash       VARCHAR(255),
  email_verified_at   DATETIME,
  failed_login_count  TINYINT      NOT NULL DEFAULT 0,
  locked_until        DATETIME,
  display_name        VARCHAR(60)  NOT NULL,
  avatar_url          VARCHAR(500),
  role                VARCHAR(20)  NOT NULL DEFAULT 'USER',
  status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  is_adult_confirmed  BOOLEAN      NOT NULL,
  area_id             BIGINT,
  stage               VARCHAR(10),
  event_window_days   SMALLINT     NOT NULL DEFAULT 30,
  points              INT          NOT NULL DEFAULT 0,
  false_report_count  INT          NOT NULL DEFAULT 0,
  report_muted_until  DATETIME,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_login_at       DATETIME,
  deleted_at          DATETIME,
  CONSTRAINT ck_users_role   CHECK (role IN ('USER','MODERATOR','ACADEMIC_MANAGER','ADMIN')),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','LOCKED','DELETED')),
  CONSTRAINT ck_users_stage  CHECK (stage IN ('PRE','ARR','LEAVE','HOME')),
  CONSTRAINT ck_users_window CHECK (event_window_days IN (15,30)),
  CONSTRAINT fk_users_area FOREIGN KEY (area_id) REFERENCES areas(id)
) ENGINE=InnoDB;

CREATE TABLE user_auth_providers (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT       NOT NULL,
  provider          VARCHAR(20)  NOT NULL,
  provider_user_id  VARCHAR(255) NOT NULL,
  linked_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_provider_user (provider, provider_user_id),
  CONSTRAINT ck_auth_provider CHECK (provider IN ('GOOGLE','FACEBOOK')),
  CONSTRAINT fk_auth_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- One-time links for email verification and password reset.
-- Only the SHA-256 hash of the token is stored; the raw token is sent by email.
CREATE TABLE user_tokens (
  id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  purpose     VARCHAR(20) NOT NULL,
  token_hash  CHAR(64)    NOT NULL UNIQUE,
  expires_at  DATETIME    NOT NULL,
  used_at     DATETIME,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_tokens_user (user_id, purpose),
  CONSTRAINT ck_tokens_purpose CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
  CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE legal_documents (
  id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
  doc_type      VARCHAR(20) NOT NULL,
  version       VARCHAR(20) NOT NULL,
  content       MEDIUMTEXT  NOT NULL,
  effective_at  DATETIME    NOT NULL,
  UNIQUE KEY uq_doc_version (doc_type, version),
  CONSTRAINT ck_doc_type CHECK (doc_type IN ('TOS','PRIVACY','COMMUNITY'))
) ENGINE=InnoDB;

CREATE TABLE user_consents (
  id           BIGINT   AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT   NOT NULL,
  document_id  BIGINT   NOT NULL,
  accepted_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_consent_doc  FOREIGN KEY (document_id) REFERENCES legal_documents(id)
) ENGINE=InnoDB;

CREATE TABLE user_daily_usage (
  user_id     BIGINT   NOT NULL,
  usage_date  DATE     NOT NULL,
  posts       SMALLINT NOT NULL DEFAULT 0,
  pins        SMALLINT NOT NULL DEFAULT 0,
  videos      SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, usage_date),
  CONSTRAINT fk_usage_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- 4. Media (created early: tags, events and posts point to it)
-- =========================================================
CREATE TABLE media (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
  owner_id      BIGINT       NOT NULL,
  media_type    VARCHAR(10)  NOT NULL,
  storage_key   VARCHAR(500) NOT NULL UNIQUE,
  mime_type     VARCHAR(50)  NOT NULL,
  size_bytes    BIGINT       NOT NULL,
  duration_sec  SMALLINT,
  width         INT,
  height        INT,
  scan_status   VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at    DATETIME,
  CONSTRAINT ck_media_type     CHECK (media_type IN ('IMAGE','VIDEO')),
  CONSTRAINT ck_media_duration CHECK (duration_sec <= 30),
  CONSTRAINT ck_media_scan     CHECK (scan_status IN ('PROCESSING','PASSED','REJECTED')),
  CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 2. Identity tags
-- =========================================================
CREATE TABLE tag_types (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(60)  NOT NULL UNIQUE,
  description  VARCHAR(255) NOT NULL,
  color        VARCHAR(20)  NOT NULL,
  selfie_only  BOOLEAN      NOT NULL DEFAULT FALSE,
  is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by   BIGINT,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_tagtype_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE tag_proof_types (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  tag_type_id  BIGINT       NOT NULL,
  name         VARCHAR(120) NOT NULL,
  CONSTRAINT fk_proof_tag FOREIGN KEY (tag_type_id) REFERENCES tag_types(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE tag_requests (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL,
  tag_type_id     BIGINT       NOT NULL,
  proof_type_id   BIGINT       NOT NULL,
  proof_media_id  BIGINT,
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  reject_reason   VARCHAR(255),
  submitted_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at      DATETIME     NOT NULL,
  reviewed_by     BIGINT,
  reviewed_at     DATETIME,
  CONSTRAINT ck_tagreq_status CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
  CONSTRAINT fk_tagreq_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_tagreq_tag    FOREIGN KEY (tag_type_id) REFERENCES tag_types(id),
  CONSTRAINT fk_tagreq_proof  FOREIGN KEY (proof_type_id) REFERENCES tag_proof_types(id),
  CONSTRAINT fk_tagreq_media  FOREIGN KEY (proof_media_id) REFERENCES media(id) ON DELETE SET NULL,
  CONSTRAINT fk_tagreq_mod    FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE user_tags (
  user_id      BIGINT   NOT NULL,
  tag_type_id  BIGINT   NOT NULL,
  request_id   BIGINT,
  granted_by   BIGINT   NOT NULL,
  granted_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at   DATETIME,
  PRIMARY KEY (user_id, tag_type_id),
  CONSTRAINT fk_usertag_user    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_usertag_tag     FOREIGN KEY (tag_type_id) REFERENCES tag_types(id),
  CONSTRAINT fk_usertag_request FOREIGN KEY (request_id) REFERENCES tag_requests(id),
  CONSTRAINT fk_usertag_granter FOREIGN KEY (granted_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 3. Points and ranks
-- =========================================================
CREATE TABLE ranks (
  id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(60) NOT NULL UNIQUE,
  min_points      INT         NOT NULL UNIQUE,
  featured_bonus  INT         NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE point_transactions (
  id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  delta       INT         NOT NULL,
  reason      VARCHAR(40) NOT NULL,
  ref_type    VARCHAR(20),
  ref_id      BIGINT,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_points_user (user_id, created_at),
  CONSTRAINT fk_points_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- 5. Places and map
-- =========================================================
CREATE TABLE place_categories (
  id          VARCHAR(20) PRIMARY KEY,
  name_vi     VARCHAR(80) NOT NULL,
  icon        VARCHAR(50) NOT NULL,
  sort_order  INT         NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE category_google_types (
  google_type  VARCHAR(50) PRIMARY KEY,
  category_id  VARCHAR(20) NOT NULL,
  CONSTRAINT fk_gtype_cat FOREIGN KEY (category_id) REFERENCES place_categories(id)
) ENGINE=InnoDB;

CREATE TABLE places (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
  google_place_id  VARCHAR(255) UNIQUE,
  name_vi          VARCHAR(160) NOT NULL,
  name_ja          VARCHAR(160),
  name_en          VARCHAR(160),
  category_id      VARCHAR(20)  NOT NULL,
  area_id          BIGINT,
  address          VARCHAR(255),
  lat              DECIMAL(9,6),
  lng              DECIMAL(9,6),
  source           VARCHAR(20)  NOT NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  is_closed        BOOLEAN      NOT NULL DEFAULT FALSE,
  closed_at        DATETIME,
  reject_reason    VARCHAR(255),
  removed_reason   VARCHAR(255),
  merged_into_id   BIGINT,
  created_by       BIGINT       NOT NULL,
  reviewed_by      BIGINT,
  reviewed_at      DATETIME,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_places_status_cat (status, category_id),
  CONSTRAINT ck_places_source   CHECK (source IN ('GOOGLE','TEAM','USER','MODERATOR')),
  CONSTRAINT ck_places_status   CHECK (status IN ('PENDING','ACTIVE','REJECTED','REMOVED','MERGED')),
  CONSTRAINT ck_places_position CHECK (google_place_id IS NOT NULL OR (lat IS NOT NULL AND lng IS NOT NULL)),
  CONSTRAINT ck_places_merged   CHECK ((status = 'MERGED') = (merged_into_id IS NOT NULL)),
  CONSTRAINT fk_places_cat      FOREIGN KEY (category_id) REFERENCES place_categories(id),
  CONSTRAINT fk_places_area     FOREIGN KEY (area_id) REFERENCES areas(id),
  CONSTRAINT fk_places_merged   FOREIGN KEY (merged_into_id) REFERENCES places(id),
  CONSTRAINT fk_places_creator  FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT fk_places_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE place_history (
  id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
  place_id    BIGINT      NOT NULL,
  action      VARCHAR(30) NOT NULL,
  actor_id    BIGINT      NOT NULL,
  detail      JSON,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_phist_action CHECK (action IN ('CREATED','APPROVED','REJECTED','EDITED','CLOSED','REOPENED','REMOVED','RESTORED','MERGED')),
  CONSTRAINT fk_phist_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
  CONSTRAINT fk_phist_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE place_closed_reports (
  place_id     BIGINT   NOT NULL,
  user_id      BIGINT   NOT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at  DATETIME,
  PRIMARY KEY (place_id, user_id),
  CONSTRAINT fk_closed_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
  CONSTRAINT fk_closed_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE saved_places (
  user_id   BIGINT   NOT NULL,
  place_id  BIGINT   NOT NULL,
  saved_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, place_id),
  CONSTRAINT fk_savedp_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_savedp_place FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE place_merges (
  id               BIGINT   AUTO_INCREMENT PRIMARY KEY,
  kept_place_id    BIGINT   NOT NULL,
  merged_place_id  BIGINT   NOT NULL UNIQUE,
  field_choices    JSON     NOT NULL,
  moved_reviews    INT      NOT NULL DEFAULT 0,
  merged_by        BIGINT   NOT NULL,
  merged_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_merge_diff  CHECK (kept_place_id <> merged_place_id),
  CONSTRAINT fk_merge_kept   FOREIGN KEY (kept_place_id) REFERENCES places(id),
  CONSTRAINT fk_merge_merged FOREIGN KEY (merged_place_id) REFERENCES places(id),
  CONSTRAINT fk_merge_by     FOREIGN KEY (merged_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- No ON DELETE CASCADE here: MySQL forbids referential actions on columns used in a CHECK.
CREATE TABLE duplicate_dismissals (
  place_a_id    BIGINT   NOT NULL,
  place_b_id    BIGINT   NOT NULL,
  dismissed_by  BIGINT   NOT NULL,
  dismissed_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (place_a_id, place_b_id),
  CONSTRAINT ck_dismiss_order CHECK (place_a_id < place_b_id),
  CONSTRAINT fk_dismiss_a  FOREIGN KEY (place_a_id) REFERENCES places(id),
  CONSTRAINT fk_dismiss_b  FOREIGN KEY (place_b_id) REFERENCES places(id),
  CONSTRAINT fk_dismiss_by FOREIGN KEY (dismissed_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 7. Events (before reviews: event reviews point here)
-- =========================================================
CREATE TABLE events (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  title           VARCHAR(160) NOT NULL,
  cover_media_id  BIGINT,
  start_date      DATE         NOT NULL,
  end_date        DATE         NOT NULL,
  location_name   VARCHAR(160) NOT NULL,
  lat             DECIMAL(9,6),
  lng             DECIMAL(9,6),
  cost_note       VARCHAR(120),
  tip             VARCHAR(500),
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at      DATETIME,
  INDEX idx_events_dates (start_date, end_date),
  CONSTRAINT ck_events_dates CHECK (end_date >= start_date),
  CONSTRAINT fk_events_cover   FOREIGN KEY (cover_media_id) REFERENCES media(id),
  CONSTRAINT fk_events_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 6. Reviews
-- =========================================================
CREATE TABLE reviews (
  id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
  author_id       BIGINT        NOT NULL,
  place_id        BIGINT,
  event_id        BIGINT,
  event_year      SMALLINT,
  rating          TINYINT       NOT NULL,
  content         VARCHAR(2000) NOT NULL,
  status          VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING',
  is_edited       BOOLEAN       NOT NULL DEFAULT FALSE,
  removed_reason  VARCHAR(255),
  removed_at      DATETIME,
  purge_at        DATETIME,     -- removed_at + 30 days: kept for appeals, then deleted for good
  like_count      INT           NOT NULL DEFAULT 0,
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- One review per user per place, and one per user per year of an event.
  -- (NULLs never collide, so place reviews and event reviews do not block each other.)
  UNIQUE KEY uq_review_place (author_id, place_id),
  UNIQUE KEY uq_review_event (author_id, event_id, event_year),
  INDEX idx_reviews_place (place_id, status),
  INDEX idx_reviews_event (event_id, event_year),
  INDEX idx_reviews_purge (purge_at),
  CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT ck_reviews_status CHECK (status IN ('PROCESSING','PUBLISHED','REJECTED','HIDDEN','REMOVED')),
  CONSTRAINT ck_reviews_target CHECK ((place_id IS NULL) <> (event_id IS NULL)),
  CONSTRAINT ck_reviews_year   CHECK (event_id IS NULL OR event_year IS NOT NULL),
  CONSTRAINT fk_reviews_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_reviews_place  FOREIGN KEY (place_id) REFERENCES places(id),
  CONSTRAINT fk_reviews_event  FOREIGN KEY (event_id) REFERENCES events(id)
) ENGINE=InnoDB;

CREATE TABLE review_media (
  review_id   BIGINT   NOT NULL,
  media_id    BIGINT   NOT NULL,
  sort_order  SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (review_id, media_id),
  CONSTRAINT fk_rmedia_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE,
  CONSTRAINT fk_rmedia_media  FOREIGN KEY (media_id) REFERENCES media(id)
) ENGINE=InnoDB;

CREATE TABLE review_likes (
  review_id   BIGINT   NOT NULL,
  user_id     BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (review_id, user_id),
  CONSTRAINT fk_rlike_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE,
  CONSTRAINT fk_rlike_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- 8. Blog
-- =========================================================
CREATE TABLE blog_topics (
  id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(60) NOT NULL UNIQUE,
  is_other    BOOLEAN     NOT NULL DEFAULT FALSE,
  sort_order  INT         NOT NULL DEFAULT 0,
  is_active   BOOLEAN     NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE blogs (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
  author_id        BIGINT       NOT NULL,
  blog_type        VARCHAR(10)  NOT NULL DEFAULT 'POST',
  title            VARCHAR(200) NOT NULL,
  content          MEDIUMTEXT   NOT NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
  is_pinned        BOOLEAN      NOT NULL DEFAULT FALSE,
  best_comment_id  BIGINT,
  is_edited        BOOLEAN      NOT NULL DEFAULT FALSE,
  removed_reason   VARCHAR(255),
  removed_at       DATETIME,
  purge_at         DATETIME,
  like_count       INT          NOT NULL DEFAULT 0,
  comment_count    INT          NOT NULL DEFAULT 0,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_blogs_status_created (status, created_at),
  INDEX idx_blogs_purge (purge_at),
  -- ngram parser so Japanese text (no spaces between words) is searchable too
  FULLTEXT KEY ft_blogs_search (title, content) WITH PARSER ngram,
  CONSTRAINT ck_blogs_type   CHECK (blog_type IN ('POST','QA')),
  CONSTRAINT ck_blogs_status CHECK (status IN ('PROCESSING','PUBLISHED','REJECTED','HIDDEN','REMOVED')),
  CONSTRAINT fk_blogs_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
  -- fk_blogs_best_comment is added after the comments table
) ENGINE=InnoDB;

CREATE TABLE blog_topic_links (
  blog_id   BIGINT NOT NULL,
  topic_id  BIGINT NOT NULL,
  PRIMARY KEY (blog_id, topic_id),
  CONSTRAINT fk_btopic_blog  FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_btopic_topic FOREIGN KEY (topic_id) REFERENCES blog_topics(id)
) ENGINE=InnoDB;

CREATE TABLE blog_place_links (
  blog_id   BIGINT NOT NULL,
  place_id  BIGINT NOT NULL,
  PRIMARY KEY (blog_id, place_id),
  CONSTRAINT fk_bplace_blog  FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_bplace_place FOREIGN KEY (place_id) REFERENCES places(id)
) ENGINE=InnoDB;

CREATE TABLE blog_media (
  blog_id     BIGINT   NOT NULL,
  media_id    BIGINT   NOT NULL,
  sort_order  SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (blog_id, media_id),
  CONSTRAINT fk_bmedia_blog  FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_bmedia_media FOREIGN KEY (media_id) REFERENCES media(id)
) ENGINE=InnoDB;

CREATE TABLE blog_likes (
  blog_id     BIGINT   NOT NULL,
  user_id     BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blog_id, user_id),
  CONSTRAINT fk_blike_blog FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_blike_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE saved_blogs (
  blog_id     BIGINT   NOT NULL,
  user_id     BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blog_id, user_id),
  CONSTRAINT fk_bsave_blog FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_bsave_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE comments (
  id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
  blog_id     BIGINT        NOT NULL,
  author_id   BIGINT        NOT NULL,
  parent_id   BIGINT,
  content     VARCHAR(2000) NOT NULL,
  status      VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING',
  is_edited   BOOLEAN       NOT NULL DEFAULT FALSE,
  removed_at  DATETIME,
  purge_at    DATETIME,
  like_count  INT           NOT NULL DEFAULT 0,
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_comments_blog (blog_id, created_at),
  INDEX idx_comments_purge (purge_at),
  CONSTRAINT ck_comments_status CHECK (status IN ('PROCESSING','PUBLISHED','REJECTED','HIDDEN','REMOVED')),
  CONSTRAINT fk_comments_blog   FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE,
  CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB;

ALTER TABLE blogs ADD CONSTRAINT fk_blogs_best_comment
  FOREIGN KEY (best_comment_id) REFERENCES comments(id) ON DELETE SET NULL;

CREATE TABLE comment_likes (
  comment_id  BIGINT   NOT NULL,
  user_id     BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (comment_id, user_id),
  CONSTRAINT fk_clike_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
  CONSTRAINT fk_clike_user    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE featured_snapshots (
  id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
  period       VARCHAR(10) NOT NULL,
  computed_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  blog_id      BIGINT      NOT NULL,
  rank_no      TINYINT     NOT NULL,
  score        INT         NOT NULL,
  CONSTRAINT ck_feat_period CHECK (period IN ('WEEK','MONTH')),
  CONSTRAINT ck_feat_rank   CHECK (rank_no BETWEEN 1 AND 3),
  CONSTRAINT fk_feat_blog FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- 9. Japanese learning (may be reduced after the council review)
-- =========================================================
CREATE TABLE learning_topics (
  id    VARCHAR(20) PRIMARY KEY,
  name  VARCHAR(60) NOT NULL,
  icon  VARCHAR(50) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE phrases (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  category_id  VARCHAR(20)  NOT NULL,
  kind         VARCHAR(10)  NOT NULL,
  japanese     VARCHAR(200) NOT NULL,
  romaji       VARCHAR(200) NOT NULL,
  meaning_vi   VARCHAR(300) NOT NULL,
  created_by   BIGINT       NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at   DATETIME,
  CONSTRAINT ck_phrases_kind CHECK (kind IN ('PHRASE','VOCAB')),
  CONSTRAINT fk_phrases_cat     FOREIGN KEY (category_id) REFERENCES place_categories(id),
  CONSTRAINT fk_phrases_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE phrase_topic_links (
  phrase_id  BIGINT      NOT NULL,
  topic_id   VARCHAR(20) NOT NULL,
  PRIMARY KEY (phrase_id, topic_id),
  CONSTRAINT fk_ptopic_phrase FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE,
  CONSTRAINT fk_ptopic_topic  FOREIGN KEY (topic_id) REFERENCES learning_topics(id)
) ENGINE=InnoDB;

CREATE TABLE user_phrase_marks (
  user_id      BIGINT   NOT NULL,
  phrase_id    BIGINT   NOT NULL,
  is_learned   BOOLEAN  NOT NULL DEFAULT FALSE,
  in_notebook  BOOLEAN  NOT NULL DEFAULT FALSE,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, phrase_id),
  CONSTRAINT fk_mark_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_mark_phrase FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE practice_sentences (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  phrase_id    BIGINT       NOT NULL,
  author_id    BIGINT       NOT NULL,
  content      VARCHAR(500) NOT NULL,
  status       VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
  is_correct   BOOLEAN      NOT NULL DEFAULT FALSE,
  correction   VARCHAR(500),
  reviewed_by  BIGINT,
  reviewed_at  DATETIME,
  removed_at   DATETIME,
  purge_at     DATETIME,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT ck_prac_status CHECK (status IN ('PUBLISHED','HIDDEN','REMOVED')),
  CONSTRAINT fk_prac_phrase   FOREIGN KEY (phrase_id) REFERENCES phrases(id) ON DELETE CASCADE,
  CONSTRAINT fk_prac_author   FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_prac_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 10. Moderation and reports
-- =========================================================
CREATE TABLE reason_options (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  scope       VARCHAR(20)  NOT NULL,
  label       VARCHAR(120) NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_reason (scope, label),
  CONSTRAINT ck_reason_scope CHECK (scope IN ('REPORT','ACADEMIC_REPORT','PIN_REJECT','PLACE_REMOVE'))
) ENGINE=InnoDB;

CREATE TABLE banned_keywords (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  keyword     VARCHAR(100) NOT NULL UNIQUE,
  created_by  BIGINT,           -- NULL for keywords seeded with the database
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_kw_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE moderation_scans (
  id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
  target_type  VARCHAR(20) NOT NULL,
  target_id    BIGINT      NOT NULL,
  engine       VARCHAR(20) NOT NULL,
  result       VARCHAR(10) NOT NULL,
  detail       JSON,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_scans_target (target_type, target_id),
  CONSTRAINT ck_scan_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT','PRACTICE','MEDIA')),
  CONSTRAINT ck_scan_engine CHECK (engine IN ('KEYWORD','OPENAI')),
  CONSTRAINT ck_scan_result CHECK (result IN ('PASS','FLAG','ERROR'))
) ENGINE=InnoDB;

CREATE TABLE report_cases (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  target_type    VARCHAR(20)  NOT NULL,
  target_id      BIGINT       NOT NULL,
  handler_role   VARCHAR(20)  NOT NULL,
  report_count   INT          NOT NULL DEFAULT 0,
  priority       VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
  status         VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
  decision       VARCHAR(20),
  decision_note  VARCHAR(500),
  handled_by     BIGINT,
  handled_at     DATETIME,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 1 while the case is open, NULL once resolved. MySQL has no partial index,
  -- so this column plus the UNIQUE key allows only one open case per item.
  open_flag      TINYINT AS (CASE WHEN status = 'OPEN' THEN 1 END) STORED,
  UNIQUE KEY uq_open_case (target_type, target_id, open_flag),
  CONSTRAINT ck_case_target   CHECK (target_type IN ('REVIEW','BLOG','COMMENT','PHRASE','PRACTICE')),
  CONSTRAINT ck_case_handler  CHECK (handler_role IN ('MODERATOR','ACADEMIC_MANAGER')),
  CONSTRAINT ck_case_priority CHECK (priority IN ('NORMAL','URGENT')),
  CONSTRAINT ck_case_status   CHECK (status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_case_decision CHECK (decision IN ('KEEP','REMOVE','CORRECTED')),
  CONSTRAINT fk_case_handler FOREIGN KEY (handled_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE reports (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  case_id      BIGINT       NOT NULL,
  reporter_id  BIGINT       NOT NULL,
  reason_id    BIGINT       NOT NULL,
  note         VARCHAR(500),
  is_false     BOOLEAN,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_report_once (case_id, reporter_id),
  CONSTRAINT fk_report_case     FOREIGN KEY (case_id) REFERENCES report_cases(id) ON DELETE CASCADE,
  CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_report_reason   FOREIGN KEY (reason_id) REFERENCES reason_options(id)
) ENGINE=InnoDB;

CREATE TABLE appeals (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT        NOT NULL,
  target_type    VARCHAR(20)   NOT NULL,
  target_id      BIGINT        NOT NULL,
  content        VARCHAR(1000) NOT NULL,
  status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  decided_by     BIGINT,
  decided_at     DATETIME,
  decision_note  VARCHAR(500),
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_appeal_once (target_type, target_id),
  CONSTRAINT ck_appeal_target CHECK (target_type IN ('REVIEW','BLOG','COMMENT','PRACTICE')),
  CONSTRAINT ck_appeal_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
  CONSTRAINT fk_appeal_user    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_appeal_decider FOREIGN KEY (decided_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- =========================================================
-- 11. Guide
-- =========================================================
CREATE TABLE guide_stages (
  code        VARCHAR(10)  PRIMARY KEY,
  title       VARCHAR(120) NOT NULL,
  icon        VARCHAR(50)  NOT NULL,
  sort_order  INT          NOT NULL,
  CONSTRAINT ck_stage_code CHECK (code IN ('PRE','ARR','LEAVE','HOME'))
) ENGINE=InnoDB;

CREATE TABLE guide_items (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  stage_code  VARCHAR(10)  NOT NULL,
  title       VARCHAR(255) NOT NULL,
  due_note    VARCHAR(80),
  place_id    BIGINT,
  blog_id     BIGINT,
  sort_order  INT          NOT NULL DEFAULT 0,
  created_by  BIGINT,
  updated_by  BIGINT,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_gitem_stage   FOREIGN KEY (stage_code) REFERENCES guide_stages(code),
  CONSTRAINT fk_gitem_place   FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE SET NULL,
  CONSTRAINT fk_gitem_blog    FOREIGN KEY (blog_id) REFERENCES blogs(id) ON DELETE SET NULL,
  CONSTRAINT fk_gitem_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT fk_gitem_updater FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE user_guide_progress (
  user_id  BIGINT   NOT NULL,
  item_id  BIGINT   NOT NULL,
  done_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, item_id),
  CONSTRAINT fk_gprog_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_gprog_item FOREIGN KEY (item_id) REFERENCES guide_items(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =========================================================
-- 12. Notifications and system
-- =========================================================
CREATE TABLE notifications (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  type        VARCHAR(30)  NOT NULL,
  content     VARCHAR(500) NOT NULL,
  ref_type    VARCHAR(20),
  ref_id      BIGINT,
  is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notif_user (user_id, is_read, created_at),
  CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- "key" is a reserved word in MySQL, so the columns are setting_key / setting_value
CREATE TABLE system_settings (
  setting_key    VARCHAR(50)  PRIMARY KEY,
  setting_value  VARCHAR(255) NOT NULL,
  updated_by     BIGINT,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_setting_user FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE activity_logs (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
  actor_id     BIGINT,
  action       VARCHAR(50)   NOT NULL,
  target_type  VARCHAR(20),
  target_id    BIGINT,
  old_value    JSON,           -- state before the change (role, status, setting…)
  new_value    JSON,           -- state after the change
  description  VARCHAR(1000),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_logs_created (created_at),
  INDEX idx_logs_actor (actor_id),
  INDEX idx_logs_target (target_type, target_id),
  CONSTRAINT fk_log_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE api_usage (
  service     VARCHAR(40)   NOT NULL,
  usage_date  DATE          NOT NULL,
  amount      DECIMAL(12,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (service, usage_date)
) ENGINE=InnoDB;

-- =========================================================
-- Views
-- =========================================================
-- Average rating and number of visible reviews of each place
CREATE OR REPLACE VIEW v_place_rating AS
SELECT p.id                     AS place_id,
       COUNT(r.id)              AS review_count,
       ROUND(AVG(r.rating), 1)  AS avg_rating
FROM places p
LEFT JOIN reviews r ON r.place_id = p.id AND r.status = 'PUBLISHED'
GROUP BY p.id;

-- =========================================================
-- Lookup data taken from the prototype
-- =========================================================
INSERT INTO areas (ward, station, display_name, sort_order) VALUES
 ('Arakawa','Nippori','Arakawa – Nippori',1),
 ('Arakawa','Machiya','Arakawa – Machiya',2),
 ('Taito','Ueno','Taito – Ueno',3),
 ('Bunkyo','Sendagi','Bunkyo – Sendagi',4),
 ('Kita','Tabata','Kita – Tabata',5),
 ('Toshima','Ikebukuro','Toshima – Ikebukuro',6),
 ('Shinjuku','Takadanobaba','Shinjuku – Takadanobaba',7);

INSERT INTO tag_types (name, description, color, selfie_only) VALUES
 ('Kouhai','Đang học tại Nhật hoặc mới đến Nhật','#2E7D5B',FALSE),
 ('Mentor','Đã về nước hoặc đang sinh sống tại Nhật','#C2456E',FALSE),
 ('Giáo viên tiếng Nhật','Đang dạy tiếng Nhật','#E8A33C',FALSE),
 ('JLPT N2 trở lên','Có chứng chỉ JLPT N2 hoặc N1','#E8A33C',FALSE),
 ('Người Nhật','Người Nhật dùng Senpai ơi, chủ yếu là người quen của nhóm','#E8A33C',TRUE);

INSERT INTO tag_proof_types (tag_type_id, name)
SELECT t.id, x.proof FROM tag_types t JOIN (
  SELECT 'Kouhai' AS tag, 'Thẻ sinh viên (学生証)' AS proof
  UNION ALL SELECT 'Kouhai', 'Giấy chứng nhận đang học (在学証明書)'
  UNION ALL SELECT 'Mentor', 'Giấy chứng nhận tốt nghiệp (卒業証明書)'
  UNION ALL SELECT 'Mentor', 'Thẻ nhân viên (社員証)'
  UNION ALL SELECT 'Mentor', 'Giấy xác nhận đang làm việc (在職証明書)'
  UNION ALL SELECT 'Giáo viên tiếng Nhật', 'Giấy đăng ký 登録日本語教員'
  UNION ALL SELECT 'Giáo viên tiếng Nhật', 'Chứng nhận đỗ 日本語教育能力検定試験'
  UNION ALL SELECT 'Giáo viên tiếng Nhật', 'Chứng nhận hoàn thành khóa 420 giờ'
  UNION ALL SELECT 'JLPT N2 trở lên', 'Giấy chứng nhận kết quả JLPT'
  UNION ALL SELECT 'Người Nhật', 'Ảnh chân dung (selfie)'
) x ON x.tag = t.name;

INSERT INTO blog_topics (name, is_other, sort_order) VALUES
 ('Kinh nghiệm',FALSE,1),('Cảnh báo lừa đảo',FALSE,2),('Thủ tục giấy tờ',FALSE,3),
 ('Visa và cư trú',FALSE,4),('Nhà ở',FALSE,5),('Việc làm thêm',FALSE,6),('Ăn uống',FALSE,7),
 ('Đi lại',FALSE,8),('Học tiếng Nhật',FALSE,9),('Y tế',FALSE,10),('Mua sắm',FALSE,11),
 ('Ngân hàng và tiền',FALSE,12),('Sau khi về nước',FALSE,13),('Khác',TRUE,99);

INSERT INTO banned_keywords (keyword) VALUES
 ('vay nóng'),('cá độ'),('visa nhanh'),('việc nhẹ lương cao');

INSERT INTO place_categories (id, name_vi, icon, sort_order) VALUES
 ('konbini','Cửa hàng tiện lợi','local_convenience_store',1),
 ('food','Quán ăn','restaurant',2),
 ('station','Ga tàu','train',3),
 ('super','Siêu thị','shopping_cart',4),
 ('ward','Văn phòng quận','account_balance',5),
 ('hospital','Bệnh viện và phòng khám','local_hospital',6),
 ('bank','Bưu điện và ngân hàng','local_post_office',7),
 ('pharmacy','Hiệu thuốc','local_pharmacy',8),
 ('school','Trường học','school',9),
 ('laundry','Tiệm giặt tự động','local_laundry_service',10),
 ('park','Công viên','park',11),
 ('temple','Đền và chùa','temple_buddhist',12),
 ('phone','Cửa hàng điện thoại và SIM','smartphone',13),
 ('housing','Văn phòng bất động sản','home_work',14);

INSERT INTO category_google_types (google_type, category_id) VALUES
 ('convenience_store','konbini'),('supermarket','super'),('home_goods_store','super'),
 ('shopping_mall','super'),('train_station','station'),('hospital','hospital'),
 ('dentist','hospital'),('doctor','hospital'),('pharmacy','pharmacy'),
 ('post_office','bank'),('bank','bank'),('library','ward'),('city_hall','ward'),
 ('school','school'),('restaurant','food'),('laundry','laundry'),('park','park');

INSERT INTO ranks (name, min_points, featured_bonus) VALUES
 ('Người mới',0,0),('Người chỉ đường',100,5),('Senpai tin cậy',300,10);

INSERT INTO guide_stages (code, title, icon, sort_order) VALUES
 ('PRE','Trước khi đi','luggage',1),
 ('ARR','Khi mới đến (tuần đầu đến 3 tháng)','flight_land',2),
 ('LEAVE','Trước khi về nước','flight_takeoff',3),
 ('HOME','Sau khi về nước','home',4);

INSERT INTO reason_options (scope, label, sort_order) VALUES
 ('REPORT','Lừa đảo',1),('REPORT','Sai sự thật',2),('REPORT','Xúc phạm',3),('REPORT','Spam',4),
 ('ACADEMIC_REPORT','Sai tiếng Nhật',1),('ACADEMIC_REPORT','Sai nghĩa',2),
 ('ACADEMIC_REPORT','Xúc phạm',3),('ACADEMIC_REPORT','Spam',4),
 ('PIN_REJECT','Không có thật hoặc sai vị trí',1),('PIN_REJECT','Trùng địa điểm có sẵn',2),
 ('PIN_REJECT','Tên không rõ nghĩa hoặc spam',3),('PIN_REJECT','Review đầu tiên vi phạm',4),
 ('PLACE_REMOVE','Không còn tồn tại',1),('PLACE_REMOVE','Thông tin sai hoặc không có thật',2),
 ('PLACE_REMOVE','Vi phạm (lừa đảo, nội dung xấu)',3),('PLACE_REMOVE','Chủ địa điểm yêu cầu gỡ',4);

INSERT INTO system_settings (setting_key, setting_value) VALUES
 ('report_hide_threshold','5'),('false_report_max','5'),('mute_days','7'),
 ('proof_keep_days','14'),('new_account_days','7'),('posts_per_day','3'),
 ('pins_per_day','2'),('videos_per_day','1'),('closed_report_threshold','5'),
 ('removed_keep_days','30'),('log_keep_days','90'),('max_moderators','3'),
 ('password_min_length','8'),('lock_attempts','5'),('lock_minutes','30'),
 ('verify_token_hours','24'),('reset_token_minutes','30'),
 ('featured_top_n','3'),('featured_min_score','10'),
 ('duplicate_warn_radius_m','200'),('merge_suggest_radius_m','50');
