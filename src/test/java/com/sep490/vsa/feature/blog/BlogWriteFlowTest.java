package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-11 end to end against MySQL. Uses its own database {@code asvsj_test} (created and migrated by Flyway on
 * the first run), the local credentials from application-local.properties, and fresh accounts per test.
 * The photo scanner is replaced by a mock so each test chooses PASS, FLAG or ERROR.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BlogWriteFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BlogPublishService publisher;
    @MockBean
    private ImageModerator moderator;
    @Value("${app.media.local-dir}")
    private String mediaDir;

    private long newbie;       // 2 days old, no tag: new-account limits apply
    private long veteran;      // 30 days old, no tag
    private long tagged;       // 30 days old, Mentor tag: may post links
    private long staff;        // Moderator
    private long experience;   // topic "Kinh nghiệm"
    private long scam;         // topic "Cảnh báo lừa đảo"
    private long place;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{\"flagged\":false}"));
        newbie = user("USER", 2);
        veteran = user("USER", 30);
        tagged = user("USER", 30);
        staff = user("MODERATOR", 30);
        long mentorTag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", tagged, mentorTag, staff);
        experience = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
        scam = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Cảnh báo lừa đảo'", Long.class);
        place = jdbc.queryForObject("SELECT MIN(id) FROM places WHERE status = 'ACTIVE'", Long.class);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void textPostIsPublishedAtOnceWithPointsAndDailyUsage() throws Exception {
        long id = created(send(newbie, post("POST", "Năm điều mình ước biết trước", "Mua thẻ IC ngay ở ga đầu tiên.",
                List.of(experience), List.of(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Bài viết của bạn đã được đăng."))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED")));

        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForList("SELECT topic_id FROM blog_topic_links WHERE blog_id = ?", Long.class, id))
                .containsExactly(experience);
        assertThat(jdbc.queryForList("SELECT place_id FROM blog_place_links WHERE blog_id = ?", Long.class, id))
                .containsExactly(place);
        assertThat(points(newbie)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT reason FROM point_transactions WHERE ref_type = 'BLOG' AND ref_id = ?",
                String.class, id)).isEqualTo("BLOG_CREATED");
        assertThat(usage(newbie)).containsEntry("posts", 1).containsEntry("videos", 0);
        // Shown in the feed at once
        mvc.perform(get("/api/blogs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void questionDoesNotNeedADescription() throws Exception {
        long id = created(send(veteran, post("QA", "Gia hạn visa cần mang gì?", "", List.of(experience), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Câu hỏi của bạn đã được đăng.")));
        assertThat(jdbc.queryForObject("SELECT blog_type FROM blogs WHERE id = ?", String.class, id)).isEqualTo("QA");
        assertThat(jdbc.queryForObject("SELECT content FROM blogs WHERE id = ?", String.class, id)).isEmpty();
        // A question opens in Hỏi-Đáp, never as a blog post
        mvc.perform(get("/api/questions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("QA"));
        mvc.perform(get("/api/blogs/" + id)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- step 1: required fields and lengths

    @Test
    void missingFieldsComeBackOnePerFieldWithMsg37() throws Exception {
        send(veteran, post("POST", " ", "", List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BlogWriteRules.MSG37))
                .andExpect(jsonPath("$.data.title").value("Hãy nhập tiêu đề."))
                .andExpect(jsonPath("$.data.topicIds").exists())
                .andExpect(jsonPath("$.data.content").value("Hãy nhập nội dung bài viết."));
        send(veteran, post("POST", "x".repeat(151), "ok", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").value("Tiêu đề tối đa 150 ký tự."));
        send(veteran, post("POST", "t", "ok", List.of(experience, 999_999L), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(containsString("chọn lại")));
        assertThat(postsBy(veteran)).isZero();
    }

    @Test
    void videoLongerThanThirtySecondsIsRefused() throws Exception {
        send(veteran, post("POST", "Video dài", "nội dung", List.of(experience), List.of()), video(31))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.video").value("Video dài 31 giây. Video tối đa 30 giây."));
        assertThat(postsBy(veteran)).isZero();
    }

    // ---------------------------------------------------------------- step 2: links (BR-22)

    @Test
    void onlyTaggedAccountsPostLinksAndNobodyPostsShortLinks() throws Exception {
        send(veteran, post("POST", "Link", "Xem https://www.city.arakawa.tokyo.jp nhé", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ tài khoản đã có tag định danh mới được chèn link."));
        send(tagged, post("POST", "Link", "Xem https://bit.ly/abc nhé", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("link rút gọn")));
        send(tagged, post("POST", "Link", "Xem https://www.city.arakawa.tokyo.jp nhé", List.of(experience), List.of()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- step 3: new-account limit (MSG44)

    @Test
    void newAccountPostsAtMostThreeTimesADayOlderAccountsAreNotLimited() throws Exception {
        for (int i = 1; i <= 3; i++) {
            send(newbie, post("POST", "Bài " + i, "nội dung", List.of(experience), List.of())).andExpect(status().isOk());
        }
        send(newbie, post("POST", "Bài 4", "nội dung", List.of(experience), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("giới hạn đăng bài trong ngày của tài khoản mới")));
        assertThat(postsBy(newbie)).isEqualTo(3);

        for (int i = 1; i <= 4; i++) {
            send(veteran, post("POST", "Bài " + i, "nội dung", List.of(experience), List.of())).andExpect(status().isOk());
        }
    }

    @Test
    void newAccountAddsOneVideoADay() throws Exception {
        send(newbie, post("POST", "Video 1", "nội dung", List.of(experience), List.of()), video(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertThat(usage(newbie)).containsEntry("posts", 1).containsEntry("videos", 1);
        send(newbie, post("POST", "Video 2", "nội dung", List.of(experience), List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("giới hạn video")));
        // Without the video the second post goes through
        send(newbie, post("POST", "Không video", "nội dung", List.of(experience), List.of())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- step 4: banned keywords (MSG36), and the order

    @Test
    void bannedKeywordIsRefusedAndNothingIsSaved() throws Exception {
        send(veteran, post("POST", "Tuyển gấp", "Việc nhẹ, lương-cao cho du học sinh", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("“việc nhẹ lương cao”")));
        assertThat(postsBy(veteran)).isZero();
        assertThat(usage(veteran)).containsEntry("posts", 0);
    }

    @Test
    void checksRunInTheAgreedOrder() throws Exception {
        // Link (step 2) is reported before the keyword (step 4)
        send(veteran, post("POST", "vay nóng", "https://www.example.com", List.of(experience), List.of()))
                .andExpect(jsonPath("$.message").value(containsString("tag định danh")));
        // Daily limit (step 3) is reported before the keyword (step 4)
        jdbc.update("INSERT INTO user_daily_usage (user_id, usage_date, posts) VALUES (?, ?, 3)",
                newbie, java.sql.Date.valueOf(today()));
        send(newbie, post("POST", "vay nóng", "nội dung", List.of(experience), List.of()))
                .andExpect(status().isTooManyRequests());
        // Missing fields (step 1) come before everything else
        send(newbie, post("POST", "vay nóng", "", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").exists());
    }

    // ---------------------------------------------------------------- media: PROCESSING → PUBLISHED / REJECTED

    @Test
    void postWithPhotoWaitsForTheScanThenIsPublishedWithPoints() throws Exception {
        long id = created(send(veteran, post("POST", "Ảnh hanami", "Hoa nở rồi", List.of(experience), List.of()), photo())
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));

        assertThat(points(veteran)).isEqualTo(10);
        Map<String, Object> media = jdbc.queryForMap("""
                SELECT m.scan_status, m.mime_type, m.width, m.storage_key FROM blog_media bm JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ?""", id);
        assertThat(media).containsEntry("scan_status", "PASSED").containsEntry("mime_type", "image/jpeg");
        assertThat(Files.exists(Path.of(mediaDir).resolve((String) media.get("storage_key")))).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT result FROM moderation_scans WHERE target_type = 'MEDIA'
                AND target_id = (SELECT media_id FROM blog_media WHERE blog_id = ?)""", String.class, id)).isEqualTo("PASS");
        mvc.perform(get("/api/blogs/" + id)).andExpect(jsonPath("$.data.media[0].url").value(containsString("/media/blog/")));
        // The file is served at its public URL
        mvc.perform(get("/media/" + media.get("storage_key")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/jpeg"));
    }

    @Test
    void whileScanningOnlyTheAuthorSeesThePost() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));
        long id = created(send(veteran, post("POST", "Đang quét", "nội dung", List.of(experience), List.of()), photo()));
        Thread.sleep(500);
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        mvc.perform(get("/api/blogs/" + id).session(session(veteran, "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(get("/api/blogs/" + id).session(session(tagged, "USER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/blogs/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/blogs").session(session(veteran, "USER")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) id)));

        // Right after an error the file waits (1 minute) instead of calling the scanner again
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        publisher.process(id);
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");

        // The pause is over and the scanner works again: the retry publishes the post
        jdbc.update("""
                UPDATE moderation_scans SET created_at = created_at - INTERVAL 2 MINUTE
                WHERE target_type = 'MEDIA' AND target_id = (SELECT media_id FROM blog_media WHERE blog_id = ?)""", id);
        publisher.process(id);
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(points(veteran)).isEqualTo(10);
        publisher.process(id);                                  // running again never pays twice
        assertThat(points(veteran)).isEqualTo(10);
    }

    @Test
    void flaggedPhotoRejectsThePostDeletesTheFileAndTellsTheAuthor() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long id = created(send(veteran, post("QA", "Câu hỏi có ảnh", "", List.of(experience), List.of()), photo()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "REJECTED".equals(blogStatus(id)));

        assertThat(points(veteran)).isZero();
        Map<String, Object> media = jdbc.queryForMap("""
                SELECT m.scan_status, m.deleted_at, m.storage_key FROM blog_media bm JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ?""", id);
        assertThat(media.get("scan_status")).isEqualTo("REJECTED");
        assertThat(media.get("deleted_at")).isNotNull();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Files.exists(Path.of(mediaDir).resolve((String) media.get("storage_key"))));
        assertThat(jdbc.queryForObject("SELECT content FROM notifications WHERE user_id = ? AND type = 'CONTENT_REJECTED'",
                String.class, veteran)).startsWith("Câu hỏi “Câu hỏi có ảnh” chưa được đăng");
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notifications WHERE user_id = ? AND type = 'CONTENT_REJECTED'",
                String.class, veteran)).isEqualTo("QUESTION");
    }

    @Test
    void deletingAPostRemovesItsFiles() throws Exception {
        long id = created(send(veteran, post("POST", "Sẽ xóa", "nội dung", List.of(experience), List.of()), photo()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));
        String key = jdbc.queryForObject("SELECT m.storage_key FROM blog_media bm JOIN media m ON m.id = bm.media_id "
                + "WHERE bm.blog_id = ?", String.class, id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/blogs/" + id)
                        .session(session(veteran, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());
        assertThat(Files.exists(Path.of(mediaDir).resolve(key))).isFalse();
        assertThat(points(veteran)).isZero();
    }

    // ---------------------------------------------------------------- who may post, screen data

    @Test
    void guestsAndStaffCannotPost() throws Exception {
        mvc.perform(multipart("/api/blogs").file(json(post("POST", "t", "c", List.of(experience), List.of())))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isUnauthorized());
        send(staff, "MODERATOR", post("POST", "t", "c", List.of(experience), List.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(BlogWriteService.NO_PERMISSION));
    }

    @Test
    void composeShowsTopicsLimitsAndTheNewAccountNote() throws Exception {
        mvc.perform(get("/api/blogs/compose").session(session(newbie, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topics[?(@.name == 'Khác')].other").value(true))
                .andExpect(jsonPath("$.data.topics[?(@.name == 'Cảnh báo lừa đảo')].scamWarning").value(true))
                .andExpect(jsonPath("$.data.limits.titleMax").value(150))
                .andExpect(jsonPath("$.data.limits.contentMax").value(5000))
                .andExpect(jsonPath("$.data.quota.newAccount").value(true))
                .andExpect(jsonPath("$.data.quota.postsLimit").value(3))
                .andExpect(jsonPath("$.data.canPostLinks").value(false));
        mvc.perform(get("/api/blogs/compose").session(session(tagged, "USER")))
                .andExpect(jsonPath("$.data.quota.newAccount").value(false))
                .andExpect(jsonPath("$.data.canPostLinks").value(true));
        mvc.perform(get("/api/blogs/compose")).andExpect(status().isUnauthorized());
    }

    @Test
    void placeSearchIgnoresAccentsAndScamPostsMarkTheirPlaces() throws Exception {
        String name = jdbc.queryForObject("SELECT name_vi FROM places WHERE id = ?", String.class, place);
        String plain = com.sep490.vsa.common.text.VietnameseText.fold(name).toUpperCase();
        mvc.perform(get("/api/places/search").param("q", plain).session(session(veteran, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) place)));
        mvc.perform(get("/api/places/search").param("q", "  ").session(session(veteran, "USER")))
                .andExpect(jsonPath("$.data.items").isEmpty());

        long id = created(send(veteran, post("POST", "Cẩn thận môi giới giả", "Có người giả làm nhân viên",
                List.of(scam), List.of(place))));
        mvc.perform(get("/api/blogs/scam-warnings").param("placeId", String.valueOf(place)))
                .andExpect(jsonPath("$.data[*].blogId", hasItem((int) id)));
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role, int daysOld) {
        String email = "uc11-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'UC11 Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "UC11 Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    /** A new post (kind POST, sent to /api/blogs) or question (kind QA, sent to /api/questions). */
    private record Body(String kind, String json) {
    }

    private static Body post(String kind, String title, String content, List<Long> topics, List<Long> places) {
        return new Body(kind, "{\"title\":\"" + title + "\",\"content\":\"" + content
                + "\",\"topicIds\":" + topics + ",\"placeIds\":" + places + "}");
    }

    private static MockMultipartFile json(Body body) {
        return new MockMultipartFile("QA".equals(body.kind()) ? "question" : "post", "", "application/json",
                body.json().getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions send(long userId, Body body, MockMultipartFile... files) throws Exception {
        return send(userId, "USER", body, files);
    }

    private ResultActions send(long userId, String role, Body body, MockMultipartFile... files) throws Exception {
        String url = "QA".equals(body.kind()) ? "/api/questions" : "/api/blogs";
        MockMultipartHttpServletRequestBuilder request = multipart(url).file(json(body));
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(request.session(session(userId, role))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }

    /** A minimal MP4: ftyp + moov/mvhd with the given length. */
    private static MockMultipartFile video(int seconds) {
        ByteBuffer ftyp = ByteBuffer.allocate(16).putInt(16).put("ftypisom".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x200);
        ByteBuffer mvhd = ByteBuffer.allocate(108).putInt(108).put("mvhd".getBytes(StandardCharsets.ISO_8859_1))
                .putInt(0).putInt(0).putInt(0).putInt(1000).putInt(seconds * 1000);
        ByteBuffer moov = ByteBuffer.allocate(8 + 108).putInt(116).put("moov".getBytes(StandardCharsets.ISO_8859_1)).put(mvhd.array());
        ByteBuffer file = ByteBuffer.allocate(16 + 116).put(ftyp.array()).put(moov.array());
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", file.array());
    }

    private String blogStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, id);
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long postsBy(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE author_id = ?", Long.class, userId);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Tokyo"));
    }

    private Map<String, Object> usage(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT posts, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                userId, java.sql.Date.valueOf(today()));
        if (rows.isEmpty()) {
            return Map.of("posts", 0, "videos", 0);
        }
        return Map.of("posts", ((Number) rows.get(0).get("posts")).intValue(),
                "videos", ((Number) rows.get(0).get("videos")).intValue());
    }
}
