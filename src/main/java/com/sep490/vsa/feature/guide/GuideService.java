package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.media.MediaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GuideService {
    private static final String SELECT = """
            SELECT g.*, s.title AS topic_name, u.display_name AS creator_name
            FROM guide_items g
            JOIN guide_stages s ON s.code = g.stage_code
            LEFT JOIN users u ON u.id = g.created_by
            """;

    private final JdbcTemplate jdbc;
    private final MediaService media;
    private final String mediaBaseUrl;

    public GuideService(JdbcTemplate jdbc, MediaService media,
                        @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.jdbc = jdbc;
        this.media = media;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    public record Topic(String code, String title) {}

    public List<Topic> topics() {
        return jdbc.query("SELECT code, title FROM guide_stages ORDER BY sort_order",
                (rs, n) -> new Topic(rs.getString(1), rs.getString(2)));
    }

    public List<GuideResponse> list(String category, String q) {
        String topic = category == null ? "" : category.trim();
        String search = q == null ? "" : q.trim();
        if (search.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Từ khóa tối đa 200 ký tự.");
        }
        return jdbc.query(SELECT + """
                WHERE g.published = TRUE AND (? = '' OR g.stage_code = ?)
                  AND (? = '' OR LOCATE(LOWER(?), LOWER(CONCAT(g.title, ' ', COALESCE(g.summary, '')))) > 0)
                ORDER BY g.created_at DESC, g.id DESC
                """, (rs, n) -> map(rs, false), topic, topic, search, search);
    }

    public GuideResponse detail(long id) {
        return jdbc.query(SELECT + " WHERE g.id = ? AND g.published = TRUE",
                        (rs, n) -> map(rs, true), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy cẩm nang."));
    }

    public record BrowsePage(List<GuideResponse> items, long total, int page, int size, int totalPages) {}

    public BrowsePage browse(String category, String q, String sort, int page, int size) {
        String topic = category == null ? "" : category.trim();
        String search = q == null ? "" : q.trim();
        if (search.length() > 200 || page < 0 || size < 1 || size > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tiêu chí tìm kiếm hoặc phân trang không hợp lệ.");
        }
        String order = switch (sort) {
            case "NEWEST" -> "g.created_at DESC, g.id DESC";
            case "OLDEST" -> "g.created_at ASC, g.id ASC";
            case "TITLE" -> "g.title ASC, g.id ASC";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Thứ tự sắp xếp không hợp lệ.");
        };
        String where = """
                WHERE g.published = TRUE AND (? = '' OR g.stage_code = ?)
                  AND (? = '' OR LOCATE(LOWER(?), LOWER(CONCAT(g.title, ' ', COALESCE(g.summary, '')))) > 0
                    OR EXISTS (SELECT 1 FROM guide_sections s WHERE s.guide_id = g.id
                        AND LOCATE(LOWER(?), LOWER(CONCAT(s.heading, ' ', s.content))) > 0))
                """;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM guide_items g " + where,
                Long.class, topic, topic, search, search, search);
        long total = count == null ? 0 : count;
        int pages = (int) Math.ceil((double) total / size);
        int currentPage = Math.min(page, Math.max(0, pages - 1));
        List<GuideResponse> items = jdbc.query(SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, n) -> map(rs, false), topic, topic, search, search, search, size, (long) currentPage * size);
        return new BrowsePage(items, total, currentPage, size, pages);
    }

    private GuideResponse map(ResultSet rs, boolean full) throws SQLException {
        long id = rs.getLong("id");
        List<GuideRequest.Section> sections = full ? jdbc.query("""
                SELECT s.heading, s.content,
                       CASE WHEN m.deleted_at IS NULL AND m.scan_status = 'PASSED'
                            THEN CONCAT(?, m.storage_key) ELSE s.image_url END AS image_url,
                       s.reference_url
                FROM guide_sections s LEFT JOIN media m ON m.id = s.media_id
                WHERE s.guide_id = ? ORDER BY s.sort_order
                """, (row, n) -> new GuideRequest.Section(row.getString(1), row.getString(2),
                row.getString(3), row.getString(4)), mediaBaseUrl, id) : List.of();
        return new GuideResponse(id, rs.getString("title"), rs.getString("summary"),
                rs.getString("stage_code"), rs.getString("topic_name"), rs.getBoolean("published"),
                rs.getObject("created_by", Long.class), rs.getString("creator_name"),
                rs.getTimestamp("created_at").toInstant(), sections);
    }

    static void validateUrl(String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            boolean http = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            if (!http || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Ảnh và tài liệu tham khảo phải là đường dẫn HTTP/HTTPS hợp lệ.");
        }
    }

    @Transactional
    public GuideResponse create(GuideRequest request, AppUserPrincipal me) {
        if (request.sections().stream().anyMatch(s -> s.imageIndex() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vui lòng gửi ảnh kèm nội dung cẩm nang.");
        }
        return createWithImages(request, me, List.of());
    }

    @Transactional
    public GuideResponse createWithImages(GuideRequest request, AppUserPrincipal me,
                                           List<GuideUploadService.Image> images) {
        if (me == null || !"MODERATOR".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ Moderator được tạo cẩm nang.");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM guide_stages WHERE code=?",
                Integer.class, request.category());
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Chủ đề không hợp lệ.");
        }
        request.sections().forEach(section -> {
            validateUrl(section.imageUrl());
            validateUrl(section.referenceUrl());
        });

        // The database owns created_at, so clients cannot forge the creation time.
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO guide_items(stage_code, title, summary, published, created_by, updated_by)
                    VALUES (?, ?, ?, TRUE, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.category());
            statement.setString(2, request.title().trim());
            statement.setString(3, request.summary().trim());
            statement.setLong(4, me.id());
            statement.setLong(5, me.id());
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        List<Long> mediaIds = new java.util.ArrayList<>();
        for (var image : images) {
            long mediaId = media.save(me.id(), "guide", image.file());
            jdbc.update("UPDATE media SET scan_status = 'PASSED' WHERE id = ?", mediaId);
            jdbc.update("""
                    INSERT INTO moderation_scans(target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, ?, 'PASS', ?, CURRENT_TIMESTAMP)
                    """, mediaId, image.engine(), image.scanDetail());
            mediaIds.add(mediaId);
        }
        for (int i = 0; i < request.sections().size(); i++) {
            var section = request.sections().get(i);
            jdbc.update("""
                    INSERT INTO guide_sections(guide_id, sort_order, heading, content, image_url, reference_url, media_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, i, section.heading().trim(), section.content().trim(),
                    section.imageUrl(), section.referenceUrl(),
                    section.imageIndex() == null ? null : mediaIds.get(section.imageIndex()));
        }
        return detail(id);
    }
}
