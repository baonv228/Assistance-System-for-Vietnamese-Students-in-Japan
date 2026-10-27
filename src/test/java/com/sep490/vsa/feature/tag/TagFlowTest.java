package com.sep490.vsa.feature.tag;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Identity tags (UC-U09, UC-M01, UC-M02) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TagFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TagService tags;
    @MockBean
    private ImageModerator moderator;

    private long user;
    private long mod;
    private long kouhai;
    private long studentCard;
    private long japanese;

    @BeforeEach
    void setUp() {
        user = user("USER");
        mod = user("MODERATOR");
        kouhai = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Kouhai'", Long.class);
        studentCard = jdbc.queryForObject("SELECT MIN(id) FROM tag_proof_types WHERE tag_type_id = ?", Long.class, kouhai);
        japanese = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Người Nhật'", Long.class);
    }

    @Test
    void aRequestNeedsATagAProofTypeAPhotoAndConsent() throws Exception {
        mvc.perform(get("/api/me/tag-requests/compose").session(session(user, "USER")))
                .andExpect(jsonPath("$.data.tags[?(@.name == 'Kouhai')].proofTypes[0].name").value("Thẻ sinh viên (学生証)"))
                .andExpect(jsonPath("$.data.tags[?(@.name == 'Người Nhật')].selfieOnly").value(true))
                .andExpect(jsonPath("$.data.pending").doesNotExist());
        send(user, "{}", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.tagId").value("Hãy chọn một tag."))
                .andExpect(jsonPath("$.data.photo").value("Hãy chọn ảnh minh chứng."))
                .andExpect(jsonPath("$.data.consent").value("Bạn cần đồng ý cho Kiểm duyệt viên xem ảnh."));
        send(user, "{\"tagId\":" + kouhai + ",\"consent\":true}", photo())
                .andExpect(jsonPath("$.data.proofTypeId").value("Hãy chọn loại minh chứng."));
        send(user, "{\"tagId\":" + japanese + ",\"consent\":true}", null)
                .andExpect(jsonPath("$.data.photo").value("Hãy chọn ảnh chân dung."));
        send(mod, "{\"tagId\":" + kouhai + ",\"proofTypeId\":" + studentCard + ",\"consent\":true}", photo())
                .andExpect(status().isForbidden());
    }

    @Test
    void anApprovedRequestGrantsTheTagAndDeletesThePhoto() throws Exception {
        long id = submitKouhai();
        // One waiting request at a time
        send(user, "{\"tagId\":" + japanese + ",\"consent\":true}", photo()).andExpect(status().isConflict());
        mvc.perform(get("/api/me/tag-requests/compose").session(session(user, "USER")))
                .andExpect(jsonPath("$.data.pending.tagName").value("Kouhai"));

        // The photo is never scanned, never public, and only the Moderator API shows it
        verify(moderator, never()).check(any(byte[].class));
        String key = jdbc.queryForObject("""
                SELECT m.storage_key FROM tag_requests r JOIN media m ON m.id = r.proof_media_id WHERE r.id = ?""",
                String.class, id);
        assertThat(key).startsWith("tagproof/");
        mvc.perform(get("/media/" + key)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + key).session(session(mod, "MODERATOR"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/moderator/tag-requests/" + id + "/photo").session(session(user, "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/moderator/tag-requests/" + id + "/photo").session(session(mod, "MODERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        mvc.perform(get("/api/moderator/tag-requests").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[?(@.id == " + id + ")].daysLeft").value(14));

        act(post("/api/moderator/tag-requests/" + id + "/approve"), "").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_tags WHERE user_id = ? AND tag_type_id = ? AND revoked_at IS NULL",
                Integer.class, user, kouhai)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE storage_key = ?", Boolean.class, key))
                .isTrue();
        assertThat(lastNotification()).isEqualTo("Bạn đã được cấp tag Kouhai.");
        mvc.perform(get("/api/moderator/tag-requests/" + id + "/photo").session(session(mod, "MODERATOR")))
                .andExpect(status().isConflict());
        act(post("/api/moderator/tag-requests/" + id + "/approve"), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Yêu cầu này đã được xử lý."));
        mvc.perform(get("/api/me/tag-requests/compose").session(session(user, "USER")))
                .andExpect(jsonPath("$.data.tags[?(@.name == 'Kouhai')].owned").value(true));
    }

    @Test
    void aRejectedRequestNeedsAReasonAndCanBeSentAgain() throws Exception {
        long id = submitKouhai();
        act(post("/api/moderator/tag-requests/" + id + "/reject"), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy chọn lý do từ chối."));
        act(post("/api/moderator/tag-requests/" + id + "/reject"), "{\"reason\":\"Ảnh mờ\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', reject_reason) FROM tag_requests WHERE id = ?",
                String.class, id)).isEqualTo("REJECTED/Ảnh mờ");
        assertThat(lastNotification()).isEqualTo("Yêu cầu tag Kouhai bị từ chối: Ảnh mờ. Bạn có thể gửi lại.");
        submitKouhai();
    }

    @Test
    void aRequestWaitingFourteenDaysExpires() throws Exception {
        long id = submitKouhai();
        jdbc.update("UPDATE tag_requests SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), id);
        tags.expireOld();
        assertThat(jdbc.queryForObject("SELECT status FROM tag_requests WHERE id = ?", String.class, id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT m.deleted_at IS NOT NULL FROM tag_requests r JOIN media m ON m.id = r.proof_media_id WHERE r.id = ?""",
                Boolean.class, id)).isTrue();
        assertThat(lastNotification()).startsWith("Yêu cầu tag Kouhai đã quá 14 ngày chưa được duyệt");
    }

    // ---------------------------------------------------------------- helpers

    private long submitKouhai() throws Exception {
        send(user, "{\"tagId\":" + kouhai + ",\"proofTypeId\":" + studentCard + ",\"consent\":true}", photo())
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Đã gửi yêu cầu tag."));
        return jdbc.queryForObject("SELECT MAX(id) FROM tag_requests WHERE user_id = ?", Long.class, user);
    }

    private ResultActions send(long userId, String json, MockMultipartFile photo) throws Exception {
        var request = multipart("/api/me/tag-requests")
                .file(new MockMultipartFile("request", "", "application/json", json.getBytes(StandardCharsets.UTF_8)));
        if (photo != null) {
            request.file(photo);
        }
        return mvc.perform(request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .session(session(userId, userId == mod ? "MODERATOR" : "USER")));
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .session(session(mod, "MODERATOR")).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String lastNotification() {
        return jdbc.queryForObject("SELECT content FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user);
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("photo", "the.png", "image/png", out.toByteArray());
    }

    private long user(String role) {
        String email = "tag-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Tag Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Tag Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
