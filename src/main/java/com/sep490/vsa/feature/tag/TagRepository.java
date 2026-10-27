package com.sep490.vsa.feature.tag;

import com.sep490.vsa.feature.tag.TagDtos.PendingRequest;
import com.sep490.vsa.feature.tag.TagDtos.ProofType;
import com.sep490.vsa.feature.tag.TagDtos.RequestRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Plain SQL of the identity tags: tag_types, tag_proof_types, tag_requests, user_tags. */
@Repository
public class TagRepository {

    private final JdbcTemplate jdbc;

    public TagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TagType(long id, String name, String description, String color, boolean selfieOnly) {
    }

    public List<TagType> activeTags() {
        return jdbc.query("""
                SELECT id, name, description, color, selfie_only FROM tag_types WHERE is_active ORDER BY id""",
                (rs, i) -> new TagType(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5)));
    }

    public Optional<TagType> activeTag(long id) {
        return activeTags().stream().filter(t -> t.id() == id).findFirst();
    }

    /** Proof types of every tag, keyed by tag id, in the order they were added. */
    public Map<Long, List<ProofType>> proofTypes() {
        Map<Long, List<ProofType>> out = new LinkedHashMap<>();
        jdbc.query("SELECT tag_type_id, id, name FROM tag_proof_types ORDER BY tag_type_id, id", rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ProofType(rs.getLong(2), rs.getString(3)));
        });
        return out;
    }

    /** Tags the user holds now (not revoked). */
    public Set<Long> ownedTagIds(long userId) {
        return Set.copyOf(jdbc.queryForList(
                "SELECT tag_type_id FROM user_tags WHERE user_id = ? AND revoked_at IS NULL", Long.class, userId));
    }

    public Optional<PendingRequest> pendingOf(long userId) {
        return jdbc.query("""
                SELECT t.name, r.submitted_at, r.expires_at FROM tag_requests r JOIN tag_types t ON t.id = r.tag_type_id
                WHERE r.user_id = ? AND r.status = 'PENDING' ORDER BY r.id DESC LIMIT 1""",
                (rs, i) -> new PendingRequest(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant()), userId).stream().findFirst();
    }

    /** One request at a time per user: the users row is locked while the request is checked and saved. */
    public String lockUser(long userId) {
        return jdbc.query("SELECT status FROM users WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), userId)
                .stream().findFirst().orElse(null);
    }

    public String role(long userId) {
        return jdbc.query("SELECT role FROM users WHERE id = ?", (rs, i) -> rs.getString(1), userId)
                .stream().findFirst().orElse(null);
    }

    public long insertRequest(long userId, long tagId, long proofTypeId, long mediaId, Instant now, Instant expires) {
        jdbc.update("""
                INSERT INTO tag_requests (user_id, tag_type_id, proof_type_id, proof_media_id, status, submitted_at,
                                          expires_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?)""",
                userId, tagId, proofTypeId, mediaId, Timestamp.from(now), Timestamp.from(expires));
        return jdbc.queryForObject("SELECT MAX(id) FROM tag_requests WHERE user_id = ?", Long.class, userId);
    }

    // ---------------------------------------------------------------- Moderator

    private static final String ROW_COLUMNS = """
            r.id, r.user_id, u.display_name, t.name, t.color, p.name, r.submitted_at, r.expires_at, t.selfie_only,
            r.proof_media_id, r.status
            """;
    private static final String ROW_FROM = """
            FROM tag_requests r JOIN users u ON u.id = r.user_id JOIN tag_types t ON t.id = r.tag_type_id
            JOIN tag_proof_types p ON p.id = r.proof_type_id
            """;

    /** Waiting requests, oldest first, filtered by tag and by the user's name (accents ignored). */
    public List<RequestRow> pending(Long tagId, String name, int offset, int size, Instant now) {
        List<Object> args = new ArrayList<>();
        String where = pendingWhere(tagId, name, args);
        args.add(size);
        args.add(offset);
        return jdbc.query("SELECT " + ROW_COLUMNS + ROW_FROM + where + " ORDER BY r.submitted_at, r.id LIMIT ? OFFSET ?",
                (rs, i) -> new RequestRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        daysLeft(rs.getTimestamp(8).toInstant(), now)), args.toArray());
    }

    public long countPending(Long tagId, String name) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + ROW_FROM + pendingWhere(tagId, name, args), Long.class,
                args.toArray());
        return n == null ? 0 : n;
    }

    private static String pendingWhere(Long tagId, String name, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE r.status = 'PENDING' AND u.status = 'ACTIVE'");
        if (tagId != null) {
            where.append(" AND r.tag_type_id = ?");
            args.add(tagId);
        }
        if (name != null && !name.isBlank()) {
            where.append(" AND u.display_name LIKE ?");
            args.add("%" + name.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return where.toString();
    }

    public record RequestState(long id, long userId, String userName, String tagName, String tagColor,
                               String proofType, Instant submittedAt, Instant expiresAt, boolean selfieOnly,
                               Long mediaId, String status, long tagId) {
    }

    /** @param lock {@code FOR UPDATE}: two Moderators handling the same request wait for each other */
    public Optional<RequestState> request(long id, boolean lock) {
        return jdbc.query("SELECT " + ROW_COLUMNS + ", r.tag_type_id " + ROW_FROM + " WHERE r.id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestState(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8).toInstant(), rs.getBoolean(9), (Long) rs.getObject(10, Long.class),
                        rs.getString(11), rs.getLong(12)), id).stream().findFirst();
    }

    public boolean close(long id, String status, String reason, Long moderatorId, Instant now) {
        return jdbc.update("""
                UPDATE tag_requests SET status = ?, reject_reason = ?, reviewed_by = ?, reviewed_at = ?
                WHERE id = ? AND status = 'PENDING'""", status, reason, moderatorId, Timestamp.from(now), id) > 0;
    }

    /** Grants the tag (again, if it was revoked before). */
    public void grant(long userId, long tagId, long requestId, long moderatorId, Instant now) {
        jdbc.update("""
                INSERT INTO user_tags (user_id, tag_type_id, request_id, granted_by, granted_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE request_id = VALUES(request_id), granted_by = VALUES(granted_by),
                                        granted_at = VALUES(granted_at), revoked_at = NULL""",
                userId, tagId, requestId, moderatorId, Timestamp.from(now));
    }

    /** Waiting requests whose 14 days are over, oldest first. */
    public List<Long> expiredIds(Instant now, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM tag_requests WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at LIMIT ?""",
                Long.class, Timestamp.from(now), limit);
    }

    static long daysLeft(Instant expires, Instant now) {
        long seconds = Duration.between(now, expires).getSeconds();
        return seconds <= 0 ? 0 : (seconds + 86_399) / 86_400;
    }
}
