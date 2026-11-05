package com.sep490.vsa.feature.home;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.home.HomeDtos.FeedReview;
import com.sep490.vsa.feature.home.HomeDtos.ReviewFeed;
import com.sep490.vsa.feature.home.HomeDtos.ScopeCount;
import com.sep490.vsa.feature.home.HomeDtos.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Page "Review mới" ({@code /reviews}), opened from "Review mới quanh …" on the member Home: every review that card
 * draws from, newest first, around the viewer's area, in their prefecture or everywhere. Guests and members without an
 * area get "Tất cả" only. Read-only SQL over the review feature's tables, like {@link HomeService}.
 */
@Service
@Transactional(readOnly = true)
public class HomeReviewService {

    /**
     * What every list of reviews on Home shows: published reviews of places (not events) that are on the Map (ACTIVE)
     * by active accounts ({@link ActiveAuthors}); aliases {@code r} = reviews, {@code p} = places.
     */
    static final String SHOWN = "r.status = 'PUBLISHED' AND r.event_id IS NULL AND p.status = 'ACTIVE' AND "
            + ActiveAuthors.of("r");
    static final int PAGE_SIZE = 10;
    /** A "newest" list is not read 2,000 reviews deep, and a deep OFFSET is costly ("cần chốt"). */
    static final int MAX_PAGE = 199;
    static final int EXCERPT = 240;

    enum Scope { AREA, PREFECTURE, ALL }

    private final NamedParameterJdbcTemplate jdbc;
    private final String mediaBaseUrl;

    public HomeReviewService(NamedParameterJdbcTemplate jdbc,
                             @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.jdbc = jdbc;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    /** The viewer's area and prefecture, all null for a guest or a member who has not chosen an area. */
    private record Viewer(long id, Long areaId, String areaName, String prefectureCode, String prefectureName) {
    }

    /**
     * One page of reviews by other people, newest first. {@code scope} is AREA, PREFECTURE or ALL; one the viewer
     * cannot use (no area, a guest) or none at all picks the first scope holding reviews, so the page never opens empty
     * when there is something to read.
     */
    public ReviewFeed recent(Long viewerId, String scope, int page) {
        Viewer viewer = viewer(viewerId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewerId", viewer.id())
                .addValue("areaId", viewer.areaId())
                .addValue("prefecture", viewer.prefectureCode());

        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS total, COALESCE(SUM(p.area_id = :areaId), 0) AS in_area,
                       COALESCE(SUM(pa.prefecture_code = :prefecture), 0) AS in_prefecture
                FROM reviews r JOIN places p ON p.id = r.place_id LEFT JOIN areas pa ON pa.id = p.area_id
                WHERE %s AND r.author_id <> :viewerId""".formatted(SHOWN), params);
        List<ScopeCount> scopes = new ArrayList<>();
        if (viewer.areaId() != null) {
            scopes.add(new ScopeCount(Scope.AREA.name(), ((Number) counts.get("in_area")).longValue()));
        }
        if (viewer.prefectureCode() != null) {
            scopes.add(new ScopeCount(Scope.PREFECTURE.name(), ((Number) counts.get("in_prefecture")).longValue()));
        }
        scopes.add(new ScopeCount(Scope.ALL.name(), ((Number) counts.get("total")).longValue()));

        String chosen = scopes.stream().map(ScopeCount::code).filter(code -> code.equals(scope)).findFirst()
                .orElseGet(() -> scopes.stream().filter(s -> s.count() > 0).map(ScopeCount::code).findFirst()
                        .orElse(scopes.get(0).code()));
        String where = switch (Scope.valueOf(chosen)) {
            case AREA -> " AND p.area_id = :areaId";
            case PREFECTURE -> " AND pa.prefecture_code = :prefecture";
            case ALL -> "";
        };
        int shownPage = Math.min(Math.max(0, page), MAX_PAGE);
        params.addValue("limit", PAGE_SIZE + 1).addValue("offset", shownPage * PAGE_SIZE);

        record Row(long id, long placeId, String placeName, String categoryName, String categoryIcon,
                   String placeArea, boolean closed, String near, int rating, long authorId, String authorName,
                   String content, int likeCount, Instant createdAt) {
        }
        List<Row> rows = jdbc.query("""
                SELECT r.id, p.id, p.name_vi, c.name_vi, c.icon, pa.display_name, p.is_closed,
                       CASE WHEN p.area_id = :areaId THEN 'AREA' WHEN pa.prefecture_code = :prefecture THEN 'PREFECTURE'
                       END AS near,
                       r.rating, u.id, u.display_name, r.content, r.like_count, r.created_at
                FROM reviews r JOIN places p ON p.id = r.place_id JOIN place_categories c ON c.id = p.category_id
                JOIN users u ON u.id = r.author_id LEFT JOIN areas pa ON pa.id = p.area_id
                WHERE %s AND r.author_id <> :viewerId%s
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT :limit OFFSET :offset""".formatted(SHOWN, where), params,
                (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getInt(9), rs.getLong(10),
                        rs.getString(11), rs.getString(12), rs.getInt(13), rs.getTimestamp(14).toInstant()));
        boolean hasMore = rows.size() > PAGE_SIZE && shownPage < MAX_PAGE;
        List<Row> pageRows = rows.size() > PAGE_SIZE ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, List<MediaFile>> media = media(pageRows.stream().map(Row::id).toList());
        Map<Long, List<Tag>> tags = tags(pageRows.stream().map(Row::authorId).distinct().toList());
        List<FeedReview> items = pageRows.stream().map(row -> {
            List<MediaFile> files = media.getOrDefault(row.id(), List.of());
            String photo = files.stream().filter(f -> "IMAGE".equals(f.type())).map(f -> mediaBaseUrl + f.storageKey())
                    .findFirst().orElse(null);
            return new FeedReview(row.id(), row.placeId(), row.placeName(), row.categoryName(), row.categoryIcon(),
                    row.placeArea(), row.closed(), row.near(), row.rating(), row.authorName(),
                    tags.getOrDefault(row.authorId(), List.of()), HomeService.excerpt(row.content(), EXCERPT),
                    row.likeCount(), row.createdAt(), photo, files.size());
        }).toList();
        return new ReviewFeed(chosen, viewer.areaName(), viewer.prefectureName(), scopes, shownPage, hasMore, items);
    }

    private Viewer viewer(Long viewerId) {
        if (viewerId == null) {
            return new Viewer(-1, null, null, null, null);
        }
        return jdbc.query("""
                SELECT u.area_id, a.display_name, a.prefecture_code, pf.name_en FROM users u
                LEFT JOIN areas a ON a.id = u.area_id LEFT JOIN prefectures pf ON pf.code = a.prefecture_code
                WHERE u.id = :id""", new MapSqlParameterSource("id", viewerId),
                (rs, i) -> new Viewer(viewerId, rs.getObject(1, Long.class), rs.getString(2), rs.getString(3),
                        rs.getString(4)))
                .stream().findFirst().orElse(new Viewer(viewerId, null, null, null, null));
    }

    private record MediaFile(String type, String storageKey) {
    }

    /** Each review's photos and videos that passed the scan, in their order. */
    private Map<Long, List<MediaFile>> media(List<Long> reviewIds) {
        Map<Long, List<MediaFile>> out = new HashMap<>();
        if (reviewIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT rm.review_id, m.media_type, m.storage_key FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id IN (:ids) AND m.deleted_at IS NULL AND m.scan_status = 'PASSED'
                ORDER BY rm.review_id, rm.sort_order, m.id""", new MapSqlParameterSource("ids", reviewIds),
                (ResultSet rs) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new MediaFile(rs.getString(2), rs.getString(3)));
                });
        return out;
    }

    /** Approved, not revoked identity tags whose tag type is still active (like the Blog's tags). */
    private Map<Long, List<Tag>> tags(List<Long> userIds) {
        Map<Long, List<Tag>> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT ut.user_id, t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id IN (:ids) AND ut.revoked_at IS NULL AND t.is_active ORDER BY ut.user_id, t.id""",
                new MapSqlParameterSource("ids", userIds),
                (ResultSet rs) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Tag(rs.getString(2), rs.getString(3)));
                });
        return out;
    }
}
