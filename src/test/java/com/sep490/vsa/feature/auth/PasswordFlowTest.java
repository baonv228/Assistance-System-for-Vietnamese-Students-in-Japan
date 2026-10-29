package com.sep490.vsa.feature.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Quên mật khẩu" (UC-U05) and "Đổi mật khẩu" (UC-U06) against MySQL ({@code asvsj_test}); emails are mocked. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.public-url=https://senpai.example",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PasswordFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String OLD = "matkhau123";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @MockBean
    private PasswordMail mail;

    private String email;
    private long userId;

    @BeforeEach
    void setUp() {
        email = "pw-" + UUID.randomUUID() + "@test.local";
        userId = user(email, encoder.encode(OLD));
    }

    @Test
    void aResetLinkSetsANewPasswordOnce() throws Exception {
        String sent = "Nếu email này có tài khoản, chúng tôi đã gửi liên kết đặt lại mật khẩu. Liên kết có hiệu lực 30 phút.";
        forgot("nobody-" + UUID.randomUUID() + "@test.local").andExpect(jsonPath("$.message").value(sent));
        verify(mail, never()).sendResetLink(anyString(), anyString(), anyLong());

        jdbc.update("UPDATE users SET failed_login_count = 5, locked_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(20))), userId);
        forgot(email.toUpperCase()).andExpect(jsonPath("$.message").value(sent));
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mail).sendResetLink(eq(email), link.capture(), eq(30L));
        assertThat(link.getValue()).startsWith("https://senpai.example/reset-password?token=");
        String token = URLDecoder.decode(link.getValue().substring(link.getValue().indexOf('=') + 1), StandardCharsets.UTF_8);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM user_tokens WHERE user_id = ?", String.class, userId))
                .hasSize(64).isNotEqualTo(token);

        // Asked again within a minute: same answer, no second email
        forgot(email);
        verify(mail, times(1)).sendResetLink(anyString(), anyString(), anyLong());

        send(post("/api/auth/password/check"), null, "{\"token\":\"" + token + "\"}")
                .andExpect(jsonPath("$.data.email").value("p***@test.local"));
        send(post("/api/auth/password/reset"), null, reset(token, "ngan", "khac"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").value("Mật khẩu dài từ 8 đến 72 ký tự"))
                .andExpect(jsonPath("$.data.confirmPassword").value("Hai mật khẩu không khớp."));
        send(post("/api/auth/password/reset"), null, reset(token, "chikhongso", "chikhongso"))
                .andExpect(jsonPath("$.data.password").value("Mật khẩu phải có cả chữ và số"));
        send(post("/api/auth/password/reset"), null, reset(token, "moimoi2026", "moimoi2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đặt lại mật khẩu. Hãy đăng nhập bằng mật khẩu mới."));

        // The lock is lifted and the new password works; the link is used up
        login(OLD).andExpect(status().isUnauthorized());
        login("moimoi2026").andExpect(status().isOk());
        send(post("/api/auth/password/reset"), null, reset(token, "lanhai2026", "lanhai2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PasswordService.LINK_INVALID));
    }

    @Test
    void changingThePasswordSignsTheOtherDevicesOut() throws Exception {
        MockHttpSession phone = session(login(OLD));
        MockHttpSession laptop = session(login(OLD));

        send(put("/api/me/password"), laptop, change("sai12345", "moimoi2026", "moimoi2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.currentPassword").value("Mật khẩu hiện tại không đúng."));
        send(put("/api/me/password"), laptop, change(OLD, OLD, OLD))
                .andExpect(jsonPath("$.data.password").value("Mật khẩu mới phải khác mật khẩu hiện tại."));
        send(put("/api/me/password"), laptop, change(OLD, "moimoi2026", "moimoi2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Đã đổi mật khẩu."));
        verify(mail).sendChanged(eq(email), anyString());

        assertThat(phone.isInvalid()).isTrue();
        assertThat(laptop.isInvalid()).isFalse();
        login("moimoi2026").andExpect(status().isOk());
    }

    @Test
    void fiveWrongCurrentPasswordsLockTheAccountLikeALogin() throws Exception {
        MockHttpSession laptop = session(login(OLD));
        for (int i = 0; i < 4; i++) {
            send(put("/api/me/password"), laptop, change("sai12345", "moimoi2026", "moimoi2026"))
                    .andExpect(status().isBadRequest());
        }
        send(put("/api/me/password"), laptop, change("sai12345", "moimoi2026", "moimoi2026"))
                .andExpect(status().isLocked());
        login(OLD).andExpect(status().isLocked());
    }

    @Test
    void anAccountMadeWithGoogleSetsItsFirstPassword() throws Exception {
        // Signed in, then the password is taken away: the account is now like one made with Google
        MockHttpSession signedIn = session(login(OLD));
        jdbc.update("UPDATE users SET password_hash = NULL WHERE id = ?", userId);
        send(put("/api/me/password"), signedIn, change(null, "dautien2026", "dautien2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã tạo mật khẩu. Từ giờ bạn đăng nhập được bằng email và mật khẩu."));
        login("dautien2026").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions forgot(String address) throws Exception {
        return send(post("/api/auth/password/forgot"), null, "{\"email\":\"" + address + "\"}");
    }

    private ResultActions login(String password) throws Exception {
        return send(post("/api/auth/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static MockHttpSession session(ResultActions login) throws Exception {
        return (MockHttpSession) login.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private static String reset(String token, String password, String confirm) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + confirm + "\"}";
    }

    private static String change(String current, String password, String confirm) {
        return "{" + (current == null ? "" : "\"currentPassword\":\"" + current + "\",") + "\"password\":\"" + password
                + "\",\"confirmPassword\":\"" + confirm + "\"}";
    }

    private ResultActions send(MockHttpServletRequestBuilder request, MockHttpSession session, String json)
            throws Exception {
        MockHttpServletRequestBuilder r = request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(session == null ? r : r.session(session));
    }

    private long user(String address, String hash) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, ?, 'Password Test', 'USER', 'ACTIVE', TRUE, ?)""",
                address, hash, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, address);
    }
}
