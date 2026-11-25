package com.sep490.vsa.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Base of the use case tests: one JUnit class per use case of the SRS (Report 3, "II. Use Case Specifications",
 * section 1 Member Feature), each test named after the flow it checks (normal flow, alternative flows, boundaries).
 * <p>
 * The tests go through the real REST API (MockMvc, Spring Security, CSRF) and the real services against MySQL, on
 * their own local database {@code asvsj_uc_test} (created and migrated by Flyway on the first run, seeded by
 * DevDataSeeder because the profile is "local"). No test calls the internet: OpenStreetMap is off, the Gemini key is
 * blank (src/test/resources/config) and the OpenAI photo scanner is a mock that passes every photo unless a test
 * says otherwise. Every subclass shares one Spring context (same properties and mocks), so keep both here.
 * <p>
 * The database keeps the rows of earlier runs: every test creates its own accounts and marks its posts with a
 * unique {@link #token()}, and lists are checked through a keyword search on that token.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_uc_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Tag("usecase")
public abstract class UseCaseTestBase {

    protected static final String CSRF = "uc-test-csrf-token";
    protected static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    // Messages of the SRS that several use cases share (Report 3, "Messages")
    protected static final String MSG14 = "Đã có lỗi xảy ra. Vui lòng thử lại sau.";
    protected static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    protected static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    protected static final String MSG38 = "Cảm ơn bạn. Báo cáo đã được gửi.";
    protected static final String MSG39 = "Bạn đã báo cáo nội dung này rồi.";
    protected static final String MSG40 = "Hãy chọn lý do báo cáo.";
    protected static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    @MockBean
    protected ImageModerator imageModerator;

    @BeforeEach
    void photosPassTheScanByDefault() {
        when(imageModerator.engine()).thenReturn("OPENAI");
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{\"flagged\":false}"));
    }

    // ================================================================ unique text

    /** A word no other row holds (letters and digits only), to mark and find this test's rows. */
    protected static String token() {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder("uct");
        for (int i = 0; i < 10; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ================================================================ accounts

    /** An ACTIVE Member (role USER) whose account is {@code daysOld} days old, with no area, stage or tag. */
    protected long member(int daysOld) {
        return account("USER", daysOld);
    }

    /** An ACTIVE Member older than 7 days: the new-account limits do not apply. */
    protected long member() {
        return member(30);
    }

    /** An ACTIVE account of any role (USER, MODERATOR, ACADEMIC_MANAGER, ADMIN). */
    protected long account(String role, int daysOld) {
        String email = "uc-" + UUID.randomUUID() + "@test.local";
        String name = "UC Tester " + ThreadLocalRandom.current().nextInt(1000, 9999);
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, email_verified_at, created_at)
                VALUES (?, ?, ?, 'ACTIVE', TRUE, ?, ?)""",
                email, name, role, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    protected String displayName(long userId) {
        return jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, userId);
    }

    /** Gives the identity tag "Mentor" (links, phone numbers and Zalo/LINE ids allowed, BR-22). */
    protected void grantMentorTag(long userId) {
        long tag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        long granter = account("MODERATOR", 30);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", userId, tag, granter);
    }

    /** LOCKED or DELETED (an Admin lock or the user's own "Xóa tài khoản"), as the hidden-author rule reads it. */
    protected void setStatus(long userId, String status) {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, userId);
    }

    protected int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    // ================================================================ topics and places

    /** Id of an active Blog topic by its name ("Kinh nghiệm", "Cảnh báo lừa đảo", "Khác", …). */
    protected long topic(String name) {
        return jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
    }

    /** A new ACTIVE TEAM place in Tokyo (inside Japan, with coordinates), named with the given text. */
    protected long place(String name) {
        long creator = account("MODERATOR", 30);
        jdbc.update("""
                INSERT INTO places (name_vi, name_en, category_id, address, lat, lng, source, status, created_by)
                VALUES (?, ?, (SELECT MIN(id) FROM place_categories), 'Tokyo', 35.7280, 139.7700, 'TEAM', 'ACTIVE', ?)""",
                name, name, creator);
        return jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, name);
    }

    // ================================================================ posts and comments written straight to the DB

    /**
     * A post (kind POST) or question (kind QA) stored without the API, for tests that need rows in a given state
     * or with a given age. Counters start at 0; use the API for likes and comments so the counters stay right.
     */
    protected long insertBlog(long authorId, String kind, String title, String content, String status,
                              Instant createdAt, long... topicIds) {
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                authorId, kind, title, content, status, Timestamp.from(createdAt), Timestamp.from(createdAt));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM blogs WHERE author_id = ?", Long.class, authorId);
        for (long topicId : topicIds) {
            jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) VALUES (?, ?)", id, topicId);
        }
        return id;
    }

    /** A PUBLISHED post written now, on the topic "Kinh nghiệm". */
    protected long publishedPost(long authorId, String title, String content) {
        return insertBlog(authorId, "POST", title, content, "PUBLISHED", Instant.now(), topic("Kinh nghiệm"));
    }

    /** A PUBLISHED question asked now, on the topic "Kinh nghiệm". */
    protected long publishedQuestion(long authorId, String title, String content) {
        return insertBlog(authorId, "QA", title, content, "PUBLISHED", Instant.now(), topic("Kinh nghiệm"));
    }

    protected String blogStatus(long blogId) {
        return jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, blogId);
    }

    protected boolean blogExists(long blogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE id = ?", Integer.class, blogId) > 0;
    }

    protected boolean commentExists(long commentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE id = ?", Integer.class, commentId) > 0;
    }

    protected Map<String, Object> commentRow(long commentId) {
        return jdbc.queryForMap("SELECT * FROM comments WHERE id = ?", commentId);
    }

    protected int commentCount(long blogId) {
        return jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, blogId);
    }

    /** Id of the newest comment this user wrote on this post (the one a test just sent). */
    protected long lastCommentId(long blogId, long authorId) {
        return jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE blog_id = ? AND author_id = ?",
                Long.class, blogId, authorId);
    }

    /** Lets the 15-second spacing of new comments pass for this user (Add Comment 4c). */
    protected void ageCommentsOf(long userId, Duration by) {
        jdbc.update("UPDATE comments SET created_at = DATE_SUB(created_at, INTERVAL ? SECOND) WHERE author_id = ?",
                by.toSeconds(), userId);
    }

    // ================================================================ requests

    /** A signed-in session for this account, with its role read from the database. */
    protected MockHttpSession session(long userId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT email, display_name, role FROM users WHERE id = ?", userId);
        String role = (String) row.get("role");
        AppUserPrincipal me = new AppUserPrincipal(userId, (String) row.get("email"), (String) row.get("display_name"),
                role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    /** Sends the request as this account ({@code null} = Guest), with the CSRF cookie and header of client.js. */
    protected ResultActions perform(MockHttpServletRequestBuilder request, Long asUserId) throws Exception {
        request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        if (asUserId != null) {
            request.session(session(asUserId));
        }
        return mvc.perform(request);
    }

    protected ResultActions getAs(Long asUserId, String url, Object... vars) throws Exception {
        return perform(get(url, vars), asUserId);
    }

    protected ResultActions postAs(Long asUserId, Object body, String url, Object... vars) throws Exception {
        MockHttpServletRequestBuilder request = post(url, vars);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return perform(request, asUserId);
    }

    protected ResultActions putAs(Long asUserId, Object body, String url, Object... vars) throws Exception {
        MockHttpServletRequestBuilder request = put(url, vars);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return perform(request, asUserId);
    }

    protected ResultActions deleteAs(Long asUserId, String url, Object... vars) throws Exception {
        return perform(delete(url, vars), asUserId);
    }

    /**
     * A multipart create (POST) or edit (PUT) of a post or question: part {@code partName} ("post" or "question")
     * holds the JSON, the files go as "images" / "video".
     */
    protected ResultActions multipartAs(Long asUserId, String method, String url, String partName, Object json,
                                        MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(url);
        if ("PUT".equals(method)) {
            request.with(r -> {
                r.setMethod("PUT");
                return r;
            });
        }
        request.file(new MockMultipartFile(partName, "", "application/json",
                mapper.writeValueAsString(json).getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return perform(request, asUserId);
    }

    /** The whole JSON answer ({@code success}, {@code message}, {@code data}). */
    protected JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return body(result).get("data");
    }

    // ================================================================ files

    /** A small PNG photo sent as part "images". */
    protected static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }

    /** A minimal MP4 (ftyp + moov/mvhd) of the given length, sent as part "video". */
    protected static MockMultipartFile video(int seconds) {
        ByteBuffer ftyp = ByteBuffer.allocate(16).putInt(16).put("ftypisom".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x200);
        ByteBuffer mvhd = ByteBuffer.allocate(108).putInt(108).put("mvhd".getBytes(StandardCharsets.ISO_8859_1))
                .putInt(0).putInt(0).putInt(0).putInt(1000).putInt(seconds * 1000);
        ByteBuffer moov = ByteBuffer.allocate(8 + 108).putInt(116).put("moov".getBytes(StandardCharsets.ISO_8859_1)).put(mvhd.array());
        ByteBuffer file = ByteBuffer.allocate(16 + 116).put(ftyp.array()).put(moov.array());
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", file.array());
    }

    // ================================================================ time

    protected static LocalDate japanToday() {
        return LocalDate.now(JAPAN);
    }

    protected static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    /** Repeats a string, for length boundaries ("x".repeat(2000) = exactly 2,000 characters). */
    protected static String chars(int length) {
        return "a".repeat(length);
    }
}
