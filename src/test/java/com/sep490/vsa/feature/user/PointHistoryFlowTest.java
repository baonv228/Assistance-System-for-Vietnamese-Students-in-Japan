package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.security.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Lịch sử điểm" (UC-U10) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PointHistoryFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PointService points;
    @Autowired
    private TransactionTemplate tx;

    private long userId;

    @BeforeEach
    void setUp() {
        String email = "points-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Points Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @Test
    void theHistoryListsEveryChangeNewestFirstWithItsContent() throws Exception {
        mvc.perform(get("/api/me/points").session(session())).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES ('Quán Điểm', 'food', 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""", userId);
        long place = jdbc.queryForObject("SELECT MAX(id) FROM places WHERE created_by = ?", Long.class, userId);
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status) VALUES (?, ?, 5, 'Ngon và rẻ lắm.', 'PUBLISHED')""",
                userId, place);
        long review = jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ?", Long.class, userId);
        tx.executeWithoutResult(s -> {
            points.add(userId, PointService.REVIEW, "REVIEW_CREATED", "REVIEW", review);
            points.add(userId, PointService.PIN_APPROVED, "PIN_APPROVED", "PLACE", place);
        });
        mvc.perform(get("/api/me/points").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.points").value(25))
                .andExpect(jsonPath("$.data.items[0].reasonLabel").value("Ghim được duyệt"))
                .andExpect(jsonPath("$.data.items[0].link").value("/map?place=" + place))
                .andExpect(jsonPath("$.data.items[1].delta").value(10))
                .andExpect(jsonPath("$.data.items[1].title").value("Quán Điểm"));

        // Deleted content: the line stays, without a link
        jdbc.update("DELETE FROM reviews WHERE id = ?", review);
        mvc.perform(get("/api/me/points").session(session()))
                .andExpect(jsonPath("$.data.items[1].title").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].link").doesNotExist());
    }

    @Test
    void theTotalNeverGoesBelowZeroButTheHistoryKeepsEveryPoint() {
        tx.executeWithoutResult(s -> {
            points.add(userId, 5, "BEST_ANSWER", "COMMENT", 1L);
            points.add(userId, -20, "CONTENT_REMOVED", "BLOG", 1L);
        });
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(delta) FROM point_transactions WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(-15);
    }

    private MockHttpSession session() {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u@test.local", "Points Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
