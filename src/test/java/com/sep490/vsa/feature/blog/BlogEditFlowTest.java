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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Cập nhật bài viết" / "Cập nhật câu hỏi" end to end against MySQL ({@code asvsj_test}, like
 * {@link BlogWriteFlowTest}): the same checks as Write Post, the status after an edit, media kept, removed and
 * added, points paid once, and who may edit.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BlogEditFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;
    @Value("${app.media.local-dir}")
    private String mediaDir;

    private long newbie;       // 2 days old: new-account limits apply
    private long veteran;      // 30 days old
    private long other;        // 30 days old, not the author
    private long staff;        // Moderator
    private long experience;
    private long scam;
    private long place;
    private long otherPlace;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{\"flagged\":false}"));
        newbie = user("USER", 2);
        veteran = user("USER", 30);
        other = user("USER", 30);
        staff = user("MODERATOR", 30);
        experience = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
        scam = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Cảnh báo lừa đảo'", Long.class);
        List<Long> places = jdbc.queryForList("SELECT id FROM places WHERE status = 'ACTIVE' ORDER BY id LIMIT 2", Long.class);
        place = places.get(0);
        otherPlace = places.get(1);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void authorEditsTextTopicsAndPlacesThePostStaysPublishedAndShowsEdited() throws Exception {
        long id = created(create(newbie, body("POST", "Bài gốc", "Nội dung gốc", List.of(experience), List.of(place))));
        assertThat(points(newbie)).isEqualTo(10);

        mvc.perform(get("/api/blogs/" + id + "/edit").session(session(newbie, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Bài gốc"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.topicIds[0]").value((int) experience))
                .andExpect(jsonPath("$.data.places[0].id").value((int) place))
                .andExpect(jsonPath("$.data.options.limits.titleMax").value(150))
                .andExpect(jsonPath("$.data.options.topics").isNotEmpty());

        update(newbie, body("POST", "Bài đã sửa", "Nội dung mới", List.of(scam), List.of(otherPlace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu thay đổi."))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        Map<String, Object> row = jdbc.queryForMap("SELECT title, content, status, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("title", "Bài đã sửa").containsEntry("content", "Nội dung mới")
                .containsEntry("status", "PUBLISHED").containsEntry("is_edited", true);
        assertThat(jdbc.queryForList("SELECT topic_id FROM blog_topic_links WHERE blog_id = ?", Long.class, id))
                .containsExactly(scam);
        assertThat(jdbc.queryForList("SELECT place_id FROM blog_place_links WHERE blog_id = ?", Long.class, id))
                .containsExactly(otherPlace);
        // No points for an edit, and it is not a new post for the daily limit
        assertThat(points(newbie)).isEqualTo(10);
        assertThat(usage(newbie)).containsEntry("posts", 1);
        mvc.perform(get("/api/blogs/" + id)).andExpect(jsonPath("$.data.edited").value(true));

        // Plain JSON works too when there are no new files
        mvc.perform(put("/api/blogs/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("POST", "Lần hai", "Nội dung", List.of(experience), List.of()).json())
                        .session(session(newbie, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_place_links WHERE blog_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void questionIsEditedThroughHoiDapAndKeepsItsAnswersAndBestAnswer() throws Exception {
        long id = created(create(veteran, body("QA", "Gia hạn visa cần gì?", "", List.of(experience), List.of())));
        mvc.perform(post("/api/questions/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Mang hộ chiếu và thẻ cư trú.\"}")
                        .session(session(other, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());
        long answer = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ?", Long.class, id);
        mvc.perform(put("/api/questions/" + id + "/best-answer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answerId\":" + answer + "}")
                        .session(session(veteran, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());

        mvc.perform(get("/api/questions/" + id + "/edit").session(session(veteran, "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("QA"));
        lastId = id;
        update(veteran, body("QA", "Gia hạn visa cần mang những gì?", "Mình ở Tokyo.", List.of(experience), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu thay đổi."));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, is_edited, best_comment_id, comment_count FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("title", "Gia hạn visa cần mang những gì?").containsEntry("is_edited", true)
                .containsEntry("best_comment_id", answer).containsEntry("comment_count", 1);
        assertThat(points(veteran)).isEqualTo(10);
    }

    // ---------------------------------------------------------------- who may edit

    @Test
    void onlyTheAuthorEditsAndOnlyOnThePageOfItsKind() throws Exception {
        long id = created(create(veteran, body("POST", "Của veteran", "nội dung", List.of(experience), List.of())));
        lastId = id;
        Body change = body("POST", "Đổi", "nội dung", List.of(experience), List.of());

        update(other, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(BlogWriteService.NO_PERMISSION));
        mvc.perform(get("/api/blogs/" + id + "/edit").session(session(other, "USER"))).andExpect(status().isForbidden());
        update(staff, "MODERATOR", change).andExpect(status().isForbidden());
        mvc.perform(multipart(HttpMethod.PUT, "/api/blogs/" + id).file(json(change))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/blogs/" + id + "/edit")).andExpect(status().isUnauthorized());

        // A post is not a question: /api/questions answers 404 (MSG19)
        update(veteran, body("QA", "Đổi", "nội dung", List.of(experience), List.of()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(BlogDetailService.NOT_FOUND));
        mvc.perform(get("/api/questions/" + id + "/edit").session(session(veteran, "USER"))).andExpect(status().isNotFound());

        // Hidden by reports or removed: not editable
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", id);
        update(veteran, change).andExpect(status().isNotFound());
        jdbc.update("UPDATE blogs SET status = 'REMOVED' WHERE id = ?", id);
        mvc.perform(get("/api/blogs/" + id + "/edit").session(session(veteran, "USER"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, id)).isEqualTo("Của veteran");
    }

    // ---------------------------------------------------------------- the same checks as Write Post

    @Test
    void fieldsLinksAndBannedKeywordsAreCheckedAndNothingChanges() throws Exception {
        long id = created(create(veteran, body("POST", "Bài sạch", "nội dung", List.of(experience), List.of())));
        lastId = id;
        update(veteran, body("POST", " ", "", List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BlogWriteRules.MSG37))
                .andExpect(jsonPath("$.data.title").exists())
                .andExpect(jsonPath("$.data.topicIds").exists());
        update(veteran, body("POST", "Link", "Xem https://www.city.arakawa.tokyo.jp nhé", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ tài khoản đã có tag định danh mới được chèn link."));
        update(veteran, body("POST", "Tuyển gấp", "Việc nhẹ, lương-cao cho du học sinh", List.of(experience), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("“việc nhẹ lương cao”")));

        Map<String, Object> row = jdbc.queryForMap("SELECT title, content, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("title", "Bài sạch").containsEntry("content", "nội dung")
                .containsEntry("is_edited", false);
        assertThat(jdbc.queryForList("SELECT topic_id FROM blog_topic_links WHERE blog_id = ?", Long.class, id))
                .containsExactly(experience);
    }

    @Test
    void phoneNumbersOrChatIdsAddedByAnEditNeedAnIdentityTag() throws Exception {
        long id = created(create(veteran, body("POST", "Bài sạch", "nội dung", List.of(experience), List.of())));
        update(veteran, body("POST", "Bài sạch", "Liên hệ LINE ID: scam123 hoặc 090-1234-5678", List.of(experience),
                List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram."));
        Map<String, Object> row = jdbc.queryForMap("SELECT content, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("content", "nội dung").containsEntry("is_edited", false);
    }

    @Test
    void keptPhotosCountTowardTheFivePhotoLimit() throws Exception {
        long id = created(create(veteran, body("POST", "Một ảnh", "nội dung", List.of(experience), List.of()), photo()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));
        lastId = id;
        long kept = mediaOf(id).get(0);
        update(veteran, body("POST", "Sáu ảnh", "nội dung", List.of(experience), List.of(), List.of(kept)),
                photo(), photo(), photo(), photo(), photo())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").exists());
        assertThat(mediaOf(id)).containsExactly(kept);
    }

    // ---------------------------------------------------------------- media and status

    @Test
    void removingOnePhotoAndAddingAnotherScansTheNewOneThenPublishesWithoutNewPoints() throws Exception {
        long id = created(create(veteran, body("POST", "Hai ảnh", "nội dung", List.of(experience), List.of()), photo(), photo()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));
        assertThat(points(veteran)).isEqualTo(10);
        List<Long> before = mediaOf(id);
        String removedKey = jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, before.get(0));

        lastId = id;
        mvc.perform(get("/api/blogs/" + id + "/edit").session(session(veteran, "USER")))
                .andExpect(jsonPath("$.data.media.length()").value(2))
                .andExpect(jsonPath("$.data.media[0].url").value(containsString("/media/blog/")));
        // Keep the second photo, add one
        update(veteran, body("POST", "Hai ảnh (đổi 1)", "nội dung", List.of(experience), List.of(),
                List.of(before.get(1))), photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.message").value(containsString("đang được quét")));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));

        List<Long> after = mediaOf(id);
        assertThat(after).hasSize(2).startsWith(before.get(1)).doesNotContain(before.get(0));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE id = ?", Boolean.class, before.get(0)))
                .isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> !Files.exists(Path.of(mediaDir).resolve(removedKey)));
        assertThat(points(veteran)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
    }

    @Test
    void rejectedPostFixedByTheAuthorIsPublishedAndEarnsItsPointsOnce() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long id = created(create(veteran, body("POST", "Ảnh vi phạm", "nội dung", List.of(experience), List.of()), photo()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "REJECTED".equals(blogStatus(id)));
        assertThat(points(veteran)).isZero();
        lastId = id;
        // The rejected photo is gone already; the edit form shows no media
        mvc.perform(get("/api/blogs/" + id + "/edit").session(session(veteran, "USER")))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.media").isEmpty());

        update(veteran, body("POST", "Đã bỏ ảnh", "nội dung", List.of(experience), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(points(veteran)).isEqualTo(10);

        // Editing again, even with a new photo scanned and published, never pays twice
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        update(veteran, body("POST", "Thêm ảnh mới", "nội dung", List.of(experience), List.of()), photo())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));
        update(veteran, body("POST", "Lần ba", "nội dung", List.of(experience), List.of(), mediaOf(id)))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(points(veteran)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
    }

    @Test
    void aVideoAddedToAPostWithoutOneCountsTowardTheNewAccountVideoLimit() throws Exception {
        long withVideo = created(create(newbie, body("POST", "Có video", "nội dung", List.of(experience), List.of()), video(10)));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(withVideo)));
        long textOnly = created(create(newbie, body("POST", "Chỉ chữ", "nội dung", List.of(experience), List.of())));
        assertThat(usage(newbie)).containsEntry("posts", 2).containsEntry("videos", 1);

        lastId = textOnly;
        update(newbie, body("POST", "Chỉ chữ + video", "nội dung", List.of(experience), List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("giới hạn video")));
        assertThat(mediaOf(textOnly)).isEmpty();

        // Replacing the video of a post that had one is not a new video
        lastId = withVideo;
        update(newbie, body("POST", "Đổi video", "nội dung", List.of(experience), List.of()), video(12))
                .andExpect(status().isOk());
        assertThat(usage(newbie)).containsEntry("posts", 2).containsEntry("videos", 1);
        assertThat(mediaOf(withVideo)).hasSize(1);

        // A post may hold one video only: kept + new
        long kept = mediaOf(withVideo).get(0);
        update(newbie, body("POST", "Hai video", "nội dung", List.of(experience), List.of(), List.of(kept)), video(5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.video").value("Chỉ đính kèm được 1 video."));
    }

    // ---------------------------------------------------------------- helpers

    /** The post the next {@link #update} call edits. */
    private long lastId;

    private long user(String role, int daysOld) {
        String email = "edit-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Edit Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Edit Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private record Body(String kind, String json) {
    }

    private static Body body(String kind, String title, String content, List<Long> topics, List<Long> places) {
        return body(kind, title, content, topics, places, List.of());
    }

    private static Body body(String kind, String title, String content, List<Long> topics, List<Long> places,
                             List<Long> keepMediaIds) {
        return new Body(kind, "{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"topicIds\":" + topics
                + ",\"placeIds\":" + places + ",\"keepMediaIds\":" + keepMediaIds + "}");
    }

    private static MockMultipartFile json(Body body) {
        return new MockMultipartFile("QA".equals(body.kind()) ? "question" : "post", "", "application/json",
                body.json().getBytes(StandardCharsets.UTF_8));
    }

    private static String base(Body body) {
        return "QA".equals(body.kind()) ? "/api/questions" : "/api/blogs";
    }

    private ResultActions create(long userId, Body body, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(base(body)).file(json(body));
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(request.session(session(userId, "USER"))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    private ResultActions update(long userId, Body body, MockMultipartFile... files) throws Exception {
        return update(userId, "USER", body, files);
    }

    private ResultActions update(long userId, String role, Body body, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, base(body) + "/" + lastId).file(json(body));
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(request.session(session(userId, role))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    private long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        lastId = Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
        return lastId;
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

    /** Attached media that is not deleted, in display order. */
    private List<Long> mediaOf(long blogId) {
        return jdbc.queryForList("""
                SELECT m.id FROM blog_media bm JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ? AND m.deleted_at IS NULL ORDER BY bm.sort_order, m.id""", Long.class, blogId);
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private int awards(long blogId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_transactions WHERE ref_type = 'BLOG' AND ref_id = ? AND reason = 'BLOG_CREATED'""",
                Integer.class, blogId);
    }

    private Map<String, Object> usage(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT posts, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                userId, java.sql.Date.valueOf(LocalDate.now(ZoneId.of("Asia/Tokyo"))));
        if (rows.isEmpty()) {
            return Map.of("posts", 0, "videos", 0);
        }
        return Map.of("posts", ((Number) rows.get(0).get("posts")).intValue(),
                "videos", ((Number) rows.get(0).get("videos")).intValue());
    }
}
