package com.sep490.vsa.feature.notification;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.feature.moderation.MediaReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The author hears the result of every photo/video scan (posts, questions, place reviews, the first review of a pin,
 * event reviews): CONTENT_PUBLISHED when it passed, CONTENT_REJECTED with the reason when it did not, and nothing
 * while it waits. Then the bell's API (/api/me/notifications): list, summary, read, links, and who may call it.
 * Against MySQL ({@code asvsj_test}, like BlogWriteFlowTest); the image scanner is a mock and no reader is configured,
 * so a photo passes or fails on the image scan alone.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ScanNotificationFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASS = "{\"flagged\":false}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationService notifications;
    @MockBean
    private ImageModerator moderator;
    @MockBean
    private MediaReader reader;

    private long owner;        // 30 days old
    private long other;
    private long staff;        // Moderator: creates places and events
    private long postTopic;    // "Kinh nghiệm"
    private long qaTopic;      // "Visa và cư trú"

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", PASS));
        when(reader.engine()).thenReturn("GEMINI");
        when(reader.enabled()).thenReturn(false);
        owner = user("USER");
        other = user("USER");
        staff = user("MODERATOR");
        postTopic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
        qaTopic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Visa và cư trú'", Long.class);
    }

    // ---------------------------------------------------------------- posts and questions

    @Test
    void aPostWhosePhotoPassesIsAnnouncedAsPublishedWithItsPoints() throws Exception {
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Hoa anh đào ở Ueno", postTopic))
                .file(photo())).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        assertThat(rows(owner)).isEmpty();      // nothing while the photo is being scanned

        awaitBlog(id, "PUBLISHED");
        assertThat(rows(owner)).containsExactly("CONTENT_PUBLISHED BLOG " + id
                + " Bài viết “Hoa anh đào ở Ueno” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        assertThat(points(owner)).isEqualTo(10);
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("CONTENT_PUBLISHED"))
                .andExpect(jsonPath("$.data.items[0].link").value("/blog/" + id))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test
    void aQuestionWhosePhotoPassesIsAnnouncedAsAQuestionAndOpensHoiDap() throws Exception {
        long id = created(send(owner, multipart("/api/questions")
                .file(blogPart("question", "Gia hạn visa cần ảnh gì?", qaTopic)).file(photo())));
        awaitBlog(id, "PUBLISHED");

        assertThat(rows(owner)).containsExactly("CONTENT_PUBLISHED QUESTION " + id
                + " Câu hỏi “Gia hạn visa cần ảnh gì?” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].link").value("/qa/" + id));
    }

    @Test
    void aPostWhosePhotoFailsIsAnnouncedAsRejectedOnly() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Ảnh bị chặn", postTopic))
                .file(photo())));
        awaitBlog(id, "REJECTED");

        assertThat(rows(owner)).containsExactly("CONTENT_REJECTED BLOG " + id + " Bài viết “Ảnh bị chặn” chưa được"
                + " đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.");
        assertThat(points(owner)).isZero();
        // The author still opens it (the page says why it is not public)
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].type").value("CONTENT_REJECTED"))
                .andExpect(jsonPath("$.data.items[0].link").value("/blog/" + id));
    }

    @Test
    void aPostWithoutMediaIsPublishedAtOnceWithoutANotification() throws Exception {
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Không có ảnh", postTopic)))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED")));
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(rows(owner)).isEmpty();
    }

    @Test
    void aScanThatCannotAnswerKeepsThePostWaitingWithoutANotificationAndTheBellAsksAgainSoon() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Đang chờ quét", postTopic))
                .file(photo())));
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM moderation_scans s JOIN blog_media bm ON bm.media_id = s.target_id
                WHERE s.target_type = 'MEDIA' AND bm.blog_id = ?""", Integer.class, id) > 0);

        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        assertThat(rows(owner)).isEmpty();
        mvc.perform(get("/api/me/notifications/summary").session(session(owner)))
                .andExpect(jsonPath("$.data.processing").value(1))
                .andExpect(jsonPath("$.data.unread").value(0));
    }

    @Test
    void anEditWithANewPhotoIsAnnouncedAsAnEditWithoutNewPoints() throws Exception {
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Bài sẽ sửa", postTopic))
                .file(photo())));
        awaitBlog(id, "PUBLISHED");
        long kept = jdbc.queryForObject("SELECT media_id FROM blog_media WHERE blog_id = ?", Long.class, id);

        MockMultipartFile change = new MockMultipartFile("post", "", "application/json", ("{\"title\":\"Bài đã sửa\","
                + "\"content\":\"Thêm một ảnh\",\"topicIds\":[" + postTopic + "],\"placeIds\":[],\"keepMediaIds\":["
                + kept + "]}").getBytes(StandardCharsets.UTF_8));
        send(owner, multipart(HttpMethod.PUT, "/api/blogs/" + id).file(change).file(photo()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        await().atMost(Duration.ofSeconds(15)).until(() -> rows(owner).size() == 2);

        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(rows(owner)).containsExactly(
                "CONTENT_PUBLISHED BLOG " + id
                        + " Bài viết “Bài sẽ sửa” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.",
                "CONTENT_PUBLISHED BLOG " + id + " Bản sửa bài viết “Bài đã sửa” đã qua kiểm duyệt ảnh/video và đã"
                        + " được đăng.");
        assertThat(points(owner)).isEqualTo(10);
    }

    // ---------------------------------------------------------------- reviews

    @Test
    void aPlaceReviewWhosePhotoPassesIsAnnouncedAndOpensThePlaceOnTheMap() throws Exception {
        String name = "Quán " + UUID.randomUUID().toString().substring(0, 8);
        long place = place(name, "ACTIVE");
        MockMultipartFile review = new MockMultipartFile("review", "", "application/json",
                ("{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Quán sạch, phở ngon, giá ổn.\"}")
                        .getBytes(StandardCharsets.UTF_8));
        long id = created(send(owner, multipart("/api/reviews").file(review).file(photo()))
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        awaitReview(id, "PUBLISHED");

        assertThat(rows(owner)).containsExactly("CONTENT_PUBLISHED REVIEW " + id + " Review về “" + name
                + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].link").value("/map?place=" + place));
    }

    @Test
    void anEditedPlaceReviewThatFailsTellsTheReason() throws Exception {
        String name = "Quán " + UUID.randomUUID().toString().substring(0, 8);
        long place = place(name, "ACTIVE");
        MockMultipartFile review = new MockMultipartFile("review", "", "application/json",
                ("{\"placeId\":" + place + ",\"rating\":4,\"content\":\"Quán ổn, hơi đông người.\"}")
                        .getBytes(StandardCharsets.UTF_8));
        long id = created(send(owner, multipart("/api/reviews").file(review)));
        assertThat(rows(owner)).isEmpty();      // no photo: published at once

        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        MockMultipartFile change = new MockMultipartFile("review", "", "application/json",
                "{\"rating\":4,\"content\":\"Quán ổn, thêm ảnh món ăn.\",\"keepMediaIds\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
        send(owner, multipart(HttpMethod.PUT, "/api/reviews/" + id).file(change).file(photo()))
                .andExpect(status().isOk());
        awaitReview(id, "REJECTED");

        assertThat(rows(owner)).containsExactly("CONTENT_REJECTED REVIEW " + id + " Review về “" + name
                + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa review rồi gửi lại.");
    }

    @Test
    void theFirstReviewOfAPinSaysItIsShownOnceTheModeratorApproves() throws Exception {
        String name = "Pin " + UUID.randomUUID().toString().substring(0, 8);
        double lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        double lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        MockMultipartFile pin = new MockMultipartFile("pin", "", "application/json", ("{\"lat\":" + lat + ",\"lng\":"
                + lng + ",\"nameEn\":\"" + name + "\",\"categoryId\":\"food\",\"rating\":5,"
                + "\"content\":\"Cơm nhà làm, chủ quán dễ thương.\",\"confirmDuplicate\":true}")
                .getBytes(StandardCharsets.UTF_8));
        String body = send(owner, multipart("/api/map/pins").file(pin).file(photo()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        long reviewId = Long.parseLong(body.replaceAll(".*\"reviewId\":(\\d+).*", "$1"));
        awaitReview(reviewId, "PUBLISHED");

        assertThat(rows(owner)).containsExactly("CONTENT_PUBLISHED REVIEW " + reviewId + " Review đầu tiên về “"
                + name + "” đã qua kiểm duyệt ảnh/video. Review sẽ hiển thị cho mọi người khi ghim được Moderator"
                + " duyệt. +10 điểm.");
    }

    @Test
    void anEventReviewWhosePhotoPassesIsAnnouncedAndOpensTheEvent() throws Exception {
        String title = "Lễ hội " + UUID.randomUUID().toString().substring(0, 8);
        long event = event(title);
        try {
            MockMultipartFile review = new MockMultipartFile("review", "", "application/json",
                    "{\"rating\":5,\"content\":\"Lễ hội rất vui, đông người.\"}".getBytes(StandardCharsets.UTF_8));
            long id = created(send(owner, multipart("/api/events/" + event + "/reviews").file(review).file(photo()))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING")));
            awaitReview(id, "PUBLISHED");

            assertThat(rows(owner)).containsExactly("CONTENT_PUBLISHED REVIEW " + id + " Review “" + title
                    + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
            mvc.perform(get("/api/me/notifications").session(session(owner)))
                    .andExpect(jsonPath("$.data.items[0].link").value("/events/" + event));

            // The event deleted: the notification stays, with nothing to open
            jdbc.update("UPDATE events SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", event);
            mvc.perform(get("/api/me/notifications").session(session(owner)))
                    .andExpect(jsonPath("$.data.items[0].link").value(nullValue()));
        } finally {
            jdbc.update("UPDATE events SET deleted_at = UTC_TIMESTAMP() WHERE id = ? AND deleted_at IS NULL", event);
        }
    }

    // ---------------------------------------------------------------- the bell's API

    @Test
    void theListIsTheUsersOwnNewestFirstTwentyAPage() throws Exception {
        for (int i = 1; i <= 23; i++) {
            notifications.send(owner, NotificationService.NEW_COMMENT, "Thông báo " + i, "BLOG", null);
        }
        notifications.send(other, NotificationService.NEW_COMMENT, "Của người khác", "BLOG", null);

        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.items[0].content").value("Thông báo 23"))
                .andExpect(jsonPath("$.data.items[19].content").value("Thông báo 4"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.unread").value(23));
        mvc.perform(get("/api/me/notifications").param("page", "1").session(session(owner)))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[2].content").value("Thông báo 1"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/api/me/notifications").param("page", "-3").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].content").value("Thông báo 23"));
        mvc.perform(get("/api/me/notifications").session(session(other)))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("Của người khác"));
    }

    @Test
    void theSummaryGivesTheCountTheNewestIdAndWhatCameAfterTheIdAsked() throws Exception {
        mvc.perform(get("/api/me/notifications/summary").session(session(owner)))
                .andExpect(jsonPath("$.data.unread").value(0))
                .andExpect(jsonPath("$.data.latestId").value(0))
                .andExpect(jsonPath("$.data.processing").value(0))
                .andExpect(jsonPath("$.data.fresh.length()").value(0));

        notifications.send(owner, NotificationService.NEW_COMMENT, "Cũ", "BLOG", null);
        long seen = latestId(owner);
        for (int i = 1; i <= 7; i++) {
            notifications.send(owner, NotificationService.CONTENT_PUBLISHED, "Mới " + i, "BLOG", null);
        }
        // Without "after" (a new tab): only counted
        mvc.perform(get("/api/me/notifications/summary").session(session(owner)))
                .andExpect(jsonPath("$.data.unread").value(8))
                .andExpect(jsonPath("$.data.latestId").value(latestId(owner)))
                .andExpect(jsonPath("$.data.fresh.length()").value(0));
        // After the id the tab has seen: the newest five, newest first
        mvc.perform(get("/api/me/notifications/summary").param("after", String.valueOf(seen)).session(session(owner)))
                .andExpect(jsonPath("$.data.fresh.length()").value(5))
                .andExpect(jsonPath("$.data.fresh[0].content").value("Mới 7"))
                .andExpect(jsonPath("$.data.fresh[4].content").value("Mới 3"))
                .andExpect(jsonPath("$.data.fresh[0].type").value("CONTENT_PUBLISHED"));
        mvc.perform(get("/api/me/notifications/summary").param("after", String.valueOf(latestId(owner)))
                        .session(session(owner)))
                .andExpect(jsonPath("$.data.fresh.length()").value(0));
    }

    @Test
    void readMarksOnlyTheUsersOwnUpToTheIdShown() throws Exception {
        notifications.send(owner, NotificationService.NEW_COMMENT, "Một", "BLOG", null);
        notifications.send(owner, NotificationService.NEW_COMMENT, "Hai", "BLOG", null);
        long shown = latestId(owner);
        notifications.send(owner, NotificationService.NEW_COMMENT, "Ba (đến sau khi trang tải)", "BLOG", null);
        notifications.send(other, NotificationService.NEW_COMMENT, "Của người khác", "BLOG", null);

        mvc.perform(withCsrf(post("/api/me/notifications/read")).session(session(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":" + shown + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(1));
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.items[1].read").value(true))
                .andExpect(jsonPath("$.data.items[2].read").value(true));
        assertThat(unread(other)).isEqualTo(1);

        // Without an id (or a body): all of them
        mvc.perform(withCsrf(post("/api/me/notifications/read")).session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(0));
        assertThat(unread(other)).isEqualTo(1);
    }

    @Test
    void onlyASignedInUserReadsTheirNotificationsAndReadNeedsTheCsrfToken() throws Exception {
        mvc.perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/notifications/summary")).andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(post("/api/me/notifications/read"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/notifications/read").session(session(owner))).andExpect(status().isForbidden());

        // A locked account is signed out by the session check
        long locked = user("USER");
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", locked);
        mvc.perform(get("/api/me/notifications").session(session(locked))).andExpect(status().isUnauthorized());
    }

    @Test
    void linksFollowThePostThatStillExists() throws Exception {
        long id = created(send(owner, multipart("/api/blogs").file(blogPart("post", "Sẽ xóa", postTopic))));
        notifications.send(owner, NotificationService.NEW_COMMENT, "Có bình luận", "BLOG", id);
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].link").value("/blog/" + id));

        mvc.perform(withCsrf(delete("/api/blogs/" + id)).session(session(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/me/notifications").session(session(owner)))
                .andExpect(jsonPath("$.data.items[0].content").value("Có bình luận"))
                .andExpect(jsonPath("$.data.items[0].link").value(nullValue()));
    }

    // ---------------------------------------------------------------- helpers

    /** "TYPE REF_TYPE REF_ID content" of each notification of the user, oldest first. */
    private List<String> rows(long userId) {
        return jdbc.queryForList("""
                SELECT CONCAT(type, ' ', ref_type, ' ', ref_id, ' ', content) FROM notifications
                WHERE user_id = ? ORDER BY id""", String.class, userId);
    }

    private long latestId(long userId) {
        return jdbc.queryForObject("SELECT MAX(id) FROM notifications WHERE user_id = ?", Long.class, userId);
    }

    private int unread(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = FALSE",
                Integer.class, userId);
    }

    private MockMultipartFile blogPart(String part, String title, long topic) {
        return new MockMultipartFile(part, "", "application/json", ("{\"title\":\"" + title
                + "\",\"content\":\"Ảnh mình chụp hôm nay\",\"topicIds\":[" + topic + "],\"placeIds\":[]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions send(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(withCsrf(request).session(session(userId)));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private void awaitBlog(long id, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status.equals(blogStatus(id)));
    }

    private String blogStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, id);
    }

    private void awaitReview(long id, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status.equals(
                jdbc.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, id)));
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long place(String name, String status) {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', ?, ?, 'TEAM', ?, ?)""",
                name, 33 + ThreadLocalRandom.current().nextDouble(3), 131 + ThreadLocalRandom.current().nextDouble(8),
                status, staff);
        return jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
    }

    /** An event that started yesterday (reviews are open). */
    private long event(String title) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        jdbc.update("""
                INSERT INTO events (title, start_date, end_date, location_name, created_by)
                VALUES (?, ?, ?, 'Công viên Ueno', ?)""",
                title, java.sql.Date.valueOf(today.minusDays(1)), java.sql.Date.valueOf(today.plusDays(1)), staff);
        return jdbc.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
    }

    private long user(String role) {
        String email = "notif-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Notif Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private MockHttpSession session(long userId) {
        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Notif Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        img.setRGB(10, 10, ThreadLocalRandom.current().nextInt(0xFFFFFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }
}
