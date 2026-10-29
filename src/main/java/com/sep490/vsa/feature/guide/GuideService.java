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
            SELECT g.*, COALESCE(t.title, s.title) AS topic_name, s.title AS stage_name, u.display_name AS creator_name,
                   CASE WHEN cm.deleted_at IS NULL AND cm.scan_status = 'PASSED' THEN cm.storage_key END AS cover_key
            FROM guide_items g
            LEFT JOIN guide_stages s ON s.code = g.stage_code
            LEFT JOIN guide_topics t ON t.code = g.topic_code
            LEFT JOIN media cm ON cm.id = g.cover_media_id
            LEFT JOIN users u ON u.id = g.created_by
            """;

    private final JdbcTemplate jdbc;
    private final MediaService media;
    private final String mediaBaseUrl;
    private final GuidePreparationService preparation;

    public GuideService(JdbcTemplate jdbc, MediaService media,
                        @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl,
                        GuidePreparationService preparation) {
        this.jdbc = jdbc;
        this.media = media;
        this.mediaBaseUrl = mediaBaseUrl;
        this.preparation = preparation;
    }

    public record Topic(String code, String title) {}

    public List<Topic> topics() {
        return jdbc.query("SELECT code, title FROM guide_stages ORDER BY sort_order",
                (rs, n) -> new Topic(rs.getString(1), rs.getString(2)));
    }

    public List<Topic> subjectTopics() {
        return jdbc.query("SELECT code, title FROM guide_topics ORDER BY sort_order", (rs, n) -> new Topic(rs.getString(1), rs.getString(2)));
    }

    public GuideResponse moderatorDetail(long id) {
        return jdbc.query(SELECT + " WHERE g.id = ? AND g.deleted_at IS NULL", (rs, n) -> map(rs, true), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy cẩm nang."));
    }

    public List<GuideResponse> list(String category, String q) {
        String topic = category == null ? "" : category.trim();
        String search = q == null ? "" : q.trim();
        if (search.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Từ khóa tối đa 200 ký tự.");
        }
        return jdbc.query(SELECT + """
                WHERE g.deleted_at IS NULL AND g.published = TRUE AND (? = '' OR g.stage_code = ?)
                  AND (? = '' OR LOCATE(LOWER(?), LOWER(CONCAT(g.title, ' ', COALESCE(g.summary, '')))) > 0)
                ORDER BY g.created_at DESC, g.id DESC
                """, (rs, n) -> map(rs, false), topic, topic, search, search);
    }

    public GuideResponse detail(long id) {
        return jdbc.query(SELECT + " WHERE g.id = ? AND g.published = TRUE AND g.deleted_at IS NULL",
                        (rs, n) -> map(rs, true), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy cẩm nang."));
    }

    public record BrowsePage(List<GuideResponse> items, long total, int page, int size, int totalPages) {}

    public BrowsePage browse(String category, String q, String sort, int page, int size) {
        return browse(category, null, q, sort, page, size, "PUBLISHED", false);
    }

    public BrowsePage browse(String category, String topicCode, String q, String sort, int page, int size,
                             String status, boolean moderator) {
        String topic = category == null ? "" : category.trim();
        String subject = topicCode == null ? "" : topicCode.trim();
        if (!List.of("ALL", "DRAFT", "PUBLISHED").contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ.");
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
                WHERE g.deleted_at IS NULL AND (? = TRUE OR g.published = TRUE)
                  AND (? = 'ALL' OR g.published = ?)
                  AND (? = '' OR g.stage_code = ?)
                  AND (? = '' OR g.topic_code = ?)
                  AND (? = '' OR LOCATE(LOWER(?), LOWER(CONCAT(g.title, ' ', COALESCE(g.summary, '')))) > 0
                    OR EXISTS (SELECT 1 FROM guide_topics gt WHERE gt.code = g.topic_code
                        AND LOCATE(LOWER(?), LOWER(gt.title)) > 0)
                    OR EXISTS (SELECT 1 FROM guide_sections s WHERE s.guide_id = g.id
                        AND LOCATE(LOWER(?), LOWER(CONCAT(s.heading, ' ', s.content))) > 0))
                """;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM guide_items g " + where,
                Long.class, moderator, status, "PUBLISHED".equals(status), topic, topic, subject, subject, search, search, search, search);
        long total = count == null ? 0 : count;
        int pages = (int) Math.ceil((double) total / size);
        int currentPage = Math.min(page, Math.max(0, pages - 1));
        List<GuideResponse> items = jdbc.query(SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, n) -> map(rs, false), moderator, status, "PUBLISHED".equals(status), topic, topic, subject, subject, search, search, search, search, size, (long) currentPage * size);
        return new BrowsePage(items, total, currentPage, size, pages);
    }

    private GuideResponse map(ResultSet rs, boolean full) throws SQLException {
        long id = rs.getLong("id");
        List<GuideRequest.Section> sections = full ? jdbc.query("""
                SELECT s.heading, s.content,
                       CASE WHEN m.deleted_at IS NULL AND m.scan_status = 'PASSED'
                            THEN CONCAT(?, m.storage_key) ELSE s.image_url END AS image_url,
                       s.reference_url, s.media_id
                FROM guide_sections s LEFT JOIN media m ON m.id = s.media_id
                WHERE s.guide_id = ? ORDER BY s.sort_order
                """, (row, n) -> new GuideRequest.Section(row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), null, row.getObject(5, Long.class)), mediaBaseUrl, id) : List.of();
        return new GuideResponse(id, rs.getString("title"), rs.getString("summary"),
                rs.getString("stage_code"), rs.getString("topic_name"), rs.getBoolean("published"),
                rs.getObject("created_by", Long.class), rs.getString("creator_name"),
                rs.getTimestamp("created_at").toInstant(), sections, rs.getString("stage_name"), rs.getString("topic_code"),
                rs.getString("source_url"), rs.getString("cover_key") == null ? null : mediaBaseUrl + rs.getString("cover_key"),
                rs.getBoolean("published") ? "PUBLISHED" : "DRAFT", rs.getBoolean("preparation_enabled"),
                full && rs.getBoolean("preparation_enabled") ? preparation.tasks(id) : List.of());
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
        if (request.coverImageIndex() != null || request.sections().stream().anyMatch(s -> s.imageIndex() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vui lòng gửi ảnh kèm nội dung cẩm nang.");
        }
        return createWithImages(request, me, List.of());
    }

    @Transactional
    public GuideResponse createWithImages(GuideRequest request, AppUserPrincipal me,
                                           List<GuideUploadService.Image> images) {
        return save(request, me, images, null);
    }

    @Transactional
    public GuideResponse updateWithImages(long id, GuideRequest request, AppUserPrincipal me, List<GuideUploadService.Image> images) {
        return save(request, me, images, id);
    }

    private GuideResponse save(GuideRequest request, AppUserPrincipal me, List<GuideUploadService.Image> images, Long existingId) {
        if (me == null || !"MODERATOR".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ Moderator được tạo cẩm nang.");
        }
        String stage = blankToNull(request.category()), subject = blankToNull(request.topicCode());
        if (request.publishing() && (stage == null || subject == null || request.sections().stream()
                .anyMatch(section -> blankToNull(section.heading()) == null || blankToNull(section.content()) == null))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Đăng công khai cần giai đoạn, chủ đề và nội dung đầy đủ.");
        }
        if (stage != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM guide_stages WHERE code=?", Integer.class, stage);
            if (count == null || count == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Giai đoạn không hợp lệ.");
        }
        if (subject != null && jdbc.queryForObject("SELECT COUNT(*) FROM guide_topics WHERE code=?", Integer.class, subject) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Chủ đề không hợp lệ.");
        }
        validateUrl(request.sourceUrl());
        request.sections().forEach(section -> {
            validateUrl(section.imageUrl());
            validateUrl(section.referenceUrl());
        });

        long id;
        List<Long> removedMedia = new java.util.ArrayList<>();
        var retainedMedia = new java.util.HashSet<Long>();
        if (existingId == null) {
            if (request.sections().stream().anyMatch(section -> section.mediaId() != null)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh được giữ lại không hợp lệ.");
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO guide_items(stage_code, title, summary, published, created_by, updated_by, topic_code, source_url)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, stage); statement.setString(2, request.title().trim());
                statement.setString(3, request.summary()); statement.setBoolean(4, request.publishing());
                statement.setLong(5, me.id()); statement.setLong(6, me.id());
                statement.setString(7, subject); statement.setString(8, blankToNull(request.sourceUrl()));
                return statement;
            }, key);
            id = Objects.requireNonNull(key.getKey()).longValue();
        } else {
            var rows = jdbc.query("SELECT id FROM guide_items WHERE id = ? AND deleted_at IS NULL FOR UPDATE", (rs, n) -> rs.getLong(1), existingId);
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy cẩm nang.");
            id = existingId;
            removedMedia.addAll(jdbc.query("SELECT media_id FROM guide_sections WHERE guide_id = ? AND media_id IS NOT NULL", (rs, n) -> rs.getLong(1), id));
            var existingUrls = jdbc.query("SELECT image_url FROM guide_sections WHERE guide_id = ? AND image_url IS NOT NULL", (rs, n) -> rs.getString(1), id);
            for (var section : request.sections()) {
                if (section.mediaId() != null) {
                    if (section.imageIndex() != null || !removedMedia.contains(section.mediaId())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh phải thuộc cẩm nang đang chỉnh sửa.");
                    }
                    retainedMedia.add(section.mediaId());
                }
                if (blankToNull(section.imageUrl()) != null && !existingUrls.contains(section.imageUrl())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh cũ không thuộc cẩm nang đang chỉnh sửa.");
                }
            }
            jdbc.update("DELETE FROM guide_sections WHERE guide_id = ?", id);
            jdbc.update("""
                    UPDATE guide_items SET stage_code=?, title=?, summary=?, published=?, updated_by=?, topic_code=?, source_url=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, stage, request.title().trim(), request.summary(), request.publishing(), me.id(), subject, blankToNull(request.sourceUrl()), id);
        }
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
        if (request.coverImageIndex() != null || request.removeCover()) {
            removedMedia.addAll(jdbc.query("SELECT cover_media_id FROM guide_items WHERE id=? AND cover_media_id IS NOT NULL", (rs, n) -> rs.getLong(1), id));
            jdbc.update("UPDATE guide_items SET cover_media_id=? WHERE id=?", request.coverImageIndex() == null ? null : mediaIds.get(request.coverImageIndex()), id);
        }
        for (int i = 0; i < request.sections().size(); i++) {
            var section = request.sections().get(i);
            jdbc.update("""
                    INSERT INTO guide_sections(guide_id, sort_order, heading, content, image_url, reference_url, media_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, i, section.heading() == null ? "" : section.heading().trim(), section.content() == null ? "" : section.content().trim(),
                    section.imageUrl(), section.referenceUrl(),
                    section.imageIndex() == null ? section.mediaId() : mediaIds.get(section.imageIndex()));
        }
        removedMedia.removeAll(retainedMedia);
        media.discard(removedMedia);
        preparation.saveTasks(id, request);
        return moderatorDetail(id);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    @Transactional
    public void delete(long id, AppUserPrincipal me) {
        if (me == null || !"MODERATOR".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ Moderator được xóa cẩm nang.");
        }
        var rows = jdbc.query("SELECT id FROM guide_items WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                (rs, n) -> rs.getLong(1), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy cẩm nang.");
        // Preserve content and progress history; all readers exclude deleted guides.
        jdbc.update("UPDATE guide_items SET deleted_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP, updated_by=? WHERE id=?",
                me.id(), id);
    }
}
