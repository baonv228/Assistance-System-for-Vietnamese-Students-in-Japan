package com.sep490.vsa.feature.report;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Report Content (UC-16) for blog posts and comments; reviews can reuse it. Each reported item has one open
 * {@code report_cases} row that collects the individual {@code reports}.
 * <ul>
 *   <li>A user reports the same item only once, ever (GBR-22, MSG39), and never their own content.</li>
 *   <li>A user whose reporting is locked for false reports cannot report until the lock ends.</li>
 *   <li>1 to {@code report_hide_threshold} (5) reports: "Cần xử lý" (NORMAL), content stays visible.
 *       More than that: the case becomes URGENT and the caller must hide the content.</li>
 * </ul>
 * The caller checks that the item exists and is visible, and does the hiding (it knows the item's table). The caller
 * also locks the reported row first, so reports on one item run one after another (two first reports at the same
 * moment would otherwise both try to open the case and could deadlock).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ContentReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    static final int NOTE_MAX = 500;

    private final JdbcTemplate jdbc;
    private final SystemSettings settings;
    private final Clock clock;

    public ContentReportService(JdbcTemplate jdbc, SystemSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** What is reported. {@code type} is BLOG, COMMENT or REVIEW. */
    public record Target(String type, long id, long authorId) {
    }

    /**
     * @param hide true while the item has more reports than the threshold: hide it now. Every report past the
     *             threshold says so again (hiding only changes published content), so an item that was not
     *             published when its case turned URGENT is still hidden by a later report.
     */
    public record Outcome(int reportCount, boolean hide) {
    }

    public record Reason(long id, String label) {
    }

    /** The reasons offered in the report dialog: Lừa đảo, Sai sự thật, Xúc phạm, Spam. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<Reason> reasons() {
        return jdbc.query("""
                SELECT id, label FROM reason_options WHERE scope = 'REPORT' AND is_active ORDER BY sort_order, id""",
                (rs, i) -> new Reason(rs.getLong(1), rs.getString(2)));
    }

    public Outcome report(Target target, long reporterId, Long reasonId, String note) {
        if (target.authorId() == reporterId) {
            throw ApiException.badRequest("Bạn không thể báo cáo nội dung của chính mình.");
        }
        if (reasonId == null || reasons().stream().noneMatch(r -> r.id() == reasonId)) {
            throw ApiException.badRequest("Hãy chọn lý do báo cáo.");                       // MSG40
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > NOTE_MAX) {
            throw ApiException.badRequest("Ghi chú tối đa " + NOTE_MAX + " ký tự.");
        }
        Instant now = clock.instant();
        Timestamp mutedUntil = jdbc.queryForObject("SELECT report_muted_until FROM users WHERE id = ?",
                Timestamp.class, reporterId);
        if (mutedUntil != null && mutedUntil.toInstant().isAfter(now)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Bạn bị khóa báo cáo đến "
                    + DAY.format(mutedUntil.toInstant().atZone(JAPAN)) + " vì có quá nhiều báo cáo sai.");
        }
        if (alreadyReported(target, reporterId)) {
            throw ApiException.conflict("Bạn đã báo cáo nội dung này rồi.");                // MSG39
        }

        long caseId = openCase(target, now);
        try {
            jdbc.update("""
                    INSERT INTO reports (case_id, reporter_id, reason_id, note, created_at) VALUES (?, ?, ?, ?, ?)""",
                    caseId, reporterId, reasonId, cleanNote, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Bạn đã báo cáo nội dung này rồi.");
        }
        jdbc.update("UPDATE report_cases SET report_count = report_count + 1 WHERE id = ?", caseId);
        int count = jdbc.queryForObject("SELECT report_count FROM report_cases WHERE id = ?", Integer.class, caseId);

        int threshold = settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5);
        boolean overThreshold = count > threshold;
        if (overThreshold) {
            jdbc.update("UPDATE report_cases SET priority = 'URGENT' WHERE id = ? AND priority = 'NORMAL'", caseId);
        }
        return new Outcome(count, overThreshold);
    }

    /** Closes open cases of content that no longer exists (the author deleted it). */
    public void closeCasesOf(String targetType, List<Long> targetIds) {
        if (targetIds.isEmpty()) {
            return;
        }
        String marks = String.join(",", targetIds.stream().map(id -> "?").toList());
        Object[] args = new Object[targetIds.size() + 2];
        args[0] = Timestamp.from(clock.instant());
        args[1] = targetType;
        for (int i = 0; i < targetIds.size(); i++) {
            args[i + 2] = targetIds.get(i);
        }
        jdbc.update("UPDATE report_cases SET status = 'RESOLVED', decision_note = 'Tác giả đã tự xóa nội dung.', "
                + "handled_at = ? WHERE status = 'OPEN' AND target_type = ? AND target_id IN (" + marks + ")", args);
    }

    private boolean alreadyReported(Target target, long reporterId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reports r JOIN report_cases c ON c.id = r.case_id
                WHERE c.target_type = ? AND c.target_id = ? AND r.reporter_id = ?""",
                Long.class, target.type(), target.id(), reporterId);
        return n != null && n > 0;
    }

    /** The open case of the item, created on its first report. */
    private long openCase(Target target, Instant now) {
        List<Long> open = jdbc.query("""
                SELECT id FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN' FOR UPDATE""",
                (rs, i) -> rs.getLong(1), target.type(), target.id());
        if (!open.isEmpty()) {
            return open.get(0);
        }
        try {
            jdbc.update("""
                    INSERT INTO report_cases (target_type, target_id, handler_role, created_at)
                    VALUES (?, ?, 'MODERATOR', ?)""", target.type(), target.id(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // Another report created the case at the same moment; use that one
        }
        return jdbc.queryForObject("""
                SELECT id FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN'""",
                Long.class, target.type(), target.id());
    }
}
