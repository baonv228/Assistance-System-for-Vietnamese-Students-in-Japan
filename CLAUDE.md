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
- Places: OpenStreetMap places store their `osm_id`, names, address and coordinates (ODbL allows it; credit
  "© OpenStreetMap contributors"); places saved from Google Maps before keep their `google_place_id` (no address or
  coordinates); user pins need Moderator approval; same name within 200 m
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
- Page addresses have no ".html" (`common/web/CleanUrls`): `/login`, `/register`, `/terms`, `/profile`, `/blog`,
  `/blog/new`, `/blog/{id}`, `/qa`, `/qa/ask`, `/qa/{id}`, `/coming-soon`, `/moderator`… forward to the files in
  `static/pages/`; old `/pages/*.html` links (`?id=` included) answer 301 to them. Links in pages and JS use the clean
  addresses; a new page gets its entry in `CleanUrls.PAGES`.
- Each role lands on `Role.homePath()`: USER `/`, MODERATOR `/moderator`, ACADEMIC_MANAGER
  `/academic`, ADMIN `/admin` (sample dashboards). SecurityConfig enforces it: guests go to
  login, a wrong role is redirected to its own home; `/api/moderator|academic|admin/**` need the role,
  `/api/me/**` needs login.
- `feature/home` reads other features' tables (places, reviews, events, blogs, guide, phrases) with plain SQL
  and never writes them, so it does not clash with the entities teammates will create.
- App pages use `assets/css/tokens.css` + `site.css` and `components/site-header.js` (top nav on desktop,
  bottom tab bar on phones). Links to features not built yet go to `/coming-soon?f=<feature>`.
- Profile `local` runs `DevDataSeeder`: test accounts (password 123456, see README) and
  `dev/sample-data.sql`. Never use profile `local` on the server.

## Blog feed (feature/blog)
- Blog = posts only (`blogs.blog_type = 'POST'`); questions (`'QA'`) live in the same table but belong to Hỏi-Đáp
  (see below). Every blog query, count, statistic, featured snapshot, home featured post and scam warning filters
  `blog_type = 'POST'`.
- `GET /api/blogs` (search, topic filter, sort, paging) and `GET /api/blogs/overview` (featured posts,
  active topics, side statistics). Plain SQL like `feature/home`; search folds accents and case via
  `utf8mb4_0900_ai_ci`. A signed-in author also sees their own `PROCESSING` posts.
- `FeaturedSnapshotService` fills `featured_snapshots` (WEEK = last 7 days, MONTH = last 30) at startup and
  every hour. Page: `pages/blog.html` + `assets/js/pages/blog.js`; links to Write Post, Map and Public Profile
  go through `ROUTES` at the top of blog.js (Map and Public Profile are coming-soon pages for now). Old
  `blog.html?type=QA` links redirect to `qa.html`.
- Post Detail (UC-10): `GET /api/blogs/{id}` (public) returns the post, media, comment tree and `viewer` flags.
  Actions (signed-in role USER only, never on one's own content for like/report): `POST|DELETE
  /api/blogs/{id}/like|save`, `POST /api/blogs/{id}/comments` (`parentId` for replies), `POST /api/blogs/{id}/report`,
  `DELETE /api/blogs/{id}` (own post: hard delete, its points withdrawn), `POST|DELETE /api/comments/{id}/like`,
  `POST /api/comments/{id}/report`, `DELETE /api/comments/{id}` (own; a comment with replies becomes an empty
  REMOVED placeholder). Page: `/blog/{id}` (file `pages/blog-detail.html`).
- The services take a `kind` ("POST" / "QA"): `/api/blogs/**` passes POST, `/api/questions/**` passes QA, and an id of
  the other kind answers 404 (MSG19). Only the shared `/api/comments/{id}/...` calls pass null (any kind).
  Notifications about a question use `ref_type = 'QUESTION'` (`BlogActionService.refType`); reports keep `BLOG`
  because of the `report_cases` CHECK constraint.
- Shared helpers used by blog and reusable elsewhere: `feature/report/ContentReportService` (one report per
  user per item, more than `report_hide_threshold` → URGENT and the caller hides the item, reporting lock),
  `feature/moderation/ContentFilter` (banned keywords ignoring accents/spaces; links only for tagged users,
  no short links), `feature/user/PointService`, `feature/notification/NotificationService`,
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
  `BlogPublishService` scans after commit (and a retry job every 2 min): all PASSED → PUBLISHED + points,
  any REJECTED → REJECTED, rejected files deleted, author notified (`CONTENT_REJECTED`). `user_daily_usage` counts
  every new post, in Japan dates. Values still "cần chốt" (title 150, QA description optional, photo rules = review
  rules) are constants in `BlogWriteRules`.
- `feature/media`: `MediaService` re-encodes photos to JPG ≤ 1600 px (EXIF/GPS dropped; the page also shrinks them
  before upload, HEIC only where the browser opens it), checks MP4/MOV length ≤ 30 s from the file itself
  (`Mp4Duration`, no ffprobe; fragmented MP4 refused). Files are stored by `LocalMediaStorage` in
  `app.media.local-dir` (default `uploads/`, git-ignored) and served at `/media/**`; implement `MediaStorage` for
  Cloudflare R2 later. Deleting a post (UC-13) also deletes its files.
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
- Solved = the best answer is still PUBLISHED (`QaRepository.SOLVED_SQL`), so a best answer hidden by reports or
  removed stops counting, as on the question page. Unanswered = no published answer and not solved.
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
  branches ("Lawson") are often 100–200 m apart under the same name. The copy whose `osm_id` ASVSJ has wins. An OSM
  result with an ASVSJ `osm_id`, or with the same name close to an approved ASVSJ place (a team place, a pin, a Google
  place with coordinates; 600 m only station↔ASVSJ station), is shown as that ASVSJ place; one a Moderator removed is
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
- Fair use of the free public services: Nominatim at most 1 request a second for the whole server (callers wait up to
  6 s, then BUSY) with a User-Agent naming the app (`app.places.osm.user-agent`); per user 40 map searches a minute
  and 25 new map places a day (in memory); the same text within 10 minutes and a looked-up place within the hour are
  answered from memory. Real calls are counted in `api_usage` (`OSM_PHOTON_SEARCH`, `OSM_NOMINATIM_LOOKUP`, Japan days).
- Implementations of `ExternalPlaces` (`ExternalPlacesConfig`): `DevOsmPlaces` (made-up Tokyo/Hanoi places, ids
  `DEV-…`, works offline) when `app.places.dev-fake=true`, `OsmPlaces` when `app.places.osm.enabled` (default true),
  else none (ASVSJ places only). Tests set `app.places.osm.enabled=false` or mock `ExternalPlaces`: no test calls the
  internet. On Windows the client also trusts the Windows certificate store (antivirus HTTPS scanning).
- Post / question detail: linked places with a position appear on a small map (Leaflet 1.9.4 from unpkg with SRI,
  OSM tiles `tile.openstreetmap.org`, credit shown), loaded only when needed; `PlaceRef` carries category, address,
  lat, lng. The map element is kept across re-renders (likes, comments).
- Dev sample places: Ga Nippori is the real OSM station N772389775, the other made-up places are TEAM places;
  `DevDataSeeder` moves rows of older checkouts (made-up "ChIJ-dev-…" Google ids) the same way.

## Working notes
- The JDBC URL sets `forceConnectionTimeZoneToSession=true` so MySQL `DEFAULT CURRENT_TIMESTAMP` values are UTC
  like the ones the app writes (before, on a UTC+7 PC new accounts looked 7 hours younger). Add the same flag
  to `DB_URL` on the server.
- The `guide` and `scholarship` starter features still map to tables that do not exist in the schema
  (`guide`, `scholarship`); their APIs fail until rewritten. Scholarships are not part of the agreed scope.
- Keep Vietnamese UI text as in the prototype. Code, identifiers and commit messages in English.
