package com.sep490.vsa.feature.moderation;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BR-22 for photos holding a QR code, end to end against MySQL ({@code asvsj_test}): the server reads the QR codes of
 * every uploaded photo (ZXing) and an account without an identity tag is refused at once, before anything is saved, in
 * every place a member uploads photos: posts, questions, edits, place reviews and their edits, new pins, event reviews
 * and their edits. A tagged account may post the photo, unless the code leads to a shortened link or holds a banned
 * keyword. Fresh accounts per test; the image scanner is a mock and no reader is configured.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class QrPhotoFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String ZALO = "http://zaloapp.com/qr/p/179af8age0av7";
    private static final String NEEDS_TAG = " bạn vừa chọn có mã QR. Chỉ tài khoản đã có tag định danh mới được đăng ảnh"
            + " có mã QR (mã QR được tính như link). Hãy cắt hoặc che mã QR rồi chọn lại ảnh.";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;
    @MockBean
    private MediaReader reader;

    private long member;       // 30 days old, no identity tag
    private long tagged;       // 30 days old, Mentor tag
    private long staff;        // Moderator
    private long topic;        // "Kinh nghiệm"

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{\"flagged\":false}"));
        when(reader.engine()).thenReturn("GEMINI");
        when(reader.enabled()).thenReturn(false);
        member = user("USER");
        tagged = user("USER");
        staff = user("MODERATOR");
        long mentorTag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", tagged, mentorTag, staff);
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
    }

    // ---------------------------------------------------------------- posts and questions

    @Test
    void anAccountWithoutATagCannotPostAPhotoWithAQrCodeAndNothingIsSaved() throws Exception {
        send("/api/blogs", "post", member, blog("Tàu nhanh lh em"), qrPhoto(ZALO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ảnh thứ 1" + NEEDS_TAG))
                .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
        // The second photo of three holds the code: it is the one named
        send("/api/blogs", "post", member, blog("Ba ảnh"), plainPhoto(), qrPhoto(ZALO), plainPhoto())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ảnh thứ 2" + NEEDS_TAG));
        // A code on a flyer in a bigger photo counts too, and so does a question
        send("/api/questions", "question", member, blog("Có ai biết chỗ này không"), flyerPhoto("https://line.me/ti/p/x1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ảnh thứ 1" + NEEDS_TAG));

        // Refused before anything is written: no post, no file, no daily usage
        assertThat(count("SELECT COUNT(*) FROM blogs WHERE author_id = ?", member)).isZero();
        assertThat(count("SELECT COUNT(*) FROM media WHERE owner_id = ?", member)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_daily_usage WHERE user_id = ?", member)).isZero();

        // The same post without the code goes through
        long id = created(send("/api/blogs", "post", member, blog("Ảnh thường"), plainPhoto()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));
    }

    @Test
    void aTaggedAccountMayPostTheCodeButNotAShortLinkOrABannedKeyword() throws Exception {
        long id = created(send("/api/blogs", "post", tagged, blog("Nhóm Zalo du học sinh"), qrPhoto(ZALO))
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(blogStatus(id)));

        send("/api/blogs", "post", tagged, blog("Link gọn"), qrPhoto("https://bit.ly/3abcXYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ảnh thứ 1 bạn vừa chọn có mã QR dẫn tới link rút gọn. Không"
                        + " dùng link rút gọn (bit.ly, tinyurl…)."));
        send("/api/blogs", "post", tagged, blog("Khuyến mãi"), qrPhoto("https://fb88.com/khuyen-mai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chưa đăng được: mã QR trong ảnh thứ 1 bạn vừa chọn chứa từ"
                        + " khóa không được phép “fb88”. Hãy dùng ảnh khác."));
        assertThat(count("SELECT COUNT(*) FROM blogs WHERE author_id = ?", tagged)).isEqualTo(1);
    }

    @Test
    void anEditCannotAddAPhotoWithAQrCode() throws Exception {
        long id = created(send("/api/blogs", "post", member, blog("Bài chữ"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED")));
        MockMultipartFile edit = part("post", "{\"title\":\"Bài chữ đã sửa\",\"content\":\"Thêm ảnh\",\"topicIds\":["
                + topic + "],\"placeIds\":[],\"keepMediaIds\":[]}");
        sendPut("/api/blogs/" + id, member, edit, qrPhoto(ZALO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
        assertThat(jdbc.queryForMap("SELECT title, status FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Bài chữ").containsEntry("status", "PUBLISHED");
        assertThat(count("SELECT COUNT(*) FROM blog_media WHERE blog_id = ?", id)).isZero();
    }

    // ---------------------------------------------------------------- reviews, pins, events

    @Test
    void placeReviewsAndTheirEditsRefuseAPhotoWithAQrCode() throws Exception {
        long place = place();
        send("/api/reviews", "review", member, "{\"placeId\":" + place + ",\"rating\":4,\"content\":\"Quán ngon, có"
                + " mã giảm giá.\"}", qrPhoto(ZALO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE author_id = ?", member)).isZero();

        // A review without photos, then an edit that adds the code
        long review = created(mvc.perform(withCsrf(post("/api/reviews")).session(session(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":" + place + ",\"rating\":4,\"content\":\"Quán ngon, giá hợp lý.\"}")));
        sendPut("/api/reviews/" + review, member, part("review", "{\"rating\":5,\"content\":\"Quán ngon, thêm ảnh"
                + " nhé.\",\"keepMediaIds\":[]}"), qrPhoto(ZALO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
        assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", Integer.class, review)).isEqualTo(4);

        // A tagged member reviews the place with the same photo
        long accepted = created(send("/api/reviews", "review", tagged, "{\"placeId\":" + place + ",\"rating\":5,"
                + "\"content\":\"Quét mã để vào nhóm Zalo của quán.\"}", qrPhoto(ZALO)));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(
                jdbc.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, accepted)));
    }

    @Test
    void aNewPinRefusesAPhotoWithAQrCode() throws Exception {
        double lat = 35.6 + ThreadLocalRandom.current().nextDouble(0.1);
        double lng = 139.6 + ThreadLocalRandom.current().nextDouble(0.1);
        String name = "Pin QR " + UUID.randomUUID().toString().substring(0, 8);
        send("/api/map/pins", "pin", member, "{\"lat\":" + lat + ",\"lng\":" + lng + ",\"nameEn\":\"" + name
                + "\",\"categoryId\":\"food\",\"rating\":5,\"content\":\"Cơm nhà làm, chủ quán dễ thương.\","
                + "\"confirmDuplicate\":true}", qrPhoto(ZALO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
        assertThat(count("SELECT COUNT(*) FROM places WHERE name_en = ?", name)).isZero();
    }

    @Test
    void eventReviewsAndTheirEditsRefuseAPhotoWithAQrCode() throws Exception {
        String title = "Lễ hội QR " + UUID.randomUUID().toString().substring(0, 8);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        jdbc.update("""
                INSERT INTO events (title, start_date, end_date, location_name, created_by)
                VALUES (?, ?, ?, 'Công viên Ueno', ?)""",
                title, java.sql.Date.valueOf(today.minusDays(1)), java.sql.Date.valueOf(today.plusDays(1)), staff);
        long event = jdbc.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
        try {
            send("/api/events/" + event + "/reviews", "review", member,
                    "{\"rating\":5,\"content\":\"Lễ hội vui, quét mã để nhận quà.\"}", qrPhoto(ZALO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
            assertThat(count("SELECT COUNT(*) FROM reviews WHERE author_id = ? AND event_id = ?", member, event))
                    .isZero();

            long review = created(send("/api/events/" + event + "/reviews", "review", member,
                    "{\"rating\":5,\"content\":\"Lễ hội vui, đông người.\"}"));
            sendPut("/api/event-reviews/" + review, member, part("review",
                    "{\"rating\":4,\"content\":\"Lễ hội vui, thêm ảnh.\",\"keepMediaIds\":[]}"), qrPhoto(ZALO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.images").value("Ảnh thứ 1" + NEEDS_TAG));
            assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", Integer.class, review))
                    .isEqualTo(5);
        } finally {
            jdbc.update("UPDATE events SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", event);
        }
    }

    // ---------------------------------------------------------------- helpers

    private String blog(String title) {
        return "{\"title\":\"" + title + "\",\"content\":\"Liên hệ em qua mã này\",\"topicIds\":[" + topic
                + "],\"placeIds\":[]}";
    }

    private ResultActions send(String url, String partName, long userId, String body, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(url).file(part(partName, body));
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(withCsrf(request).session(session(userId)));
    }

    private ResultActions sendPut(String url, long userId, MockMultipartFile json, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, url).file(json);
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(withCsrf(request).session(session(userId)));
    }

    private static MockMultipartFile part(String name, String json) {
        return new MockMultipartFile(name, "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private String blogStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long place() {
        String name = "Quán QR " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', ?, ?, 'TEAM', 'ACTIVE', ?)""",
                name, 33 + ThreadLocalRandom.current().nextDouble(3), 131 + ThreadLocalRandom.current().nextDouble(8),
                staff);
        return jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
    }

    private long user(String role) {
        String email = "qr-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'QR Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "QR Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    /** A screenshot-like photo of a QR code, as sent from a phone (PNG). */
    private static MockMultipartFile qrPhoto(String text) throws Exception {
        return png("qr.png", qr(text, 500));
    }

    /** A bigger photo with a small QR code on a flyer in one corner. */
    private static MockMultipartFile flyerPhoto(String text) throws Exception {
        BufferedImage photo = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(new Color(70, 110, 160));
        g.fillRect(0, 0, 1400, 1000);
        g.setColor(Color.WHITE);
        g.fillRect(1000, 600, 320, 340);
        g.drawImage(qr(text, 240), 1040, 650, null);
        g.dispose();
        return png("flyer.png", photo);
    }

    private static MockMultipartFile plainPhoto() throws Exception {
        BufferedImage photo = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(new Color(250, 190, 210));
        g.fillRect(0, 0, 320, 240);
        g.setColor(new Color(90, 160, 80));
        g.fillOval(60, 60, 200, 120);
        g.dispose();
        return png("anh.png", photo);
    }

    private static BufferedImage qr(String text, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 2));
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }

    private static MockMultipartFile png(String name, BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("images", name, "image/png", out.toByteArray());
    }
}
