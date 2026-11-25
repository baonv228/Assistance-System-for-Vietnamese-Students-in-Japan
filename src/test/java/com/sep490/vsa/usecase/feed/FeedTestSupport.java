package com.sep490.vsa.usecase.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared data of the "Dành cho bạn" tests (UC 1.1, UC 1.2). Each test makes its own living area in Okinawa (47) and
 * its own places, so a post linked to that area ranks first on the Home card whatever else the shared database holds;
 * lists are otherwise read through "Tìm trong bài dành cho bạn" ({@code /api/me/for-you?q=<token>}), which ranks with
 * the very SQL of the Home card (CLAUDE.md, "Xem tất cả").
 */
abstract class FeedTestSupport extends UseCaseTestBase {

    static final String REASON_AREA = "Gần khu vực của bạn";
    static final String REASON_PREFECTURE = "Cùng tỉnh với bạn";
    static final String REASON_STAGE = "Hợp giai đoạn của bạn";
    static final String REASON_INTEREST = "Chủ đề bạn quan tâm";

    /** A living area of this test only (prefecture 47 Okinawa), switched on or off. */
    protected long newArea(String name, boolean active) {
        jdbc.update("""
                INSERT INTO areas (prefecture_code, ward, station, display_name, is_active)
                VALUES ('47', 'UC test', 'UC test', ?, ?)""", name, active);
        return jdbc.queryForObject("SELECT id FROM areas WHERE display_name = ?", Long.class, name);
    }

    /** An ACTIVE TEAM place inside the given area. */
    protected long placeIn(long areaId, String name) {
        long creator = account("MODERATOR", 30);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, area_id, address, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), ?, 'Okinawa', 26.2124, 127.6809, 'TEAM', 'ACTIVE', ?)""",
                name, areaId, creator);
        return jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, name);
    }

    protected void link(long blogId, long placeId) {
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", blogId, placeId);
    }

    /** Writes the area and stage straight to the account (the UC 1.2 API is tested in its own class). */
    protected void setAreaAndStage(long userId, Long areaId, String stage) {
        jdbc.update("UPDATE users SET area_id = ?, stage = ? WHERE id = ?", areaId, stage, userId);
    }

    /** A PUBLISHED post on one topic, written at the given time. */
    protected long post(long authorId, String title, String topicName, Instant createdAt) {
        return insertBlog(authorId, "POST", title, "Nội dung " + title, "PUBLISHED", createdAt, topic(topicName));
    }

    /** Sets the counters the ranking reads (likes + 2 × comments). */
    protected void score(long blogId, int likes, int comments) {
        jdbc.update("UPDATE blogs SET like_count = ?, comment_count = ? WHERE id = ?", likes, comments, blogId);
    }

    protected static Instant hoursAgo(int hours) {
        return Instant.now().minusSeconds(hours * 3600L);
    }

    /** {@code data} of GET /api/me/home. */
    protected JsonNode home(long viewer) throws Exception {
        return data(getAs(viewer, "/api/me/home").andExpect(status().isOk()));
    }

    /** {@code data} of "Dành cho bạn" narrowed to the posts holding {@code q} ("Tìm trong bài dành cho bạn"). */
    protected JsonNode forYou(long viewer, String q) throws Exception {
        return data(getAs(viewer, "/api/me/for-you?q={q}", q).andExpect(status().isOk()));
    }

    protected JsonNode forYou(long viewer, String q, String scope, int page) throws Exception {
        return data(getAs(viewer, "/api/me/for-you?q={q}&scope={s}&page={p}", q, scope, page)
                .andExpect(status().isOk()));
    }

    protected static List<Long> ids(JsonNode posts) {
        List<Long> ids = new ArrayList<>();
        posts.forEach(p -> ids.add(p.get("id").asLong()));
        return ids;
    }

    protected static JsonNode byId(JsonNode posts, long id) {
        for (JsonNode p : posts) {
            if (p.get("id").asLong() == id) {
                return p;
            }
        }
        throw new AssertionError("Post " + id + " is not in " + posts);
    }

    /** Reason codes of one post, in the order the page shows them. */
    protected static List<String> reasonCodes(JsonNode post) {
        List<String> codes = new ArrayList<>();
        post.get("reasons").forEach(r -> codes.add(r.get("code").asText()));
        return codes;
    }

    protected long scopeCount(JsonNode feed, String code) {
        for (JsonNode s : feed.get("scopes")) {
            if (code.equals(s.get("code").asText())) {
                return s.get("count").asLong();
            }
        }
        return -1;
    }

    protected static List<String> scopeCodes(JsonNode feed) {
        List<String> codes = new ArrayList<>();
        feed.get("scopes").forEach(s -> codes.add(s.get("code").asText()));
        return codes;
    }

    protected void like(long blogId, long userId) {
        jdbc.update("INSERT INTO blog_likes (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(Instant.now()));
    }
}
