package com.sep490.vsa.feature.report;

import com.sep490.vsa.feature.report.ReportCaseDtos.Reporter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Plain SQL over report_cases and the tables of the reported content (posts, comments, reviews, messages). */
@Repository
public class ReportCaseRepository {

    private static final String KIND = """
            CASE k.target_type WHEN 'BLOG' THEN b.blog_type
                 WHEN 'COMMENT' THEN IF(cb.blog_type = 'QA' AND c.parent_id IS NULL, 'ANSWER', 'COMMENT')
                 WHEN 'REVIEW' THEN 'REVIEW' ELSE 'MESSAGE' END""";
    private static final String FROM = """
            FROM report_cases k
            LEFT JOIN blogs b ON k.target_type = 'BLOG' AND b.id = k.target_id
            LEFT JOIN comments c ON k.target_type = 'COMMENT' AND c.id = k.target_id
            LEFT JOIN blogs cb ON cb.id = c.blog_id
            LEFT JOIN reviews r ON k.target_type = 'REVIEW' AND r.id = k.target_id
            LEFT JOIN places p ON p.id = r.place_id
            LEFT JOIN events e ON e.id = r.event_id
            LEFT JOIN chat_messages m ON k.target_type = 'MESSAGE' AND m.id = k.target_id
            JOIN users u ON u.id = COALESCE(b.author_id, c.author_id, r.author_id, m.sender_id)
            WHERE k.handler_role = 'MODERATOR'
            """;
    private static final String SELECT = """
            SELECT k.id, k.target_type, k.target_id, k.priority, k.status, k.report_count, k.created_at, (%s) AS kind,
                   k.decision, k.decision_note, k.handled_at, COALESCE(b.purge_at, c.purge_at, r.purge_at) AS purge_at,
                   COALESCE(b.title, cb.title, p.name_vi, e.title) AS title,
                   COALESCE(b.content, c.content, r.content, m.content) AS body,
                   (SELECT ev.plaintext FROM chat_report_evidence ev JOIN reports er ON er.id = ev.report_id
                    WHERE er.case_id = k.id ORDER BY ev.id LIMIT 1) AS evidence,
                   m.ciphertext IS NOT NULL AS encrypted,
                   COALESCE(b.status, c.status, r.status) AS content_status,
                   COALESCE(b.created_at, c.created_at, r.created_at, m.created_at) AS content_created_at,
                   u.id AS author_id, u.display_name, u.status AS author_status,
                   COALESCE(b.id, cb.id) AS blog_id, COALESCE(b.blog_type, cb.blog_type) AS blog_type,
                   r.place_id, r.event_id,
                   (SELECT o.label FROM reports x JOIN reason_options o ON o.id = x.reason_id WHERE x.case_id = k.id
                    GROUP BY o.id, o.label, o.sort_order ORDER BY COUNT(*) DESC, o.sort_order LIMIT 1) AS top_reason,
                   (SELECT MIN(x.created_at) FROM reports x WHERE x.case_id = k.id) AS first_reported_at
            """.formatted(KIND);

    public record CaseRow(long id, String targetType, long targetId, String priority, String status, int reportCount,
                          String kind, String title, String body, String evidence, boolean encrypted,
                          String contentStatus, Instant contentCreatedAt, long authorId, String authorName,
                          String authorStatus, Long blogId, String blogType, Long placeId, Long eventId,
                          String topReason, Instant firstReportedAt, String decision, String decisionNote,
                          Instant handledAt, Instant purgeAt) {
    }

    private static final RowMapper<CaseRow> ROW = (rs, i) -> new CaseRow(rs.getLong("id"), rs.getString("target_type"),
            rs.getLong("target_id"), rs.getString("priority"), rs.getString("status"), rs.getInt("report_count"),
            rs.getString("kind"), rs.getString("title"), rs.getString("body"), rs.getString("evidence"),
            rs.getBoolean("encrypted"), rs.getString("content_status"), instant(rs.getTimestamp("content_created_at")),
            rs.getLong("author_id"), rs.getString("display_name"), rs.getString("author_status"),
            rs.getObject("blog_id", Long.class), rs.getString("blog_type"), rs.getObject("place_id", Long.class),
            rs.getObject("event_id", Long.class), rs.getString("top_reason"), instant(rs.getTimestamp("first_reported_at")),
            rs.getString("decision"), rs.getString("decision_note"), instant(rs.getTimestamp("handled_at")),
            instant(rs.getTimestamp("purge_at")));

    private final JdbcTemplate jdbc;

    public ReportCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- queue

    /** Removed by a Moderator and still kept: posts, comments and reviews (a recalled message cannot come back). */
    private static final String REMOVED = """
             AND k.decision = 'REMOVE' AND k.target_type <> 'MESSAGE' AND COALESCE(b.status, c.status, r.status) = 'REMOVED'""";

    private static String filters(String kind, Long reasonId, List<Object> args) {
        return filters(" AND k.status = 'OPEN'", kind, reasonId, args);
    }

    private static String filters(String state, String kind, Long reasonId, List<Object> args) {
        StringBuilder sql = new StringBuilder(state);
        if (kind != null) {
            sql.append(" AND (").append(KIND).append(") = ?");
            args.add(kind);
        }
        if (reasonId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM reports fr WHERE fr.case_id = k.id AND fr.reason_id = ?)");
            args.add(reasonId);
        }
        return sql.toString();
    }

    /** Most reports first, then the oldest case. */
    public List<CaseRow> page(String priority, String kind, Long reasonId, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = filters(kind, reasonId, args);
        args.add(priority);
        args.add(limit);
        args.add(offset);
        return jdbc.query(SELECT + FROM + where + """
                 AND k.priority = ?
                ORDER BY k.report_count DESC, k.created_at, k.id LIMIT ? OFFSET ?""", ROW, args.toArray());
    }

    public long count(String priority, String kind, Long reasonId) {
        List<Object> args = new ArrayList<>();
        String where = filters(kind, reasonId, args);
        args.add(priority);
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where + " AND k.priority = ?", Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** Tab "Đã gỡ": newest removal first. */
    public List<CaseRow> removedPage(String kind, Long reasonId, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = filters(REMOVED, kind, reasonId, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(SELECT + FROM + where + " ORDER BY k.handled_at DESC, k.id DESC LIMIT ? OFFSET ?", ROW, args.toArray());
    }

    public long removedCount(String kind, Long reasonId) {
        List<Object> args = new ArrayList<>();
        String where = filters(REMOVED, kind, reasonId, args);
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public Optional<CaseRow> find(long caseId) {
        return jdbc.query(SELECT + FROM + " AND k.id = ?", ROW, caseId).stream().findFirst();
    }

    public List<Reporter> reporters(long caseId) {
        return jdbc.query("""
                SELECT x.reporter_id, u.display_name, o.label, x.note, u.false_report_count, x.created_at
                FROM reports x JOIN users u ON u.id = x.reporter_id JOIN reason_options o ON o.id = x.reason_id
                WHERE x.case_id = ? ORDER BY x.created_at, x.id""",
                (rs, i) -> new Reporter(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        instant(rs.getTimestamp(6))), caseId);
    }

    public record MediaRow(long id, String type, String key) {
    }

    /** The files of a reported post or review that passed their scan. */
    public List<MediaRow> media(String targetType, long targetId) {
        String link = "BLOG".equals(targetType) ? "blog_media l ON l.media_id = m.id AND l.blog_id = ?"
                : "review_media l ON l.media_id = m.id AND l.review_id = ?";
        return jdbc.query("SELECT m.id, m.media_type, m.storage_key FROM media m JOIN " + link
                        + " WHERE m.deleted_at IS NULL AND m.scan_status = 'PASSED' ORDER BY l.sort_order, m.id",
                (rs, i) -> new MediaRow(rs.getLong(1), rs.getString(2), rs.getString(3)), targetId);
    }

    // ---------------------------------------------------------------- decide

    /** Locks the case while it is still open; false when another Moderator handled it meanwhile. */
    public boolean lockOpen(long caseId) {
        return !jdbc.query("SELECT id FROM report_cases WHERE id = ? AND status = 'OPEN' FOR UPDATE",
                (rs, i) -> rs.getLong(1), caseId).isEmpty();
    }

    /** Locks a case closed by a removal; false when it was restored meanwhile. */
    public boolean lockRemoved(long caseId) {
        return !jdbc.query("SELECT id FROM report_cases WHERE id = ? AND decision = 'REMOVE' FOR UPDATE",
                (rs, i) -> rs.getLong(1), caseId).isEmpty();
    }

    /** "Khôi phục": the removal is undone; the reporters are not counted as wrong, the mistake was the Moderator's. */
    public void reopenAsKept(long caseId, String note, long moderatorId, Instant now) {
        jdbc.update("UPDATE report_cases SET decision = 'KEEP', decision_note = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                cut(note), moderatorId, Timestamp.from(now), caseId);
    }

    public void restoreRemovedBlog(long id) {
        jdbc.update("""
                UPDATE blogs SET status = 'PUBLISHED', removed_reason = NULL, removed_at = NULL, purge_at = NULL,
                                 updated_at = updated_at WHERE id = ? AND status = 'REMOVED'""", id);
    }

    public void restoreRemovedReview(long id) {
        jdbc.update("""
                UPDATE reviews SET status = 'PUBLISHED', removed_reason = NULL, removed_at = NULL, purge_at = NULL,
                                   updated_at = updated_at WHERE id = ? AND status = 'REMOVED'""", id);
    }

    public void restoreRemovedComment(long id) {
        jdbc.update("""
                UPDATE comments SET status = 'PUBLISHED', removed_at = NULL, purge_at = NULL, updated_at = updated_at
                WHERE id = ? AND status = 'REMOVED'""", id);
    }

    public boolean lockReview(long reviewId) {
        return !jdbc.query("SELECT id FROM reviews WHERE id = ? FOR UPDATE", (rs, i) -> rs.getLong(1), reviewId).isEmpty();
    }

    public void close(long caseId, String decision, String note, long moderatorId, Instant now) {
        jdbc.update("""
                UPDATE report_cases SET status = 'RESOLVED', decision = ?, decision_note = ?, handled_by = ?, handled_at = ?
                WHERE id = ?""", decision, note, moderatorId, Timestamp.from(now), caseId);
    }

    public void markReports(long caseId, boolean falseReport) {
        jdbc.update("UPDATE reports SET is_false = ? WHERE case_id = ?", falseReport, caseId);
    }

    /** The post is gone with its comments: their open cases end with it, their writers lose nothing. */
    public void closeCommentCases(long blogId, long moderatorId, Instant now) {
        jdbc.update("""
                UPDATE report_cases SET status = 'RESOLVED', decision_note = 'Bài gốc đã bị gỡ.', handled_by = ?, handled_at = ?
                WHERE status = 'OPEN' AND target_type = 'COMMENT'
                  AND target_id IN (SELECT id FROM comments WHERE blog_id = ?)""",
                moderatorId, Timestamp.from(now), blogId);
    }

    public void removeBlog(long id, String reason, Instant now, Instant purgeAt) {
        jdbc.update("""
                UPDATE blogs SET status = 'REMOVED', removed_reason = ?, removed_at = ?, purge_at = ?, updated_at = updated_at
                WHERE id = ?""", cut(reason), Timestamp.from(now), Timestamp.from(purgeAt), id);
    }

    public void removeReview(long id, String reason, Instant now, Instant purgeAt) {
        jdbc.update("""
                UPDATE reviews SET status = 'REMOVED', removed_reason = ?, removed_at = ?, purge_at = ?, updated_at = updated_at
                WHERE id = ?""", cut(reason), Timestamp.from(now), Timestamp.from(purgeAt), id);
    }

    public void removeComment(long id, Instant now, Instant purgeAt) {
        jdbc.update("UPDATE comments SET status = 'REMOVED', removed_at = ?, purge_at = ?, updated_at = updated_at WHERE id = ?",
                Timestamp.from(now), Timestamp.from(purgeAt), id);
    }

    /** "Báo cáo sai, khôi phục": only what the reports hid comes back. */
    public void restore(String table, long id) {
        jdbc.update("UPDATE " + table + " SET status = 'PUBLISHED', updated_at = updated_at WHERE id = ? AND status = 'HIDDEN'", id);
    }

    public record FalseCount(long userId, int count) {
    }

    /** One more false report for each; rows are locked in id order, so two decisions never wait for each other in a circle. */
    public List<FalseCount> countFalseReports(long caseId) {
        List<Long> users = jdbc.query("""
                SELECT u.id FROM users u WHERE u.id IN (SELECT reporter_id FROM reports WHERE case_id = ?)
                ORDER BY u.id FOR UPDATE""", (rs, i) -> rs.getLong(1), caseId);
        List<FalseCount> counts = new ArrayList<>();
        for (long id : users) {
            jdbc.update("UPDATE users SET false_report_count = false_report_count + 1 WHERE id = ?", id);
            counts.add(new FalseCount(id, jdbc.queryForObject("SELECT false_report_count FROM users WHERE id = ?", Integer.class, id)));
        }
        return counts;
    }

    /** The lock starts a new count: the next lock takes as many false reports again. */
    public void muteReporter(long userId, Instant until) {
        jdbc.update("UPDATE users SET report_muted_until = ?, false_report_count = 0 WHERE id = ?", Timestamp.from(until), userId);
    }

    // ---------------------------------------------------------------- purge

    public List<Long> expired(String table, Instant now, int limit) {
        return jdbc.query("SELECT id FROM " + table + " WHERE status = 'REMOVED' AND purge_at <= ? ORDER BY purge_at LIMIT ?",
                (rs, i) -> rs.getLong(1), Timestamp.from(now), limit);
    }

    public void clearPurge(long commentId) {
        jdbc.update("UPDATE comments SET purge_at = NULL WHERE id = ?", commentId);
    }

    public void clearBestAnswer(long commentId) {
        jdbc.update("UPDATE blogs SET best_comment_id = NULL, updated_at = updated_at WHERE best_comment_id = ?", commentId);
    }

    private static String cut(String text) {
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
