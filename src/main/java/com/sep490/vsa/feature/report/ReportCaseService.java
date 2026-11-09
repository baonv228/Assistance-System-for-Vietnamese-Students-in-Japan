package com.sep490.vsa.feature.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.blog.BlogDetailRepository;
import com.sep490.vsa.feature.chat.ChatService;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.report.ReportCaseDtos.CaseDetail;
import com.sep490.vsa.feature.report.ReportCaseDtos.CaseLine;
import com.sep490.vsa.feature.report.ReportCaseDtos.CasePage;
import com.sep490.vsa.feature.report.ReportCaseDtos.Done;
import com.sep490.vsa.feature.report.ReportCaseDtos.MediaRef;
import com.sep490.vsa.feature.report.ReportCaseDtos.ReasonCount;
import com.sep490.vsa.feature.report.ReportCaseDtos.ReasonRef;
import com.sep490.vsa.feature.report.ReportCaseDtos.RemoveRequest;
import com.sep490.vsa.feature.report.ReportCaseDtos.Reporter;
import com.sep490.vsa.feature.report.ReportCaseRepository.CaseRow;
import com.sep490.vsa.feature.report.ReportCaseRepository.FalseCount;
import com.sep490.vsa.feature.review.ReviewRepository;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Moderator's report queue (UC-M03) and decisions (UC-M04). There are no appeals (user decision): removed content
 * is kept {@code removed_keep_days} (30) days, for the team to look it up, and then deleted for good.
 * <p>
 * Locks: the reported row first (post, then comment; review; conversation), then the case, then users, the same order as
 * a Member's report, so a report and a decision on one item queue up instead of deadlocking.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReportCaseService {

    static final int PAGE_SIZE = 10;
    static final int MAX_PAGE = 199;
    static final int EXCERPT = 160;
    static final int NOTE_MAX = 200;
    static final int PURGE_BATCH = 200;
    static final String NOT_FOUND = "Không tìm thấy hồ sơ này.";
    static final String ALREADY_HANDLED = "Hồ sơ này đã được xử lý.";
    static final String CHOOSE_REASON = "Hãy chọn lý do gỡ.";
    /** Notification types. */
    static final String CONTENT_REMOVED = "CONTENT_REMOVED";
    static final String REPORT_RESOLVED = "REPORT_RESOLVED";
    static final String REPORT_MUTED = "REPORT_MUTED";
    static final String CONTENT_RESTORED = "CONTENT_RESTORED";
    static final String MESSAGE_FOR_GOOD = "Tin nhắn đã gỡ không khôi phục được.";

    private static final Set<String> KINDS = Set.of("POST", "QA", "ANSWER", "COMMENT", "REVIEW", "MESSAGE");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final ReportCaseRepository repository;
    private final ContentReportService reports;
    private final BlogDetailRepository blogs;
    private final ReviewRepository reviews;
    private final ChatService chat;
    private final MediaService media;
    private final PointService points;
    private final NotificationService notifications;
    private final ActivityLogService activityLog;
    private final SystemSettings settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final String mediaBase;

    public ReportCaseService(ReportCaseRepository repository, ContentReportService reports, BlogDetailRepository blogs,
                             ReviewRepository reviews, ChatService chat, MediaService media, PointService points,
                             NotificationService notifications, ActivityLogService activityLog, SystemSettings settings,
                             ObjectMapper json, Clock clock,
                             @Value("${app.media.public-base-url:/media/}") String mediaBase) {
        this.repository = repository;
        this.reports = reports;
        this.blogs = blogs;
        this.reviews = reviews;
        this.chat = chat;
        this.media = media;
        this.points = points;
        this.notifications = notifications;
        this.activityLog = activityLog;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.mediaBase = mediaBase;
    }

    // ---------------------------------------------------------------- UC-M03

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public CasePage queue(String tab, String kind, Long reasonId, int page) {
        String wanted = kind != null && KINDS.contains(kind) ? kind : null;
        int p = Math.max(0, Math.min(page, MAX_PAGE));
        long urgent = repository.count("URGENT", wanted, reasonId);
        long normal = repository.count("NORMAL", wanted, reasonId);
        long removed = repository.removedCount(wanted, reasonId);
        List<CaseRow> rows;
        long total;
        if ("REMOVED".equals(tab)) {
            rows = repository.removedPage(wanted, reasonId, p * PAGE_SIZE, PAGE_SIZE);
            total = removed;
        } else {
            String priority = "NORMAL".equals(tab) ? "NORMAL" : "URGENT";
            rows = repository.page(priority, wanted, reasonId, p * PAGE_SIZE, PAGE_SIZE);
            total = "URGENT".equals(priority) ? urgent : normal;
        }
        return new CasePage(rows.stream().map(this::line).toList(), p, PAGE_SIZE, total, urgent, normal, removed, reasons());
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public CaseDetail detail(long caseId) {
        CaseRow c = repository.find(caseId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        boolean removed = removed(c);
        if (!removed && !"OPEN".equals(c.status())) {
            throw ApiException.conflict(ALREADY_HANDLED);
        }
        List<Reporter> reporters = repository.reporters(caseId);
        Map<String, Integer> counted = new LinkedHashMap<>();
        reporters.forEach(r -> counted.merge(r.reason(), 1, Integer::sum));
        List<ReasonCount> reasonCounts = counted.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new ReasonCount(e.getKey(), e.getValue())).toList();
        List<MediaRef> files = "BLOG".equals(c.targetType()) || "REVIEW".equals(c.targetType())
                ? repository.media(c.targetType(), c.targetId()).stream()
                        .map(m -> new MediaRef(m.id(), m.type(), mediaBase + m.key())).toList()
                : List.of();
        Message message = message(c);
        boolean hidden = hidden(c);
        return new CaseDetail(c.id(), c.kind(), label(c), c.priority(), "MESSAGE".equals(c.kind()) ? null : c.title(),
                message != null ? message.text() : c.body(), c.encrypted(), message != null ? message.files() : 0, files,
                hidden || removed ? null : link(c), hidden, c.authorId(), c.authorName(), c.authorStatus(),
                c.contentCreatedAt(), c.firstReportedAt(), reasonCounts, reporters, reasons(), removed,
                removed ? c.decisionNote() : null, removed ? c.handledAt() : null, removed ? c.purgeAt() : null);
    }

    // ---------------------------------------------------------------- UC-M04

    /** "Gỡ nội dung": hidden from everyone at once, deleted after {@code removed_keep_days}, −20 points for the author. */
    public Done remove(long moderatorId, long caseId, RemoveRequest request) {
        Long reasonId = request == null ? null : request.reasonId();
        String reason = reasons().stream().filter(r -> Long.valueOf(r.id()).equals(reasonId)).map(ReasonRef::label)
                .findFirst().orElseThrow(() -> ApiException.invalid(CHOOSE_REASON, Map.of("reasonId", CHOOSE_REASON)));
        String note = request.note() == null ? "" : request.note().strip();
        if (note.length() > NOTE_MAX) {
            String tooLong = "Ghi chú tối đa " + NOTE_MAX + " ký tự.";
            throw ApiException.invalid(tooLong, Map.of("note", tooLong));
        }
        String because = note.isEmpty() ? reason : reason + ": " + note;
        CaseRow c = lockedCase(caseId);
        Instant now = clock.instant();
        Instant purgeAt = now.plus(Duration.ofDays(settings.getInt(SystemSettings.REMOVED_KEEP_DAYS, 30)));
        switch (c.targetType()) {
            case "BLOG" -> {
                repository.removeBlog(c.targetId(), because, now, purgeAt);
                repository.closeCommentCases(c.targetId(), moderatorId, now);
            }
            case "COMMENT" -> {
                repository.removeComment(c.targetId(), now, purgeAt);
                blogs.recountComments(c.blogId());
            }
            case "REVIEW" -> repository.removeReview(c.targetId(), because, now, purgeAt);
            default -> chat.removeReported(c.targetId());
        }
        repository.close(caseId, "REMOVE", because, moderatorId, now);
        repository.markReports(caseId, false);
        points.add(c.authorId(), PointService.CONTENT_REMOVED, "CONTENT_REMOVED", pointRef(c), c.targetId());
        notifications.send(c.authorId(), CONTENT_REMOVED, authorText(c, because), noticeRef(c), noticeId(c));
        for (Reporter r : repository.reporters(caseId)) {
            notifications.send(r.id(), REPORT_RESOLVED,
                    "Cảm ơn bạn đã báo cáo. Moderator đã gỡ nội dung vi phạm.", null, null);
        }
        activityLog.recordLog(moderatorId, "REPORT_REMOVED", c.targetType(), c.targetId(), Map.of("caseId", caseId),
                Map.of("reason", because), "Gỡ " + label(c) + ": " + because);
        return new Done("Đã gỡ nội dung và trừ " + Math.abs(PointService.CONTENT_REMOVED) + " điểm của tác giả.");
    }

    /**
     * "Báo cáo sai": hidden content comes back, the author loses nothing, each reporter gets one more false report; past
     * {@code false_report_max} reporting locks for {@code mute_days} days, the member is told and the count starts over.
     */
    public Done dismiss(long moderatorId, long caseId) {
        CaseRow c = lockedCase(caseId);
        boolean wasHidden = hidden(c);
        Instant now = clock.instant();
        if (wasHidden) {
            switch (c.targetType()) {
                case "BLOG" -> repository.restore("blogs", c.targetId());
                case "COMMENT" -> {
                    repository.restore("comments", c.targetId());
                    blogs.recountComments(c.blogId());
                }
                case "REVIEW" -> repository.restore("reviews", c.targetId());
                default -> { }
            }
        }
        repository.close(caseId, "KEEP", "Báo cáo sai", moderatorId, now);
        repository.markReports(caseId, true);
        int max = settings.getInt(SystemSettings.FALSE_REPORT_MAX, 5);
        int days = settings.getInt(SystemSettings.MUTE_DAYS, 7);
        for (FalseCount reporter : repository.countFalseReports(caseId)) {
            if (reporter.count() > max) {
                Instant until = now.plus(Duration.ofDays(days));
                repository.muteReporter(reporter.userId(), until);
                notifications.send(reporter.userId(), REPORT_MUTED, "Bạn bị khóa báo cáo đến "
                        + DAY.format(until.atZone(JAPAN)) + " vì có quá nhiều báo cáo sai.", null, null);
            }
        }
        activityLog.recordLog(moderatorId, "REPORT_DISMISSED", c.targetType(), c.targetId(), Map.of("caseId", caseId),
                Map.of("restored", wasHidden), "Báo cáo sai: " + label(c));
        return new Done(wasHidden ? "Đã khôi phục nội dung và đóng hồ sơ." : "Đã bỏ qua báo cáo và đóng hồ sơ.");
    }

    /**
     * "Khôi phục", for a removal made by mistake, until the content is purged: it is published again, the 20 points come
     * back and the author is told. The reporters are not counted as wrong: the mistake was the Moderator's. A removed
     * message cannot come back (its files are deleted at once).
     */
    public Done restore(long moderatorId, long caseId) {
        CaseRow c = repository.find(caseId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if ("MESSAGE".equals(c.targetType())) {
            throw ApiException.badRequest(MESSAGE_FOR_GOOD);
        }
        lockTarget(c);
        if (!repository.lockRemoved(caseId)) {
            throw ApiException.conflict(ALREADY_HANDLED);
        }
        c = repository.find(caseId).filter(ReportCaseService::removed)
                .orElseThrow(() -> ApiException.conflict(ALREADY_HANDLED));
        Instant now = clock.instant();
        switch (c.targetType()) {
            case "BLOG" -> repository.restoreRemovedBlog(c.targetId());
            case "COMMENT" -> {
                repository.restoreRemovedComment(c.targetId());
                blogs.recountComments(c.blogId());
            }
            default -> repository.restoreRemovedReview(c.targetId());
        }
        repository.reopenAsKept(caseId, "Đã khôi phục. Lý do gỡ trước đó: " + c.decisionNote(), moderatorId, now);
        int back = Math.abs(PointService.CONTENT_REMOVED);
        points.add(c.authorId(), back, "CONTENT_RESTORED", pointRef(c), c.targetId());
        notifications.send(c.authorId(), CONTENT_RESTORED, what(c) + " đã được Moderator khôi phục. Bạn được hoàn "
                + back + " điểm.", restoredRef(c), restoredId(c));
        activityLog.recordLog(moderatorId, "REPORT_RESTORED", c.targetType(), c.targetId(), Map.of("caseId", caseId),
                Map.of("status", "PUBLISHED"), "Khôi phục " + label(c));
        return new Done("Đã khôi phục nội dung và hoàn " + back + " điểm cho tác giả.");
    }

    /** Every day: content whose time is up is deleted for good, with its files. */
    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Tokyo")
    public void purgeRemoved() {
        Instant now = clock.instant();
        for (long id : repository.expired("reviews", now, PURGE_BATCH)) {
            media.discard(reviews.allMediaIds(id));
            reviews.delete(id);
        }
        for (long id : repository.expired("comments", now, PURGE_BATCH)) {
            repository.clearBestAnswer(id);
            if (blogs.hasReplies(id)) {
                blogs.blankComment(id, now);         // the replies under it stay
                repository.clearPurge(id);
            } else {
                blogs.deleteComment(id);
            }
        }
        for (long id : repository.expired("blogs", now, PURGE_BATCH)) {
            media.discard(blogs.mediaIds(id));
            blogs.deleteBlog(id);
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<ReasonRef> reasons() {
        return reports.reasons().stream().map(r -> new ReasonRef(r.id(), r.label())).toList();
    }

    private CaseRow openCase(long caseId) {
        CaseRow c = repository.find(caseId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (!"OPEN".equals(c.status())) {
            throw ApiException.conflict(ALREADY_HANDLED);
        }
        return c;
    }

    /** The reported row is locked before the case; read again after the locks, for its status at this moment. */
    private CaseRow lockedCase(long caseId) {
        CaseRow c = openCase(caseId);
        lockTarget(c);
        if (!repository.lockOpen(caseId)) {
            throw ApiException.conflict(ALREADY_HANDLED);
        }
        return repository.find(caseId).orElseThrow(() -> ApiException.conflict(ALREADY_HANDLED));
    }

    private void lockTarget(CaseRow c) {
        switch (c.targetType()) {
            case "BLOG" -> blogs.lockBlog(c.targetId());
            case "COMMENT" -> {
                blogs.lockBlog(c.blogId());
                blogs.lockComment(c.targetId());
            }
            case "REVIEW" -> repository.lockReview(c.targetId());
            default -> chat.lockForReport(c.targetId());
        }
    }

    private static boolean hidden(CaseRow c) {
        return "HIDDEN".equals(c.contentStatus());
    }

    /** Removed by a Moderator and still kept, so it can be restored. */
    private static boolean removed(CaseRow c) {
        return "REMOVE".equals(c.decision()) && !"MESSAGE".equals(c.targetType()) && "REMOVED".equals(c.contentStatus());
    }

    private CaseLine line(CaseRow c) {
        Message message = message(c);
        String text = message != null ? message.text() : c.body();
        return new CaseLine(c.id(), c.kind(), label(c), excerpt(text), c.authorName(), c.reportCount(), c.topReason(),
                c.firstReportedAt(), c.priority(), hidden(c), removed(c) ? c.purgeAt() : null);
    }

    private static String label(CaseRow c) {
        return switch (c.kind()) {
            case "POST" -> "Bài viết";
            case "QA" -> "Câu hỏi";
            case "ANSWER" -> "Câu trả lời";
            case "COMMENT" -> "Bình luận";
            case "REVIEW" -> "Review tại " + c.title();
            default -> "Tin nhắn";
        };
    }

    private static String link(CaseRow c) {
        if (c.blogId() != null) {
            return ("QA".equals(c.blogType()) ? "/qa/" : "/blog/") + c.blogId();
        }
        if (c.placeId() != null) {
            return "/map?place=" + c.placeId();
        }
        return c.eventId() != null ? "/events/" + c.eventId() : null;
    }

    private static String pointRef(CaseRow c) {
        return c.targetType();
    }

    /** What the removal notification opens: the post a comment stood under; nothing for a removed post or a message. */
    private static String noticeRef(CaseRow c) {
        return "COMMENT".equals(c.targetType()) ? ("QA".equals(c.blogType()) ? "QUESTION" : "BLOG") : null;
    }

    private static Long noticeId(CaseRow c) {
        return "COMMENT".equals(c.targetType()) ? c.blogId() : null;
    }

    /** The restored content can be opened again. */
    private static String restoredRef(CaseRow c) {
        return switch (c.targetType()) {
            case "REVIEW" -> "REVIEW";
            default -> "QA".equals(c.blogType()) ? "QUESTION" : "BLOG";
        };
    }

    private static Long restoredId(CaseRow c) {
        return "REVIEW".equals(c.targetType()) ? Long.valueOf(c.targetId()) : c.blogId();
    }

    private static String what(CaseRow c) {
        return switch (c.kind()) {
            case "POST" -> "Bài viết “" + c.title() + "”";
            case "QA" -> "Câu hỏi “" + c.title() + "”";
            case "ANSWER" -> "Câu trả lời của bạn ở “" + c.title() + "”";
            case "COMMENT" -> "Bình luận của bạn ở “" + c.title() + "”";
            case "REVIEW" -> "Review của bạn về “" + c.title() + "”";
            default -> "Một tin nhắn của bạn";
        };
    }

    private static String authorText(CaseRow c, String because) {
        return what(c) + " đã bị Moderator gỡ vì: " + because + ". −" + Math.abs(PointService.CONTENT_REMOVED) + " điểm.";
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= EXCERPT ? oneLine : oneLine.substring(0, EXCERPT).strip() + "…";
    }

    private record Message(String text, int files) {
    }

    /** A reported message: its plain text, or the text of the body the reporter revealed ({@code {text, files}}). */
    private Message message(CaseRow c) {
        if (!"MESSAGE".equals(c.kind())) {
            return null;
        }
        if (!c.encrypted()) {
            return new Message(c.body(), 0);
        }
        if (c.evidence() == null) {
            return new Message(null, 0);
        }
        try {
            JsonNode body = json.readTree(c.evidence());
            return new Message(body.path("text").asText(null), body.path("files").size());
        } catch (JsonProcessingException e) {
            return new Message(c.evidence(), 0);
        }
    }
}
