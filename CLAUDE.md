# ASVSJ – "Senpai ơi"

Assistance System for Vietnamese Students in Japan. Graduation capstone of team SEP490-G130.
A web app that combines an integration map (places reviewed by the Vietnamese community) with an
experience-sharing community (blog, Q&A), a four-stage guide and a small Japanese-phrase feature.
Expected scale 100–200 users; the team has no budget, so prefer free services and free tiers.

## Sources of truth
- **Behaviour and screens:** the clickable prototype, repo https://github.com/baonv228/Prototype_System_Test
  (branch `tienhung`). When a business rule is unclear, check the prototype first.
- **Database:** Flyway migrations in `src/main/resources/db/migration/` (`V1__init_schema.sql` = MySQL 8,
  54 tables) and design notes in `docs/database/database-design.md`. Never edit a pushed migration; add the
  next `V<n>__<description>.sql` instead.
- **Report documents** (table descriptions EN/VI, use case specs, screen specs) are kept in the prototype
  repo under `docs/`, not here. If you change a table, update the schema here and the table dictionary there.
- **Code structure and team rules:** `README.md`.

## Stack
- Spring Boot 3.3.5, Java 17, Maven. Package `com.sep490.vsa`.
- Feature-based packages: `common/` for shared code only, `feature/<name>/` holds that feature's
  Controller, Service, Repository, Entity and DTOs. No database access in controllers.
- Frontend: static HTML/CSS/JS in `src/main/resources/static/`, calling the REST API.
- Database: **MySQL 8.0.16+** (utf8mb4), schema managed by Flyway, `ddl-auto=none` (Hibernate never
  changes tables). Local credentials go in `src/main/resources/application-local.properties` (git-ignored).

## Key business rules (summary)
- Roles: User, Moderator (max 3), Academic Manager, Admin. Guests can only view. Users must be 18+ and
  accept the Terms, Privacy Policy and Community Rules (store the accepted version).
- Sign-in: **email + password** and **Google** (Facebook later). Hash passwords with BCrypt
  (`BCryptPasswordEncoder`), never store or log plain passwords. Email verification and password reset use
  one-time tokens stored as SHA-256 hashes. 5 wrong passwords → lock 30 minutes (Admin settings `lock_attempts` /
  `lock_minutes`).
- Content (reviews, posts, comments) is moderated automatically: keyword filter + image moderation.
  The banned keywords include about 90 prostitution and gambling phrases seeded by `V5__prostitution_gambling_keywords.sql`
  (agreed: only phrases that solicit or advertise, e.g. "kèo nhà cái", "fb88", "nhận bao nuôi", "パパ活"; general words
  such as "cờ bạc", "mại dâm", "cá cược", 賭博, 風俗 stay allowed so warnings and questions can be posted; phrases whose
  unaccented form is an everyday phrase are left out, see the file header; `ContentFilterTest` checks the list against
  ordinary sentences). A post or question waiting for its media scan is checked against the list as it is now before
  its files are scanned and again right before it is published (`BlogPublishService`): a hit → REJECTED without
  scanning, author notified with the keyword.
  Reports: 1–5 → "Cần xử lý"; more than 5 → auto-hidden and "Khẩn". More than 5 false reports → reporting
  locked 7 days. Removed content is kept 30 days for appeals, then purged.
- Posts, questions, comments and answers of LOCKED or DELETED accounts are hidden everywhere Members and Guests read
  content (feed, search, statistics, featured, detail → 404 MSG19, Hỏi-Đáp, "Đã lưu", Home, scam warnings); nothing is
  deleted, and an unlocked account's content shows again. One SQL rule for every read: `feature/blog/ActiveAuthors`.
  A hidden comment with visible replies stays as an empty placeholder; a hidden best answer no longer makes the
  question solved; acting on hidden content answers 404. `comment_count` counts only comments of active accounts:
  `AuthorStatusSync` recounts the posts the account commented on when an Admin locks/unlocks it or the user deletes it
  (post rows locked before the users row, same order as comment actions) and refreshes "Bài nổi bật" after commit.
  Home leaves out their reviews too (guest numbers and "Hữu ích" quotes, member "Review mới"; `ActiveAuthors.of("r")`);
  what else happens to reviews of such accounts is still open.
- New accounts (< 7 days): per day max 3 posts/reviews, 2 pins, 1 video.
- Places: OpenStreetMap places store their `osm_id`, names, address and coordinates (ODbL allows it; credit
  "© OpenStreetMap contributors"); places saved from Google Maps before keep their `google_place_id` (no address or
  coordinates); user pins need Moderator approval; same name within 200 m
  → warning. Moderator removes places softly (with reason, restorable) and merges duplicates (final, no undo).
  5 "đã đóng cửa" reports → Moderator queue; only the Moderator sets the closed label.
- One review per user per place, and per year of an event. Review text ≤ 2,000 chars, ≤ 5 photos
  (≤ 20 MB each), video ≤ 30 s.
- Featured posts are automatic: top 3 of the week/month by likes + 2 × comments by others + rank bonus, min score 10,
  one post per author. No manual featuring. The number (1–3) and the minimum are the Admin settings
  `featured_top_n` / `featured_min_score`, also used by the member Home.

## Auth (feature/auth, common/security)
- Session-based Spring Security (cookie JSESSIONID). Read the signed-in user with
  `@AuthenticationPrincipal AppUserPrincipal me`. `GET /api/**` is public; other `/api/**` calls need login.
- Every request of a signed-in user re-reads `users.role` and `users.status` (`common/security/SessionUserRefreshFilter`,
  right after the security context is loaded; /assets, /components, /media and the favicon are skipped): LOCKED, DELETED
  or a missing row ends the session and the request goes on as a guest (API 401, pages to /login); a changed role is put
  into the session at once (new authority and `homePath`, no new sign-in). `User` has `@DynamicUpdate`, so a JPA flush
  (e.g. last_login_at at a Google login) never writes back a role or status that an Admin changed with SQL meanwhile.
- Password login (`AuthService`): the account is read first, BCrypt runs outside any transaction, and the outcome is
  written with single UPDATE statements (`UserRepository.countFailedLogin` / `lockAfterFailedLogins` / `recordLogin`), so
  wrong passwords sent together are all counted. Wrong passwords during a lock are not counted. An Admin-LOCKED account
  is counted like any other and hears "Tài khoản này đã bị khóa…" only with its right password; a DELETED one answers
  like an unknown email; an account without a password gets the Google/Facebook hint.
- CSRF is on: frontend code must call the API through `assets/js/api/client.js`, which sends the
  `X-XSRF-TOKEN` header. A non-GET call is sent once more with a fresh token only when the 403 is Spring Security's own
  (CSRF rejection: Spring's error body, no `success`); a 403 of the application (MSG18, reporting locked) is never resent.
  `SessionSignIn` clears the `XSRF-TOKEN` cookie at every sign-in (as Spring's own login does), so a token from before
  is worthless; the page gets the new one through that resend. A lost connection becomes an `ApiError` (status 0,
  "Không kết nối được máy chủ…") that pages show like any other error.
- Links and redirects that send a guest to sign in use `loginUrl()` (`lib/ui.js`): `/login?next=<this page>` (the
  header button reads the address when pressed; "Viết bài" / "Đặt câu hỏi" go on to the form). `login.js` follows `next`
  after signing in, and when already signed in, only for role USER and only for a path of this site (starts with one
  "/", no "://", backslash, space or control character, same origin, not /login, /register or /complete-signup); staff
  and anything else go to `homePath`. Google/Facebook sign-in does not carry `next`. Pages Spring Security guards
  (`/profile`, `/saved`) still send a guest to plain `/login`.
- Login and sign-up forms are `method="post"` and their scripts attach the submit handlers before any `await`
  (`currentUser().catch(() => null)`), so an early Enter never puts the email or password in the URL.
- Email sign-up: send-code → verify-code (returns a one-time ticket) → complete (name, password, 18+, terms).
  "complete" sent twice with one ticket (or meeting the email's unique key) answers 409 "Email này đã được đăng ký.
  Hãy đăng nhập." to the second. Without `spring.mail.host` the code is printed in the server console.
  Google/Facebook login turns on only when their client ids are set in `application-local.properties` (see the
  `.example` file); for a LOCKED or DELETED account `SocialLoginService.findOrLink` writes nothing (no login time, no
  new link) and the handler sends it to `/login?error=account_locked`. A social "complete" that meets another new
  account at the email's unique key answers 409 "Email này đã được đăng ký. Hãy đăng nhập.".
- Sign-up limits (`RegistrationService`, `AuthRules`; "cần chốt" ones marked there): per mailbox one code per
  `email_code_resend_seconds` and 10 in 24 h (`AuthRules.mailbox`: "+tag" dropped for every domain, dots too for
  gmail.com/googlemail.com; only for counting, the account keeps the email as typed), 5 codes an hour per client address
  (`request.getRemoteAddr()`, in memory; behind a reverse proxy the server must pass the visitor's address, e.g.
  `server.forward-headers-strategy`), 300 codes in 24 h for the whole site (below the Gmail quota). The checks and the
  row run in a short transaction (one at a time); the email is sent after commit; a failed email deletes its row again
  (counts toward no limit, the code sent before stays valid) and the error goes to the user as before. verify-code locks
  the code row (`FOR UPDATE`), so parallel guesses are all counted. `email_code_minutes`, `email_code_attempts`,
  `email_code_resend_seconds`, `register_ticket_minutes`, `password_min_length` are read on every request;
  `password_min_length` is checked in `RegistrationService.complete` (the page gets it with the verify-code answer) with
  BCrypt's 72-byte limit. Display names are stripped (Unicode spaces too) before validation. SMTP waits at most 5 s
  (`spring.mail.properties.mail.smtp.*timeout`).
- "Xóa tài khoản" (`ProfileService.deleteAccount`) also revokes the user's tags, closes pending tag requests (REJECTED,
  "Tài khoản đã xóa"), discards every tag proof photo and sets `users.points` to 0, in the same transaction (READ
  COMMITTED like an Admin's lock, so the comment recount locks only the posts it changes).

## Roles, homepage, profile
- Page addresses have no ".html" (`common/web/CleanUrls`): `/login`, `/register`, `/terms`, `/profile`, `/saved`,
  `/blog`, `/blog/new`, `/blog/{id}`, `/blog/{id}/edit`, `/qa`, `/qa/ask`, `/qa/{id}`, `/qa/{id}/edit`, `/coming-soon`,
  `/moderator`, `/admin`, `/admin/users`, `/admin/system-config`, `/admin/activity-log`… forward to the files in
  `static/pages/`; old `/pages/*.html` links (`?id=` included) answer 301 to them. Links in pages and JS use the clean
  addresses; a new page gets its entry in `CleanUrls.PAGES`.
- Each role lands on `Role.homePath()`: USER `/`, MODERATOR `/moderator`, ACADEMIC_MANAGER
  `/academic`, ADMIN `/admin` (sample dashboards). SecurityConfig enforces it: guests go to
  login, a wrong role is redirected to its own home; `/api/moderator|academic|admin/**` need the role,
  `/api/me/**` needs login.
- Admin account management (`feature/admin/AdminUserService`, `/api/admin/users/{id}/role|status`): an Admin never
  changes their own role or status; status takes only ACTIVE or LOCKED (DELETED comes only from the user's own "Xóa tài
  khoản"); a DELETED account's role and status cannot be changed (the page hides those buttons).
  `feature/user/ActiveAdminGuard` keeps at least one ACTIVE Admin (count of the other active Admins FOR UPDATE): the last
  one cannot delete their account (`DELETE /api/me`), be moved to another role or be locked (400 "Hệ thống cần ít nhất
  một quản trị viên đang hoạt động."). Other staff may delete their own account.
- Admin pages put API data into HTML only through `esc()` from `lib/ui.js` (`ic()` escapes its icon name too); a row's
  JSON goes into a double-quoted attribute as `data-x="${esc(JSON.stringify(row))}"`. They live at `/admin`,
  `/admin/users`, `/admin/system-config`, `/admin/activity-log` (ADMIN only, old `/pages/admin/*.html` answer 301);
  `assets/js/api/admin-client.js` sends the page to `/login` on a 401.
- Moderator limit (`AdminUserService`): every role or status change first locks the `max_moderators` row of
  `system_settings` (`FOR UPDATE`, READ COMMITTED), so changes run one at a time; promoting to MODERATOR and unlocking a
  MODERATOR are refused when the ACTIVE Moderators already reach the limit (value kept within 1–3). Lock order:
  system_settings → posts (AuthorStatusSync) → users. The dashboard counts no DELETED account and only ACTIVE
  Moderators. Missing user → 404. Lock/unlock reason kept to 500 characters. "Xóa tài khoản" does not wait for that
  lock, so the role/status UPDATE only applies while the row is still as read (not DELETED, same status); otherwise
  400 "Tài khoản này đã bị xóa…".
- Settings (`AdminConfigService.SETTING_METADATA`): only "wired" settings (read by the code) can be changed, each to a
  whole number in its range ("cần chốt" ranges in that table; ASCII digits only, stored as "7" not "007"); the others
  answer 400 "Cài đặt này chưa được áp dụng trong hệ thống." and show "Chưa áp dụng" on the page. A new setting the code
  reads needs its row there (label, range). Banned keywords need 3 letters/digits after `VietnameseText.squash` (2 when
  they contain Han/Hiragana/Katakana, e.g. 詐欺) and may not equal an existing one by
  `VietnameseText.squashKeepingMarks` (spaces and signs dropped, accents kept: "vay-nóng" = "vay nóng" ≠ "vậy nóng").
  The column is UNIQUE under utf8mb4_0900_ai_ci (accents ignored), so MySQL still cannot store "vậy nóng" or
  "vay nong" next to "vay nóng"; they are refused as duplicates before the INSERT.
- Activity log (`ActivityLogService.recordLog` takes maps, written as JSON by Jackson; description cut to 1000).
  Pages ordered by created_at, id. Every day 03:30 Japan time `purgeOldLogs` deletes rows older than `log_keep_days`
  (30–3650) and `feature/auth/EmailCodeCleanup` deletes sign-up codes older than 7 days that are used, replaced or
  expired (a still valid code or ticket stays).
- `feature/home` reads other features' tables (places, reviews, events, blogs, guide, phrases) with plain SQL
  and never writes them, so it does not clash with the entities teammates will create.
- App pages use `assets/css/tokens.css` + `site.css` and `components/site-header.js` (top nav on desktop,
  bottom tab bar on phones). Links to features not built yet go to `/coming-soon?f=<feature>`.
- Personalized feed = the member Home: `GET /api/me/home` returns `forYou` ("Dành cho bạn", up to 5 published
  POST blogs by others; last 30 days first, older ones fill; then linked to a place in the user's area, then a topic
  that suits the user's stage (`HomeService.STAGE_TOPICS`, by topic name), then likes + 2 × comments, then newest).
  Feed preferences = the user's area + stage (`PUT /api/me/profile`); no table of their own.
- "Đã lưu" (`feature/saved`, read only): `GET /api/me/saved` (USER role) lists saved ACTIVE places (a merged place
  shows the place it was merged into), PUBLISHED posts and questions of active accounts, newest saved first; page
  `/saved` (`pages/saved.html` + `assets/js/pages/saved.js`, "Bỏ lưu" uses the existing save APIs). The profile has
  `savedCount` for "Đã lưu (N)" on Tôi. Places are saved from the Map once it is built (`saved_places`).
- Profile `local` runs `DevDataSeeder`: test accounts (password 123456, see README) and
  `dev/sample-data.sql`. Never use profile `local` on the server.

## Blog feed (feature/blog)
- Blog = posts only (`blogs.blog_type = 'POST'`); questions (`'QA'`) live in the same table but belong to Hỏi-Đáp
  (see below). Every blog query, count, statistic, featured snapshot, home featured post and scam warning filters
  `blog_type = 'POST'`.
- `GET /api/blogs` (search, topic filter, sort, paging) and `GET /api/blogs/overview` (featured posts,
  active topics, side statistics). Plain SQL like `feature/home`; search folds accents and case via
  `utf8mb4_0900_ai_ci`. A signed-in author also sees their own `PROCESSING` posts.
- `FeaturedSnapshotService` fills `featured_snapshots` (WEEK = last 7 days, MONTH = last 30) at startup, every hour
  and after an account is locked, unlocked or deleted. Page: `pages/blog.html` + `assets/js/pages/blog.js`; links to Write Post, Map and Public Profile
  go through `ROUTES` at the top of blog.js (Map and Public Profile are coming-soon pages for now). Old
  `blog.html?type=QA` links redirect to `qa.html`.
- Post Detail (UC-10): `GET /api/blogs/{id}` (public) returns the post, media, comment tree and `viewer` flags.
  Actions (signed-in role USER only, never on one's own content for like/report): `POST|DELETE
  /api/blogs/{id}/like|save`, `POST /api/blogs/{id}/comments` (`parentId` for replies), `POST /api/blogs/{id}/report`,
  `DELETE /api/blogs/{id}` (own post: hard delete, its points withdrawn), `POST|DELETE /api/comments/{id}/like`,
  `POST /api/comments/{id}/report`, `DELETE /api/comments/{id}` (own; a comment with replies becomes an empty
  REMOVED placeholder), `PUT /api/comments/{id}` `{content}` (own published comment, reply or answer; same length,
  keyword and link checks as a new one; sets `is_edited`, keeps likes and the best-answer mark, no notification;
  edited in place on the page with "Lưu"/"Hủy", Enter saves, Escape cancels). Page: `/blog/{id}` (file
  `pages/blog-detail.html`).
- `blog-detail.js` keeps what is typed in the comment/answer box and each reply box across redraws (likes, map, reply
  or edit boxes, deletes); a sent box is emptied, "Hủy" on a reply drops that reply only, an open reply box stays open.
  A comment like is stored in the post data. "Sửa" on a post shows only for PUBLISHED, PROCESSING, REJECTED. A 401 on a
  comment, reply, answer, edit or report form stays on the page with a "Đăng nhập" link (`next` = this post) and keeps
  the boxes in this tab's sessionStorage (`asvsj.commentDrafts.<id>`), filled in again once after signing in; other
  actions answering 401 keep them the same way and go to sign in. The map is never built while hidden (Leaflet still
  loading when "Ẩn bản đồ" is pressed).
- New comments, replies and answers ("cần chốt" constants in `BlogActionService`): one per user every 15 s, 20 a Japan
  day for accounts younger than `new_account_days`, the same text (≥ 30 letters/digits after `VietnameseText.squash`)
  on at most 3 other posts in 24 h, replies at most 20 deep (deeper old rows are shown under their ancestor at depth 20).
  Edits are not limited. Actions that add, like, save, choose, report or delete lock the post row first (then the
  comment row) and run READ COMMITTED, so best-answer points and withdrawals happen once and reports and likes on one
  item queue up instead of deadlocking. Deleting a post deletes its
  comments newest first (MySQL stops cascades at 15 levels).
- The services take a `kind` ("POST" / "QA"): `/api/blogs/**` passes POST, `/api/questions/**` passes QA, and an id of
  the other kind answers 404 (MSG19). Only the shared `/api/comments/{id}/...` calls pass null (any kind).
  Notifications about a question use `ref_type = 'QUESTION'` (`BlogActionService.refType`); reports keep `BLOG`
  because of the `report_cases` CHECK constraint.
- Shared helpers used by blog and reusable elsewhere: `feature/report/ContentReportService` (one report per
  user per item, more than `report_hide_threshold` → URGENT and the caller hides the item, reporting lock),
  `feature/moderation/ContentFilter` (banned keywords matched word by word on a normalised copy: accents may be left
  out but a different accented word does not match, "vậy nóng" ≠ "vay nóng", and disguises such as "v.a.y n0ng",
  "vaynong", fullwidth or Cyrillic letters are caught; the list is cached 60 s and `AdminConfigService` clears it when
  a keyword is added or deleted. BR-22: links, VN/JP mobile numbers and Zalo/LINE/Telegram ids only for tagged
  users, landlines and toll-free numbers allowed, no short links for anyone), `feature/user/PointService`,
  `feature/notification/NotificationService`,
  `common/settings/SystemSettings`, `common/text/VietnameseText`. Media URLs = `app.media.public-base-url`
  (default `/media/`) + `media.storage_key`.

## Write Post (UC-11, feature/blog + feature/media)
- Page `pages/blog-write.html` + `assets/js/pages/blog-write.js` (the same script serves `qa-ask.html`, see
  Hỏi-Đáp). API: `GET /api/blogs/compose`
  (topics, limits, today's new-account usage, `canPostLinks`; USER role only), `POST /api/blogs` multipart: part
  `post` (JSON `{title,content,topicIds,placeIds,externalPlaceIds}`), up to 5 `images`, 1 `video` (plain JSON also
  accepted when there are no files). "Địa điểm liên quan" uses `GET /api/places/search` (see Places below).
  `GET /api/blogs/scam-warnings[?placeId=]` lists places with a published "Cảnh báo lừa đảo" post, for the Map "!".
- `BlogWriteService.create` checks in the agreed order and stops at the first failing step: fields/lengths and the
  files (`BlogWriteRules`, outside the DB transaction) → links (`ContentFilter.checkLinks`) → new-account daily
  limit (usage row locked, MSG44, 429) → banned keywords (`ContentFilter.checkKeywords`, MSG36). No media →
  PUBLISHED and +`PointService.BLOG_POST` (10, not confirmed in the SRS yet). Media → PROCESSING;
  `BlogPublishService` scans after commit (and a retry job every 2 min, 20 waiting posts a run in id order after the
  previous run's last one, from the oldest again after a short page, so stuck posts never starve newer ones): all PASSED → PUBLISHED + points,
  any REJECTED → REJECTED, rejected files deleted, author notified (`CONTENT_REJECTED`). `user_daily_usage` counts
  every new post, in Japan dates. Values still "cần chốt" (title 150, QA description optional, photo rules = review
  rules) are constants in `BlogWriteRules`.
- Edit ("Sửa bài" / "Sửa câu hỏi", `BlogWriteService.update`): `GET /api/blogs/{id}/edit` (and `/api/questions/{id}/edit`)
  returns the post, its media and the compose options (author only; PUBLISHED, PROCESSING or REJECTED, else 404);
  `PUT /api/blogs/{id}` (and `/api/questions/{id}`) takes the create fields + `keepMediaIds` (attached media to keep,
  in order) and new files, with the same ordered checks. Edits never count as a new post; only a video added to a
  post that had none counts toward the daily video limit. New files → PROCESSING and a new scan; otherwise a
  published post stays PUBLISHED and a REJECTED/PROCESSING one whose remaining files all passed is published. Sets
  `is_edited` ("· đã sửa"). Points: `BlogPublishService.awardOnce` pays `BLOG_POST` the first time a post is
  published, never twice. Pages `/blog/{id}/edit` and `/qa/{id}/edit` reuse `blog-write.js` (edit mode from the path).
- `feature/media`: `MediaService` re-encodes photos to JPG ≤ 1600 px (EXIF/GPS dropped; the page also shrinks them
  before upload, HEIC only where the browser opens it; at most 2 photos decoded at once on the server, a photo waits
  20 s for a slot then gets "Máy chủ đang xử lý nhiều ảnh…", and each is read subsampled so the decoded pixels
  (bytes per pixel counted, e.g. 8 for 16-bit RGBA) stay ≤ 48 MB; all "cần chốt"), checks MP4/MOV length ≤ 30 s from the file itself
  (`Mp4Duration`, no ffprobe; fragmented MP4 refused; a box size past the end of the file counts as "to the end", so
  crafted 64-bit sizes cannot loop or reach a negative offset, and any parser error is the normal "Không đọc được video"
  message, never a 500). Videos are stored without their metadata, like photos without EXIF: the `udta`, `meta` and
  `uuid` boxes of the file, of `moov` and of each track become zero-filled `free` boxes of the same size
  (`Mp4Duration.stripMetadata`: no location such as iPhone ISO6709 / `©xyz`, no phone model; offsets unchanged, so the
  video plays as before). Files are stored by `LocalMediaStorage` in
  `app.media.local-dir` (default `uploads/`, git-ignored); implement `MediaStorage` for
  Cloudflare R2 later. Deleting a post (UC-13) also deletes its files.
- `/media/<storage_key>` is `MediaController`, checked per file by `MediaAccess` (the folder is never published): to
  anyone only when the file is not deleted, its scan PASSED and it is attached to at least one PUBLISHED post or
  question of an ACTIVE author (`Cache-Control: max-age=86400, public`, 1 day "cần chốt"); its owner (signed in,
  account ACTIVE, read with the file because `/media/` skips the session check) always sees their own non-deleted file
  (`no-store, private`): edit page, a post being scanned, rejected or hidden. Everything else is 404: other people's
  files being scanned or rejected, hidden/removed posts, locked/deleted authors, tag proofs, files attached to nothing,
  keys not in the form "folder/yyyy/MM/uuid.ext" (encoded slashes and ".." are refused earlier, 400, by Spring
  Security's firewall). Content type from `media.mime_type`, `nosniff`, Range requests and 304 work. Only registered
  while `app.media.public-base-url=/media/`; a new kind of attachment (reviews, events) or a Moderator queue adds its
  rule in `MediaAccess`.
- `feature/moderation`: `MediaScanService` + `ImageModerator`. With `app.moderation.openai.api-key` (or env
  `OPENAI_API_KEY`) photos go to OpenAI Moderation `omni-moderation-latest` (free); without a key
  `DevImageModerator` answers `app.moderation.dev-result` (PASS, or FLAG to try the rejected flow). Scanner errors
  keep the post PROCESSING and retry with a growing pause (1 → 60 min). Videos are not frame-scanned (the free
  endpoint reads images only); they pass once type, size and length are checked.
- `BlogWriteFlowTest` runs against its own database `asvsj_test` (created by Flyway on the first run) with the
  local credentials; it mocks the image scanner.

## Hỏi-Đáp (feature/qa)
- Questions are `blogs` rows with `blog_type = 'QA'`; answers are top-level `comments`, replies hang under them.
  No table of its own. `QaController` (`/api/questions`) reuses the blog services with kind QA:
  `GET /api/questions` (`q`, `topicIds`, `status` ALL|UNANSWERED|UNSOLVED|SOLVED, `sort` NEWEST|LIKES|ANSWERS, paging),
  `GET /overview` (topics, counts, popular topics, top answerers of 30 days), `GET /compose`,
  `POST` multipart (part `question`, same checks and limits as Write Post), `GET|DELETE /{id}`,
  `POST|DELETE /{id}/like|save`, `POST /{id}/report`, `POST /{id}/answers` (`parentId` for replies),
  `PUT /{id}/best-answer` `{answerId}` (asker only, a published top-level answer by someone else, +5 points moved
  if changed).
- Solved = the best answer is still PUBLISHED and its writer's account ACTIVE (`QaRepository.SOLVED_SQL`, also used
  by "Đã lưu"), so a best answer hidden by reports, removed or written by a locked/deleted account stops counting, as on
  the question page. Unanswered = no published answer by an active account and not solved.
- Pages: `pages/qa.html` + `assets/js/pages/qa.js` + `assets/css/qa.css` (list, filters in the URL), `qa-ask.html`
  and `qa-detail.html`. Ask and detail share `blog-write.js` / `blog-detail.js` with the Blog pages: the page sets
  `<body data-kind="POST|QA">` and the script picks its API, links and labels from it. Old links
  (`blog-write.html?type=QA`, a question id on `blog-detail.html`) redirect to the Hỏi-Đáp pages.
- Navbar has 8 tabs (Hỏi-Đáp after Blog); on phones the tab bar sizes each tab by its label so all fit at 320 px.

## Places from OpenStreetMap (feature/place)
- Why not Google Maps: Vietnam is a Google Maps Platform prohibited territory (every Places request from a
  Vietnamese IP gets a bare 403 "The caller does not have permission", and the terms forbid distributing a Google
  Maps app there), and the team develops and presents in Vietnam. So the map data is OpenStreetMap: free, no key,
  data may be stored. Google classes were removed; old rows keep `source` GOOGLE and `google_place_id`.
- "Địa điểm liên quan" (Write Post, Đặt câu hỏi) searches with `GET /api/places/search?q=` (signed-in only):
  approved ASVSJ places of any source first (`source` OSM / GOOGLE / TEAM / MODERATOR / USER), then OpenStreetMap
  places in Japan and Vietnam. The answer's `external` says what happened (ON, OFF, SHORT, UNAVAILABLE, BUSY) and
  `externalReason` why it did not answer (LIMITED, BUSY, OSM_ERROR, SSL, TIMEOUT, DNS, NETWORK); the page shows it.
- Search = Photon (`photon.komoot.io/api`, made for search-as-you-type): `lang=en` (romaji/English names), bias on
  Tokyo, `layer=house&layer=other`, `osm_tag=!…` exclusions (streets, bus stops, platforms, entrances, parking…,
  `OsmPlaces.LEFT_OUT`), then only `app.places.regions` (default `jp,vn`). It understands Japanese, English/romaji and
  Vietnamese (for places in Vietnam), not Vietnamese names of Japanese places ("Tháp Tokyo"): the page shows a tip.
  "ga X" / "nhà ga X" is asked among stations (`osm_tag=railway:station…`) and also as typed (unaccented "ga ran" may
  be "gà rán"), stations listed first.
- Same place once: results with the same id, or the same name (accents, case, "Ga"/"Station"/"駅" ignored) within
  60 m (600 m when both are stations, which have a point per line plus a building) are one place; 60 m because chain
  branches ("Lawson") are often 100–200 m apart under the same name. The copy whose `osm_id` ASVSJ has wins, then a
  copy with the same name close to an ASVSJ place. An OSM
  result with an ASVSJ `osm_id`, or with the same name close to an approved ASVSJ place (a team place, a pin, a Google
  place with coordinates; 600 m only station↔ASVSJ station), is shown as that ASVSJ place (every copy is compared before
  the repeats are dropped, so one close twin is enough); one a Moderator removed is
  left out together with its twins.
- An OSM place ASVSJ does not have yet has `id: null`; the page sends its `externalId` ("N772389775": node/way/relation
  + number) as `externalPlaceIds`. `BlogWriteService` calls `PlaceService.resolve` after the field checks (one
  Nominatim `/lookup` request for all of them, outside the transaction) and `PlaceService.save` inside the post
  transaction (when Nominatim cannot be reached, e.g. some Vietnamese networks reset the connection to
  nominatim.openstreetmap.org while Photon works, `OsmPlaces` takes the place from Photon results of the last 3 hours
  and skips Nominatim for 10 minutes): inserted with source OSM, ACTIVE (no Moderator step), `osm_id`, name_vi (name:vi, else the local name in
  Vietnam, else name:en, else the local name), name_ja, name_en, address, lat/lng and the category its main tag maps to
  through `category_osm_tags` (`other` "Địa điểm khác" when none fits), plus a `place_history` CREATED row. A post
  that fails a later check adds no place. A twin of an ASVSJ place (same name close by) links that place instead.
  Map noise in the lookup answer (`OsmPlaces.noise`: streets, boundaries, bus stops…) is dropped unless the search
  offered that id within 3 hours, so hand-made `externalPlaceIds` cannot add such features. Two posts adding the same
  new place at once both link the one row (`BlogWriteRepository.countActivePlaces` reads `FOR SHARE`). New places are
  inserted in `osm_id` order, so two posts adding the same places in opposite order wait instead of deadlocking.
- The page's place search ignores an answer for an older text once the text changes (the list on screen is dimmed,
  `aria-busy`, and Enter cannot pick from it until the new answer arrives).
- Fair use of the free public services: Nominatim at most 1 request a second for the whole server (callers wait up to
  6 s, then BUSY) with a User-Agent naming the app (`app.places.osm.user-agent`); per user 40 map searches a minute
  and 25 new map places a day (in memory); the same text within 10 minutes (case and spaces ignored, accents and kana
  voicing marks kept: "パン" ≠ "バン") and a looked-up place within the hour are answered from memory. `OsmPlaces`
  counts every HTTP request it sends, answered or not, in `api_usage` (`OSM_PHOTON_SEARCH`, `OSM_NOMINATIM_LOOKUP`, Japan
  days) through `ApiUsageCounter`; nothing is counted when no request goes out.
- Implementations of `ExternalPlaces` (`ExternalPlacesConfig`): `DevOsmPlaces` (made-up Tokyo/Hanoi places, ids
  `DEV-…`, works offline) when `app.places.dev-fake=true`, `OsmPlaces` when `app.places.osm.enabled` (default true),
  else none (ASVSJ places only). Tests set `app.places.osm.enabled=false` or mock `ExternalPlaces`: no test calls the
  internet. On Windows the client also trusts the Windows certificate store (antivirus HTTPS scanning).
- Place chips (Blog, Hỏi-Đáp lists and detail) open the place on openstreetmap.org in a new tab (`ui.js osmUrl`: the
  OSM object from `osmId`, else a marker at lat/lng, else a name search). Post / question detail lists the linked
  places, each with "Chỉ đường" = a Google Maps directions URL (`googleDirectionsUrl`, no key, no Maps Platform call;
  opens the Google Maps app on phones), and a small map (Leaflet 1.9.4 from unpkg with SRI, OSM tiles
  `tile.openstreetmap.org`, credit shown) hidden until "Hiện bản đồ" (Leaflet loads then); `PlaceRef` carries
  category, address, lat, lng, osmId. The map element is kept across re-renders (likes, comments).
- Dev sample places: Ga Nippori is the real OSM station N772389775, the other made-up places are TEAM places;
  `DevDataSeeder` moves rows of older checkouts (made-up "ChIJ-dev-…" Google ids) the same way.

## Working notes
- The JDBC URL sets `forceConnectionTimeZoneToSession=true` so MySQL `DEFAULT CURRENT_TIMESTAMP` values are UTC
  like the ones the app writes (before, on a UTC+7 PC new accounts looked 7 hours younger). Add the same flag
  to `DB_URL` on the server.
- The `guide` and `scholarship` starter features still map to tables that do not exist in the schema
  (`guide`, `scholarship`); until rewritten, `GET /api/guides` and `/api/scholarships` answer 404 "Tính năng này sắp ra
  mắt." without touching the database (entities and services kept, unused), and their pages show it as text.
  Scholarships are not part of the agreed scope.
- Keep Vietnamese UI text as in the prototype. Code, identifiers and commit messages in English.
