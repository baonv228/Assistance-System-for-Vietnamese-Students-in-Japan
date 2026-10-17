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
  one-time tokens stored as SHA-256 hashes. 5 wrong passwords → lock 30 minutes.
- Content (reviews, posts, comments) is moderated automatically: keyword filter + image moderation.
  Reports: 1–5 → "Cần xử lý"; more than 5 → auto-hidden and "Khẩn". More than 5 false reports → reporting
  locked 7 days. Removed content is kept 30 days for appeals, then purged.
- New accounts (< 7 days): per day max 3 posts/reviews, 2 pins, 1 video.
- Places: Google places store only `place_id`; user pins need Moderator approval; same name within 200 m
  → warning. Moderator removes places softly (with reason, restorable) and merges duplicates (final, no undo).
  5 "đã đóng cửa" reports → Moderator queue; only the Moderator sets the closed label.
- One review per user per place, and per year of an event. Review text ≤ 2,000 chars, ≤ 5 photos
  (≤ 20 MB each), video ≤ 30 s.
- Featured posts are automatic: top 3 of the week/month by likes + 2 × comments + rank bonus, min score 10,
  one post per author. No manual featuring.

## Auth (feature/auth, common/security)
- Session-based Spring Security (cookie JSESSIONID). Read the signed-in user with
  `@AuthenticationPrincipal AppUserPrincipal me`. `GET /api/**` is public; other `/api/**` calls need login.
- CSRF is on: frontend code must call the API through `assets/js/api/client.js`, which sends the
  `X-XSRF-TOKEN` header.
- Email sign-up: send-code → verify-code (returns a one-time ticket) → complete (name, password, 18+, terms).
  Without `spring.mail.host` the code is printed in the server console. Google/Facebook login turns on only
  when their client ids are set in `application-local.properties` (see the `.example` file).

## Roles, homepage, profile
- Each role lands on `Role.homePath()`: USER `/`, MODERATOR `/pages/moderator/`, ACADEMIC_MANAGER
  `/pages/academic/`, ADMIN `/pages/admin/` (sample dashboards). SecurityConfig enforces it: guests go to
  login, a wrong role is redirected to its own home; `/api/moderator|academic|admin/**` need the role,
  `/api/me/**` needs login.
- `feature/home` reads other features' tables (places, reviews, events, blogs, guide, phrases) with plain SQL
  and never writes them, so it does not clash with the entities teammates will create.
- App pages use `assets/css/tokens.css` + `site.css` and `components/site-header.js` (top nav on desktop,
  bottom tab bar on phones). Links to features not built yet go to `/pages/coming-soon.html?f=<feature>`.
- Profile `local` runs `DevDataSeeder`: test accounts (password 123456, see README) and
  `dev/sample-data.sql`. Never use profile `local` on the server.

## Blog feed (feature/blog)
- `GET /api/blogs` (search, topic/type filter, sort, paging) and `GET /api/blogs/overview` (featured posts,
  active topics, side statistics). Plain SQL like `feature/home`; search folds accents and case via
  `utf8mb4_0900_ai_ci`. A signed-in author also sees their own `PROCESSING` posts.
- `FeaturedSnapshotService` fills `featured_snapshots` (WEEK = last 7 days, MONTH = last 30) at startup and
  every hour. Page: `pages/blog.html` + `assets/js/pages/blog.js`; links to Write Post, Map and Public Profile
  go through `ROUTES` at the top of blog.js (coming-soon pages for now).
- Post Detail (UC-10): `GET /api/blogs/{id}` (public) returns the post, media, comment tree and `viewer` flags.
  Actions (signed-in role USER only, never on one's own content for like/report): `POST|DELETE
  /api/blogs/{id}/like|save`, `POST /api/blogs/{id}/comments` (`parentId` for replies), `PUT
  /api/blogs/{id}/best-comment` (asker, Q&A, +5 points moved if changed), `POST /api/blogs/{id}/report`,
  `DELETE /api/blogs/{id}` (own post: hard delete, its points withdrawn), `POST|DELETE /api/comments/{id}/like`,
  `POST /api/comments/{id}/report`, `DELETE /api/comments/{id}` (own; a comment with replies becomes an empty
  REMOVED placeholder). Page: `pages/blog-detail.html?id=`.
- Shared helpers used by blog and reusable elsewhere: `feature/report/ContentReportService` (one report per
  user per item, more than `report_hide_threshold` → URGENT and the caller hides the item, reporting lock),
  `feature/moderation/ContentFilter` (banned keywords ignoring accents/spaces; links only for tagged users,
  no short links), `feature/user/PointService`, `feature/notification/NotificationService`,
  `common/settings/SystemSettings`, `common/text/VietnameseText`. Media URLs = `app.media.public-base-url`
  (default `/media/`) + `media.storage_key` until file storage is decided.

## Working notes
- The `guide` and `scholarship` starter features still map to tables that do not exist in the schema
  (`guide`, `scholarship`); their APIs fail until rewritten. Scholarships are not part of the agreed scope.
- Keep Vietnamese UI text as in the prototype. Code, identifiers and commit messages in English.
