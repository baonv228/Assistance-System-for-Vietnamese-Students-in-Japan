# ASVSJ – "Senpai ơi"

Assistance System for Vietnamese Students in Japan. Graduation capstone of team SEP490-G130.
A web app that combines an integration map (places reviewed by the Vietnamese community) with an
experience-sharing community (blog, Q&A), a four-stage guide and a small Japanese-phrase feature.
Expected scale 100–200 users; the team has no budget, so prefer free services and free tiers.

## Sources of truth
- **Behaviour and screens:** the clickable prototype, repo https://github.com/baonv228/Prototype_System_Test
  (branch `tienhung`). When a business rule is unclear, check the prototype first.
- **Database:** Flyway migrations in `src/main/resources/db/migration/` (`V1__init_schema.sql` = MySQL 8,
  54 tables; V10 adds `prefectures`) and design notes in `docs/database/database-design.md`. Never edit a pushed migration; add the
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
- Content (reviews, posts, comments) is moderated automatically: keyword filter + image moderation, and the text and
  speech that Gemini reads in photos and videos go through the same keyword and link rules (`feature/moderation`).
  A photo with a QR code counts as a link (BR-22): members without an identity tag cannot post one (read on the server
  when the photo is uploaded; in videos only Gemini sees a code, so without a Gemini key a code in a video is missed).
  The banned keywords include every prostitution and gambling word the team listed: solicitation and ads in
  `V5__prostitution_gambling_keywords.sql` ("kèo nhà cái", "fb88", "nhận bao nuôi", "パパ活"…) and, decided later, the
  general words in `V6__general_prostitution_gambling_keywords.sql` ("mại dâm", "cờ bạc", "cá cược", "nhà cái",
  "casino", "pachinko", 賭博, 風俗…), so a warning or a question naming them is refused too. Left out: "gạ tình", lottery,
  horse racing, "mãi dâm" (see the V6 header). Posts, questions, comments, answers and display names (both sign-up
  pages, `ContentFilter.checkDisplayName`) are checked. Everyday phrases that hold such a word or look like one without
  accents (`ContentFilter.EVERYDAY_PHRASES`: "co bac nao biet" = "có bác nào biết", "bạn gái gọi điện", "con gái bao
  nhiêu tuổi", "支援交流") do not count; one matched only without accents counts only when the whole text is typed
  without accents ("chơi co bac online" in an accented text is refused). `ContentFilterTest` checks the lists against
  ordinary sentences. A post or question waiting for its media scan is checked against the list as it is now before
  its files are scanned and again right before it is published (`BlogPublishService`): a hit → REJECTED without
  scanning, author notified with the keyword. Content published before a keyword was added is not re-checked.
  Reports: 1–5 → "Cần xử lý"; more than 5 → auto-hidden and "Khẩn". More than 5 false reports → reporting
  locked 7 days. Removed content is kept 30 days for appeals, then purged.
- Posts, questions, comments and answers of LOCKED or DELETED accounts are hidden everywhere Members and Guests read
  content (feed, search, statistics, featured, detail → 404 MSG19, Hỏi-Đáp, "Đã lưu", Home, scam warnings); nothing is
  deleted, and an unlocked account's content shows again. One SQL rule for every read: `feature/blog/ActiveAuthors`.
  A hidden comment with visible replies stays as an empty placeholder; a hidden best answer no longer makes the
  question solved; acting on hidden content answers 404. `comment_count` counts only comments of active accounts:
  `AuthorStatusSync` recounts the posts the account commented on when an Admin locks/unlocks it or the user deletes it
  (post rows locked before the users row, same order as comment actions) and refreshes "Bài nổi bật" after commit.
  Home leaves out their reviews too (guest numbers and "Hữu ích" quotes, member "Review mới"; `ActiveAuthors.of("r")`),
  and so do the event pages (reviews, ratings, album) and the Map (place reviews, pin and detail figures, liking or
  reporting them → 404, scam "!" posts; `MapRepository`).
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
  (`/profile`, `/saved`, `/notifications`) still send a guest to plain `/login`.
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
- Passwords (UC-U05 / UC-U06, `feature/auth/PasswordService`, `PasswordController`, `PasswordMail`):
  "Quên mật khẩu?" on /login → `/forgot-password` (the typed email is carried in sessionStorage, never in the address)
  → `POST /api/auth/password/forgot` `{email}` always answers the same message (also as `data`); only for an ACTIVE
  account, at most 1 link a minute and 5 a day, a new link cancels the older ones; the link is a `user_tokens` row
  (RESET_PASSWORD, SHA-256 of the token, `reset_token_minutes` 30) and the email goes out on a background thread
  (`@Async`, so the answer time says nothing). The link is `app.public-url` (env `APP_PUBLIC_URL`, never the Host
  header) + `/reset-password?token=`; the page takes the token out of the address, has `referrer` no-referrer, checks it
  (`POST /api/auth/password/check` → masked email) and sends `POST /api/auth/password/reset` `{token, password,
  confirmPassword}`: 8 (`password_min_length`)–72 characters / 72 bytes, letters and digits, both equal (all problems
  in `fields`); sets the hash, clears the login lock, confirms the email, uses up every link, signs every device out,
  then /login?reset=1. "Đổi mật khẩu" / "Tạo mật khẩu" (account without a password) on Tôi → `PUT /api/me/password`
  `{currentPassword, password, confirmPassword}`: the current one through `AuthService.checkCurrentPassword` (counted
  like a login, 400 on `currentPassword`, 423 when locked), new ≠ current; the session gets a new id, the other devices
  are signed out (`common/security/UserSessions`: sessions per user in memory, filled by `SessionSignIn`, emptied by the
  session listener) and the owner gets an email. **The local profile may have a real SMTP server: a manual test of
  these flows sends real emails** (the seed addresses belong to strangers).
- "Xóa tài khoản" (`ProfileService.deleteAccount`) also revokes the user's tags, closes pending tag requests (REJECTED,
  "Tài khoản đã xóa"), discards every tag proof photo and sets `users.points` to 0, in the same transaction (READ
  COMMITTED like an Admin's lock, so the comment recount locks only the posts it changes).

## Roles, homepage, profile
- Page addresses have no ".html" (`common/web/CleanUrls`): `/login`, `/register`, `/forgot-password`, `/reset-password`, `/terms`, `/profile`, `/saved`, `/notifications`,
  `/blog`, `/blog/new`, `/blog/{id}`, `/blog/{id}/edit`, `/qa`, `/qa/ask`, `/qa/{id}`, `/qa/{id}/edit`, `/coming-soon`,
  `/events`, `/events/{id}`, `/events/{id}/review`, `/moderator`, `/moderator/events`, `/moderator/pins`, `/moderator/closed`, `/moderator/places`, `/moderator/merge`, `/moderator/tags`, `/tag-request`, `/admin`, `/admin/users`, `/admin/system-config`, `/admin/activity-log`… forward to the files in
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
  POST blogs by others; last 30 days first, older ones fill; then linked to a place in the user's area ("Gần khu vực
  của bạn"), then in its prefecture ("Cùng tỉnh với bạn"), then a topic that suits the user's stage
  (`HomeService.STAGE_TOPICS`, by topic name), then likes + 2 × comments, then newest). "Review mới" orders the same
  way (area, prefecture, rest). Feed preferences = the user's area + stage (`PUT /api/me/profile`); no table of their own.
- Living areas (V10, `feature/area`: `AreaService`, `AreaController`, `AreaDtos`): all of Japan, prefecture
  (`prefectures`, 47) → municipality, the 20 designated cities split into wards (`areas`, 1,892 rows: `code` = 6-digit
  official code, `prefecture_code`, `name_ja`/`name_kana`/`name_en`, `lat`/`lng` representative point, `display_name`
  "Arakawa-ku, Tokyo · 東京都荒川区"; no station). Data = Geolonia 住所データ (japanese-addresses-v2 `api/ja.json`, CC BY
  4.0, from デジタル庁 アドレス・ベース・レジストリ). Users and places keep `area_id`; the seven old sample areas moved to
  their Tokyo ward. Public GETs: `/api/areas` (prefectures + `devLocation`), `/api/areas/prefectures/{code}`,
  `/api/areas/search?q=` (romaji, kanji, kana; "-" = space; 12, names starting with it first), `/api/areas/nearest?lat=
  &lng=` (nearest representative point; outside Japan 400 `NOT_IN_JAPAN`; may give the neighbour near a border, the user
  confirms), `/api/areas/{id}`. Picker `components/area-picker.js` (Tôi and the Home prompt): prefecture + area selects,
  quick search, "Chọn trên bản đồ" (click, or "Vị trí của tôi" through the browser's permission prompt /
  `app.map.dev-location`); only the area id is saved, the point never is. Label by stage: PRE "Khu vực bạn sắp đến",
  HOME "Khu vực bạn từng sống", else "Khu vực bạn đang sống". Not required: the Home keeps a gentle prompt ("Mong bạn
  cho Senpai ơi biết khu vực…") until area and stage are set or "Để sau". New places in Japan get the area nearest to
  their position (`AreaService.assignPlace`: OSM places, pins; Moderator create/update through `areaIdAt`, only when the
  place is moved); places in Vietnam have none.
- Points (UC-U10): the Tôi card shows the total, the rank badge, progress, ranks with perks and the point rules;
  "Lịch sử điểm" = `GET /api/me/points?page=` (`feature/user/PointHistoryService`, 20 a page, newest first: delta, reason
  label, the content's title and a link only while it can still be seen; plain SQL over `point_transactions`). The
  points on the member Home link to `/profile#points`. `PointService.add` keeps `users.points` ≥ 0 (`GREATEST`), the
  history keeps every point taken. Sample data sets `users.points` without history lines ("Chưa có lần cộng hoặc trừ
  điểm nào được ghi lại."). Rank badges next to names elsewhere (reviews, posts, comments) are not done yet.
- Identity tags (UC-U09, UC-M01, UC-M02; `feature/tag`: `TagService`, `TagRepository`, `TagController`, `TagDtos`).
  "Xin tag định danh" on Tôi → page `/tag-request` (`tag-request.js`): `GET /api/me/tag-requests/compose` (active tags
  with description, colour, proof types, `selfieOnly`, `owned`; the waiting request; keep days) and `POST
  /api/me/tag-requests` multipart (part "request" `{tagId, proofTypeId, consent}` + "photo"; USER role; one waiting
  request per user, users row locked; held tags refused; a selfie-only tag takes its single proof type; messages "Hãy
  chọn một tag.", "Hãy chọn loại minh chứng.", "Hãy chọn ảnh minh chứng." / "Hãy chọn ảnh chân dung.", "Bạn cần đồng ý
  cho Kiểm duyệt viên xem ảnh."). The photo is re-encoded (EXIF dropped), stored under `tagproof/`, **never sent to the
  image scanner** (scan_status stays PROCESSING) and never public (`MediaAccess` gives tag proofs to nobody but their
  owner). Moderator page `/moderator/tags` (`mod-tags.js`): `GET /api/moderator/tag-requests?tag=&q=&page=` (PENDING of
  ACTIVE users, oldest first, 10 a page, `daysLeft`), `GET …/{id}`, `GET …/{id}/photo` (bytes only while PENDING,
  `no-store, private`, inline, nosniff; the page blocks the context menu), `POST …/{id}/approve` (user_tags granted or
  re-granted, notification "Bạn đã được cấp tag X.") and `POST …/{id}/reject` `{reason}` (one of `TagService.REJECT_REASONS`;
  "Yêu cầu tag X bị từ chối: lý do. Bạn có thể gửi lại."). Either way the photo is discarded at once and the action logged
  (target USER). `expireOld` (hourly at :15) closes requests older than `proof_keep_days` (14) as EXPIRED, deletes the
  photo and tells the user. A request handled meanwhile → 409 "Yêu cầu này đã được xử lý.".
- "Đã lưu" (`feature/saved`, read only): `GET /api/me/saved` (USER role) lists saved ACTIVE places (a merged place
  shows the place it was merged into), PUBLISHED posts and questions of active accounts, newest saved first; page
  `/saved` (`pages/saved.html` + `assets/js/pages/saved.js`, "Bỏ lưu" uses the existing save APIs). The profile has
  `savedCount` for "Đã lưu (N)" on Tôi. Places are saved from the Map once it is built (`saved_places`).
- Notifications (`feature/notification`, as in the prototype): `NotificationService.send` writes them (CONTENT_PUBLISHED,
  CONTENT_REJECTED, NEW_COMMENT, BEST_ANSWER); `/api/me/notifications` (any signed-in role, own rows only): `GET ?page=`
  (20, newest first, `link` computed from the post's `blog_type`, the review's event or place; null when it is gone),
  `GET /summary?after=` (unread, latestId, `processing` = own blogs + reviews still PROCESSING, `fresh` = up to 5
  after that id, one read-only snapshot), `POST /read {upToId}`. The header (`site-header.js`) shows a bell with the
  unread count, asks the summary every 5 s while `processing` > 0 (else every 60 s, never while the tab is hidden),
  pops up the scan results (`.site-notices`, "Xem" opens the link) and fires `asvsj:notification`, which reloads the post
  (`blog-detail.js`), event (`event-detail.js`) or place panel (`map.js`) still shown as PROCESSING; the Map, which
  stays on its page after sending a review or pin with media, calls `checkNotificationsSoon()`. The newest id a tab
  handled is kept in sessionStorage (`asvsj.notifications.seen.<userId>`). Page `/notifications` (guarded like
  `/saved`) lists them and marks the shown ones read.
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
  users, landlines and toll-free numbers allowed, no short links for anyone; a photo holding a QR code counts as a
  link wherever the code stands: `checkQrCodes` refuses it at once, under "images", for a member without an identity
  tag ("Ảnh thứ N bạn vừa chọn có mã QR…") and for everyone when the code leads to a short link, `checkQrKeywords` when
  it holds a banned keyword; posts, questions, edits, place reviews, pins and event reviews all run them),
  `feature/user/PointService`,
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
  files (`BlogWriteRules`, outside the DB transaction) → links (`ContentFilter.checkLinks`, then `checkQrCodes` for
  the QR codes of the photos) → new-account daily limit (usage row locked, MSG44, 429) → banned keywords
  (`ContentFilter.checkKeywords`, MSG36, then `checkQrKeywords`). No media →
  PUBLISHED and +`PointService.BLOG_POST` (10, not confirmed in the SRS yet). Media → PROCESSING;
  `BlogPublishService` scans after commit (and a retry job every 2 min, 20 waiting posts a run in id order after the
  previous run's last one, from the oldest again after a short page, so stuck posts never starve newer ones): all PASSED → PUBLISHED + points,
  author notified (`CONTENT_PUBLISHED`: "Bài viết “T” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.", "Bản sửa
  bài viết…" without points when it was published before), any REJECTED → REJECTED, rejected files deleted, author
  notified (`CONTENT_REJECTED`, with the reason); nothing while it waits. `user_daily_usage` counts
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
  (bytes per pixel counted, e.g. 8 for 16-bit RGBA) stay ≤ 48 MB; all "cần chốt"; the QR codes of the resized photo
  are read on the server with ZXing, `com.google.zxing:core`, `QrCodes`, into `Prepared.qrCodes`, no outside service:
  only codes ZXing can actually read count, several per photo and at an angle too; the search is bounded, 1000 possible
  corner squares or 3 s, because a photo crafted as a grid of corner squares kept ZXing's multi-code finder busy for
  minutes or out of memory: such a photo is refused with `MediaService.QR_UNCHECKED`; a new member upload path must call
  `ContentFilter.checkQrCodes`, photos kept on an edit are not read again), checks MP4/MOV length ≤ 30 s from the file itself
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
  question of an ACTIVE author, or to at least one PUBLISHED review (`review_media`) of an ACTIVE place by an ACTIVE
  author (`Cache-Control: max-age=86400, public`, 1 day "cần chốt"); its owner (signed in,
  account ACTIVE, read with the file because `/media/` skips the session check) always sees their own non-deleted file
  (`no-store, private`): edit page, a post or review being scanned, rejected or hidden, the first review of their
  pending pin. Everything else is 404: other people's files being scanned or rejected, hidden/removed posts and
  reviews, reviews of pending/removed/rejected/merged places, locked/deleted authors, tag proofs, files attached to
  nothing, keys not in the form "folder/yyyy/MM/uuid.ext" (encoded slashes and ".." are refused earlier, 400, by Spring
  Security's firewall). Content type from `media.mime_type`, `nosniff`, Range requests and 304 work. Only registered
  while `app.media.public-base-url=/media/`. Also public: photos/videos of a PUBLISHED review of an ACTIVE author whose
  event is not deleted, and the cover of an event that is not deleted. A Moderator sees PASSED files of a pending pin's review ("Ghim mới"). A Moderator queue adds its rule in
  `MediaAccess`.
- `feature/moderation`: `MediaScanService` + `ImageModerator` + `MediaReader`. Photos first go to OpenAI Moderation
  `omni-moderation-latest` (free; `app.moderation.openai.api-key` or env `OPENAI_API_KEY`; without a key
  `DevImageModerator` answers `app.moderation.dev-result`, PASS or FLAG). Then photos and videos go to
  `GeminiMediaReader` (`app.moderation.gemini.api-key` or env `GEMINI_API_KEY`, free tier of Google AI Studio, model
  `app.moderation.gemini.model` = `gemini-3.5-flash-lite`; REST `generateContent` v1beta with a JSON schema, no SDK;
  files over 5 MB go through the Files API and are deleted after the reading; videos read at MEDIA_RESOLUTION_HIGH).
  It answers `messageText` (captions, screenshot content without the phone/browser interface, a card or note held up to
  the camera), `sceneText` (signs, posters and menus where they hang: stored, never checked, because of パチンコ signs),
  `speech` (no song lyrics or background sound), `qrCode` (any QR code it can see, however small: the second layer
  after ZXing for photos, the only one for videos) and flags (SEXUAL, MINOR_SEXUAL, GRAPHIC_VIOLENCE, SELF_HARM,
  GAMBLING_PROMOTION, SEX_SOLICITATION, DRUG_SALE); ID, bank and card numbers come back as "[số]". A flag or Google's
  own refusal → REJECTED; then the banned keywords line by line and BR-22 (`ContentFilter.linkProblem`) on messageText
  and speech for the file's owner; a QR code counts as a link. The Vietnamese reason (naming the keyword,
  link or number) is stored in `moderation_scans.detail` (`reason`, engine GEMINI, V9) and put into the rejection
  notification (`MediaScanService.rejectionReason`); pages still show the generic text. A photo OpenAI passed is not
  sent again when only Gemini failed. Google answered but cannot read the file (a song, too much text, a file it could
  not process: UNREADABLE) → tried again, refused the third time. PASS rows keep only text lengths; FLAG rows keep the
  texts and the reason, both removed after 30 days (`forgetOldTexts`, 03:40 Japan time). At most `app.moderation.gemini.requests-per-minute`
  (10) requests a minute for the server, and no request for 2 minutes after Google failed (BUSY: no row, the next run
  tries again); other errors keep the post PROCESSING and retry with a growing pause (1 → 60 min). Requests are counted
  in `api_usage` (`GEMINI_GENERATE`, `GEMINI_FILE_UPLOAD`). Without a Gemini key the step is skipped (videos pass once
  type, size and length are checked, as before, so a QR code in a video is not caught) or `DevMediaReader` answers
  `app.moderation.dev-media-text` (e.g. fb88,
  to try the rejection). Free tier: Google may use what is sent to improve its products, and its reviewers may read it.
- `BlogWriteFlowTest` runs against its own database `asvsj_test` (created by Flyway on the first run) with the
  local credentials; it mocks the image scanner. No test calls Gemini: `src/test/resources/config/application-local.properties`
  blanks the key for every test, `MediaScanFlowTest` mocks the `MediaReader`, `GeminiMediaReaderTest` uses a fake server.

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
  the question page. Unanswered = no published answer by an active account and not solved; the list card's
  "Chưa ai trả lời" badge reads the same rule (`unanswered`), not `answerCount` (which counts replies too).
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
  internet. On Windows the client also trusts the Windows certificate store (antivirus HTTPS scanning;
  `common/http/WindowsTrust`, also used by the OpenAI and Gemini clients).
- Place chips (Blog, Hỏi-Đáp lists and detail) open the place on openstreetmap.org in a new tab (`ui.js osmUrl`: the
  OSM object from `osmId`, else a marker at lat/lng, else a name search). Post / question detail lists the linked
  places, each with "Chỉ đường" = a Google Maps directions URL (`googleDirectionsUrl`, no key, no Maps Platform call;
  opens the Google Maps app on phones), and a small map (Leaflet 1.9.4 from unpkg with SRI, OSM tiles
  `tile.openstreetmap.org`, credit shown) hidden until "Hiện bản đồ" (Leaflet loads then); `PlaceRef` carries
  category, address, lat, lng, osmId. The map element is kept across re-renders (likes, comments).
- Dev sample places: Ga Nippori is the real OSM station N772389775, the other made-up places are TEAM places;
  `DevDataSeeder` moves rows of older checkouts (made-up "ChIJ-dev-…" Google ids) the same way.

## Lễ hội và sự kiện (feature/event)
- Events are posted by Moderators only (UC-M07, page `/moderator/events`, `ModeratorEventController` under
  `/api/moderator/events`): title, dates, place name, optional position (picked on a Leaflet map or from an ASVSJ place
  with coordinates, `GET /api/moderator/events/places?q=`), cost, "Mẹo cho người đi" and an optional cover photo
  (multipart part `event` + `cover`; re-encoded like any photo, marked PASSED without the scanner and without the QR
  rule because staff upload it: real event posters carry official QR codes). Empty place name → "Vị trí trên bản đồ" / "Chưa gắn vị trí", empty cost → "Chưa rõ". Delete is soft
  (`deleted_at`): the event, its reviews and their photos are shown nowhere. Create/update/delete go to the activity log
  (`EVENT_CREATED|UPDATED|DELETED`, target EVENT). The answer says whether the pin is on the map yet (30-day window).
- Public: `GET /api/events?q=` (soonest first, accents ignored; the page groups Đang diễn ra / Trong 30 ngày tới / Sau
  này / Đã diễn ra), `GET /api/events/pins?days=15|30` (located events happening now or starting within the window, for
  the Map once it is built), `GET /api/events/{id}` (album of review photos, Moderator tip, reviews, viewer flags).
  State and days are Japan dates. Pages `/events` and `/events/{id}` (`pages/events.html`, `event-detail.html`); events
  without a cover get a drawing from the prototype (`lib/fest-art.js`, picked by words in the title).
- Event reviews (`EventReviewService`, `reviews` rows with `event_id` + `event_year` = year of the start date): Members
  only, once the event has started, one per user per year (409 otherwise). Page `/events/{id}/review` writes the
  review of this year or edits it. Same order of checks as Write Post: stars 1–5 + text ≤ 2,000 + files (≤ 5 photos,
  1 video ≤ 30 s) → links (BR-22) → new-account limit (counted with posts in `user_daily_usage`) → banned keywords.
  No files → PUBLISHED and `PointService.REVIEW` (+10, reason REVIEW_CREATED, paid once); files → PROCESSING and
  `EventReviewPublishService` scans them like `BlogPublishService` (keyword check again, retry job, notifications
  `CONTENT_PUBLISHED` / `CONTENT_REJECTED` with ref_type REVIEW). Calls under `/api/event-reviews/{id}` (PUT edit with `keepMediaIds`, DELETE
  own: hard delete, points withdrawn, files discarded, open report cases closed; `like`; `report` → HIDDEN past
  `report_hide_threshold`), so place reviews can take `/api/reviews`. Reviews, ratings and album photos of LOCKED or
  DELETED accounts are hidden (`ActiveAuthors.of("r")`); the author still sees their own PROCESSING/REJECTED/HIDDEN one.
- Shared front-end pieces made for it: `lib/map.js` (Leaflet loader, also used by blog-detail), `lib/media-files.js`
  (photo shrinking, video length, also used by blog-write), `components/staff-desk.js` (staff top bar + side menu;
  a module with an `href` links to its built page, the others show "Đang phát triển").

## Map page (feature/place: MapController, MapService, MapRepository, MapDtos) – Tiến owns the Map
- UC ids are those of Tiến's spec (UC-U14 Browse Locations on Map, UC-U15 View Location Details, …). Page `/map`
  (`pages/map.html`, `assets/js/pages/map.js`, `assets/css/map.css`); `/map?place={id}` opens a place, `/map?q=` searches.
  Leaflet 1.9.4 + leaflet.markercluster 1.5.3 from unpkg with SRI. Map style "Gọn" (default) = OpenFreeMap "Liberty"
  vector map through MapLibre GL 4.7.1 + @maplibre/maplibre-gl-leaflet 0.1.4 (free, no key; loaded only for it; falls back
  to "Chi tiết" = OSM raster tiles when WebGL/CDN fails); the layers button switches, the choice is kept in localStorage.
  CARTO basemaps were tried and need an API key in browsers. Declare module-level `let`s above `await start()` (TDZ).
- APIs (GET public): `GET /api/map` (categories with counts, every ACTIVE place with a position plus the viewer's own
  PENDING pins, review count and average, scam "!" flag, map centre = the representative point of the user's area,
  else Tokyo), `/api/map/events?days=15|30`, `/api/map/search?q=` (categories matching at a word start, then places; guests
  get Senpai ơi places only and `external: LOGIN`, signed-in people go through `PlaceService.search`),
  `/api/map/places/{id}` (a MERGED place answers with its target and `mergedFrom`; REMOVED/REJECTED and other people's
  PENDING → 404 "Địa điểm này không còn trên Senpai ơi."), `/places/{id}/reviews?page=` (10 per page, newest first,
  PUBLISHED plus the viewer's own PROCESSING), `/places/{id}/phrases`. Signed-in Users only: POST/DELETE
  `/api/map/places/{id}/save`, POST/DELETE `/api/reviews/{id}/like` (not one's own; like_count recounted),
  POST `/api/reviews/{id}/report` (ContentReportService target REVIEW; over the threshold the review becomes HIDDEN).
- Tools as in the prototype: one card (search "Tìm địa điểm" + "Danh mục" dropdown with "Tất cả danh mục" and
  counts), then "Sự kiện: Tắt / 15 ngày tới / 30 ngày tới" (lantern pins labelled "Còn N ngày" / "Đang diễn ra";
  their popup links "Xem sự kiện" to `/events/{id}`).
  Legend open on desktop, folded while the panel is open and on phones.
- Shops drawn by the "Gọn" style are clickable (MapLibre layers poi_r1/poi_r7/poi_r20/poi_transit, bus stops
  ignored): OpenFreeMap is built by Planetiler, feature id = OSM id × 10 + 1 node / 2 way / 3 relation (checked against
  Nominatim). A shop Senpai ơi already has (same `osmId` in `/api/map` pins, or same name within 80 m / 600 m between
  stations) opens that place; others open the "chưa có trên Senpai ơi" panel with "Viết review đầu tiên".
- Click anywhere at zoom ≥ 15 → "Địa điểm quanh đây": in "Gọn", the POIs the map draws within ~120 px plus Senpai ơi
  places within 150 m (client only, guests too); in "Chi tiết", `GET /api/map/nearby?lat=&lng=` (signed-in;
  `PlaceService.nearby`, counted like a search) = Senpai ơi places within 150 m + Photon reverse, else Nominatim reverse.
- OSM search robustness (`OsmPlaces`): Photon is waited for 4 s only; on failure or slowness Nominatim search/reverse
  answers and Photon is skipped for 2 minutes (the public Photon server is sometimes overloaded, 10–20 s). Kind words
  (`PlaceWords`: "công viên", "chùa", "bệnh viện", "siêu thị", "quán ăn", "trường"…, also English "park"…) turn
  "công viên Ueno" into "Ueno" among `leisure:park` (Photon `osm_tag`, results filtered by tag on the Nominatim
  fallback) plus the English text "Ueno park". `ExternalPlaces` gained `searchTagged` and `nearby` (default methods).
- Pins: bright (round, big, review count) when ACTIVE with ≥ 1 published review and not closed; faded otherwise;
  community pins (source USER) square; pending dashed; closed grey; lantern pins for events. OSM results of a search
  get temporary dark pins and a details panel without an id ("chưa có trên Senpai ơi"); such a place is stored only when
  someone reviews, saves or links it. `PlaceOption` (search result) now also carries lat/lng.
- Login accepts `?next=/path` (same-site paths only, Users only) so the Map can send guests to sign in and back.
- Reviews (feature/review, UC-U16..18): `GET /api/reviews/compose?placeId=` (limits, new-account quota, `myReviewId`),
  `POST /api/reviews` (multipart part "review" `{placeId | externalPlaceId, rating, content}` + ≤ 5 "images" + 1
  "video", or plain JSON), `GET /api/reviews/{id}/edit`, `PUT /api/reviews/{id}` (`{rating, content, keepMediaIds}`),
  `DELETE /api/reviews/{id}`. One review per user per place (409), stars 1–5, text 10–2,000, same checks and order as
  Write Post (links, new-account limit shared with posts in `user_daily_usage`, banned keywords). An
  `externalPlaceId` goes through `PlaceService.resolve/save` (the OSM place is added). Without media → PUBLISHED and
  +10 (`PointService.REVIEW`, reason REVIEW_CREATED, paid once); with media → PROCESSING until
  `ReviewPublishService` (async + retry job, like BlogPublishService; place reviews only, `event_id IS NULL`, event
  reviews have their own job) publishes or rejects it and notifies the author (`CONTENT_PUBLISHED` /
  `CONTENT_REJECTED`; the first review of a PENDING pin: "…sẽ hiển thị cho mọi người khi ghim được Moderator duyệt"),
  checking the banned keywords again before scanning and right before publishing (a hit → REJECTED, author told the
  keyword); an edit with new media hides it again until the scan passes. Delete = hard delete, points withdrawn, report
  cases closed, files discarded.
  The form is a panel mode ("write") of map.js; pins are reloaded after a save.
- "Cắm ghim" (UC-U19, feature/review PinService/PinController): `GET /api/map/pins/compose`, `GET
  /api/map/pins/duplicates?lat=&lng=&names=a|b` (ACTIVE places within 200 m whose squashed name contains / is contained
  in a pin name), `POST /api/map/pins` (part "pin" `{lat,lng,nameJa,nameEn,nameVi,categoryId,address,rating,content,
  confirmDuplicate}` + ≤ 5 "images"). ≥ 1 of nameJa/nameEn; the first review follows the review rules (+10 when
  published); duplicates without `confirmDuplicate` → 409; new accounts `pins_per_day` (2) in `user_daily_usage.pins`.
  Saved as places source USER, status PENDING (+ place_history CREATED via PIN); only its author sees it; its first
  review can be edited but not deleted while PENDING. UI: "Cắm ghim" → pick mode → panel "pin" with a draggable pin (a
  map click moves it), "Có phải là một trong các nơi này?" (OSM/Senpai ơi places within 60 m → write a review there
  instead, text carried over) and the "Có thể trùng" box ("Viết review cho nơi này" / "Vẫn gửi ghim mới").
- "Báo đã đóng cửa" (UC-U20): `POST /api/map/places/{id}/closed-report` → `{count, threshold, queued}`; one row per
  user and place (`place_closed_reports`, 409 on a second), ACTIVE and not closed places only; at
  `closed_report_threshold` (5) the place waits for a Moderator, the label is never set automatically.
- Moderator place pages (feature/place `ModeratorPlaceController`, `PinModerationService`, `PlaceModerationRepository`,
  `PlaceModerationDtos`; pages use `components/staff-desk.js`, `lib/mod-place-ui.js` toast + small Leaflet map,
  `assets/css/mod-places.css`). Every action locks the place row (`FOR UPDATE`), writes `place_history`, the activity
  log (target PLACE) and, for a community pin, a notification; a place another Moderator just handled → 409.
  - "Ghim mới" `/moderator/pins` (UC-M05, UC-M06): `GET /api/moderator/pins?category=&duplicates=` (PENDING USER pins,
    oldest first, approved places within 200 m, "Có thể trùng" = a squashed name contains / is contained in another),
    `GET /api/moderator/pins/{id}` (names, first review with its PASSED photos, places within 200 m with distance and
    review count, PIN_REJECT reasons, categories, `scanning`), `POST …/approve` `{nameVi, categoryId}` (ACTIVE, +15
    `PointService.PIN_APPROVED` reason PIN_APPROVED ref PLACE, notification "Ghim “…” đã được duyệt. +15 điểm."; 409
    while the first review's files are still scanned), `POST …/reject` `{reasonId}` (REJECTED + `reject_reason`, the
    first review deleted like its author would: report cases closed, points withdrawn, files discarded), `POST …/merge`
    `{targetId}` (an ACTIVE place within 200 m; the first review moves there unless the author already has one there, then
    it is deleted; pin MERGED + `merged_into_id`, saves moved, `place_merges` row, MERGED history on both).
    `MediaAccess` lets an ACTIVE Moderator see PASSED files of a review of a PENDING place (audience STAFF, no-store).
  - "Báo đóng cửa" `/moderator/closed` (UC-M04 AF4/AF5): `GET /api/moderator/closed` (`waiting` = ACTIVE open places with
    ≥ `closed_report_threshold` unresolved reports, most first; `closed` = labelled places), `PUT
    /api/moderator/places/{id}/closed` `{closed}` (true: label + reports kept as resolved, CLOSED history; false: label
    off, reports deleted so people can report again, REOPENED history), `DELETE /api/moderator/places/{id}/closed-reports`
    ("Vẫn mở cửa, xóa các báo cáo").
  - "Địa điểm" `/moderator/places` (UC-M07, UC-M08; `PlaceAdminService`, page `mod-places.js`, `?id=` opens a place,
    `?new=1` "Thêm địa điểm"): `GET /api/moderator/places?q=&category=&source=&status=&prefecture=&page=` (10 a page,
    newest first, `q` over the names and address with accents ignored; source MODERATOR also lists TEAM; `prefectures`
    for the filter), `GET …/{id}` (details, counts, history as ready text "Sửa thông tin · Danh mục: Quán ăn → Siêu
    thị", categories, PLACE_REMOVE reasons), `GET /api/moderator/places/osm-search?q=` (= `PlaceService.search`: Senpai ơi places with an id → "Mở",
    OSM places → "Chọn"), `GET …/osm/{externalId}` (`PlaceService.resolve`: `existingId`, or a filled-in form with the
    category from `category_osm_tags`), `POST /api/moderator/places` and `PUT …/{id}` `{osmId?, nameVi, nameJa, nameEn,
    address, categoryId, lat, lng, confirmDuplicate}` (the area follows the position; required: name "Hãy nhập tên hiển thị.", category,
    position "Hãy bấm lên bản đồ để đặt vị trí."; a similar ACTIVE name within 200 m answers `duplicates` without
    `placeId` until `confirmDuplicate`; new places are ACTIVE at once, source OSM with `osm_id` or MODERATOR; an OSM id
    Senpai ơi has answers that place; edits keep the OSM id, never write to OpenStreetMap, history `{changes: [{field,
    from, to}]}`, "Không có thay đổi nào." when nothing changed, pin authors notified), `POST …/{id}/remove`
    `{reasonId, note}` (ACTIVE → REMOVED, `removed_reason` = "reason: note", hidden everywhere, nothing deleted) and
    `POST …/{id}/restore`. The closed label uses `PUT …/{id}/closed`.
  - "Gộp trùng lặp" `/moderator/merge` (UC-M09; `PlaceMergeService`, `PlaceMergeRepository`, page `mod-merge.js`,
    `?a=&b=` compares, `?a=` = "Gộp với địa điểm khác" from a place): the queue is worked out on request (no table, so
    it is always current): ACTIVE places ≤ 200 m apart with similar squashed names, not in `duplicate_dismissals`;
    HIGH ("Cao") = same category, ≤ 50 m and the same Japanese or English name, else MEDIUM; HIGH first, then distance,
    10 a page (`GET /api/moderator/merges?page=`). `POST /api/moderator/merges/dismiss` `{aId, bId}` = "Không trùng".
    `GET …/merges/compare?a=&b=` (both sides, counts of reviews/saves/posts/guide items, people with a review on both),
    `GET …/merges/search?q=&near=` (every word must match, nearest first). `POST /api/moderator/merges` `{keptId,
    mergedId, fields: {nameVi|nameJa|nameEn|address|categoryId|areaId|position: KEPT|MERGED}, confirmed}` (400 without
    `confirmed`): both rows locked lower id first; a person with a review on both keeps the newer one (no points taken);
    reviews, saves, post links, guide items and open closed reports move; places merged into the merged one are pointed
    at the kept one (links follow one step); the merged place becomes MERGED and keeps its `osm_id` (OSM search then
    shows the kept place); `place_merges` row, MERGED history on both, pin authors notified. Final, no undo.
    The settings `duplicate_warn_radius_m` / `merge_suggest_radius_m` are still "Chưa áp dụng": 200 m / 50 m are
    constants (`PlaceMergeService`, `PinModerationService`).
- `app.map.dev-location=lat,lng,label` (application-local only) makes "Vị trí của tôi" return a fixed point, for testing
  from outside Japan; `/api/map` sends it as `devLocation`.

## Working notes
- `spring.flyway.out-of-order=true`: migrations are written on several branches at once (V9 here while the HandBook
  branch has V7/V8), so a database that already ran a higher version still applies a lower one merged later.
- Live updates (`common/live`): `GET /api/me/live` (any signed-in role) is a Server-Sent Events stream (`LiveEvents`,
  in memory, so one server only, "cần chốt" for several): `LiveEvents.publish(userId, type, data)` sends after the commit
  (never for a rollback) on two threads of its own; events carry ids and kinds only, never a text: `chat` (see "Tin
  nhắn") and `notification` (`NotificationService.send`). A stream lasts 5 min, then the browser reconnects (session
  checked again); 5 streams per user (oldest closed); a comment every 25 s keeps tunnels and proxies from closing it
  (`X-Accel-Buffering: no` for nginx); an Admin lock or "Xóa tài khoản" closes the account's streams. SecurityConfig
  permits ASYNC dispatches (the stream's end, already authorised). `assets/js/lib/live.js` (started by `site-header.js`)
  holds one EventSource only while the tab is in front (browsers allow 6 connections per site over HTTP/1.1), retries
  with a growing wait after the browser gives up, stops at "Đăng xuất"; `onLive("open")` fires on every (re)connect so
  pages catch up. Pages keep polling as a slower safety net. Measured on the dev PC: a message reaches the other page in
  under 0.1 s locally; through the free Pinggy tunnel every request still costs 0.3–1.5 s (the relay), so a deployed
  server with its own https address is much faster than the tunnel.
- The JDBC URL sets `forceConnectionTimeZoneToSession=true` so MySQL `DEFAULT CURRENT_TIMESTAMP` values are UTC
  like the ones the app writes (before, on a UTC+7 PC new accounts looked 7 hours younger). Add the same flag
  to `DB_URL` on the server.
- The `guide` and `scholarship` starter features still map to tables that do not exist in the schema
  (`guide`, `scholarship`); until rewritten, `GET /api/guides` and `/api/scholarships` answer 404 "Tính năng này sắp ra
  mắt." without touching the database (entities and services kept, unused), and their pages show it as text.
  Scholarships are not part of the agreed scope.
- Keep Vietnamese UI text as in the prototype. Code, identifiers and commit messages in English.
