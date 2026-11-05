package com.sep490.vsa.feature.report;

import java.time.Instant;
import java.util.List;

/** Bodies of /api/moderator/reports (UC-M03, UC-M04). */
public final class ReportCaseDtos {

    private ReportCaseDtos() {
    }

    public record ReasonRef(long id, String label) {
    }

    /**
     * One line of the queue.
     *
     * @param kind   POST, QA, ANSWER, COMMENT, REVIEW or MESSAGE
     * @param label  "Bài viết", "Review tại Ga Nippori"…
     * @param hidden the content is hidden for now (over the threshold)
     * @param purgeAt on tab "Đã gỡ": when it is deleted for good
     */
    public record CaseLine(long id, String kind, String label, String excerpt, String authorName, int reportCount,
                           String topReason, Instant firstReportedAt, String priority, boolean hidden, Instant purgeAt) {
    }

    /** One page of a tab, with the size of every tab (what the filters leave in them). */
    public record CasePage(List<CaseLine> items, int page, int size, long total, long urgentCount, long normalCount,
                           long removedCount, List<ReasonRef> reasons) {
    }

    public record ReasonCount(String label, int count) {
    }

    /** @param falseReports how many of this member's reports were false so far */
    public record Reporter(long id, String name, String reason, String note, int falseReports, Instant reportedAt) {
    }

    public record MediaRef(long id, String type, String url) {
    }

    /**
     * Everything the Moderator decides on.
     *
     * @param title     the post's or question's title, the place or event of a review; null for a message
     * @param text      the full text; of an encrypted message, what the reporter revealed (checked by its franking tag)
     * @param files     how many encrypted files a reported message carries (never readable here)
     * @param link      where the content can be seen, null while it is hidden and for a message
     * @param authorStatus ACTIVE, LOCKED or DELETED
     * @param removed      already removed (tab "Đã gỡ"): the only action is "Khôi phục"
     * @param removedBecause the reason given at the removal
     * @param removedAt    when it was removed
     * @param purgeAt      when it is deleted for good
     */
    public record CaseDetail(long id, String kind, String label, String priority, String title, String text,
                             boolean encrypted, int files, List<MediaRef> media, String link, boolean hidden,
                             long authorId, String authorName, String authorStatus, Instant contentCreatedAt,
                             Instant firstReportedAt, List<ReasonCount> reasons, List<Reporter> reporters,
                             List<ReasonRef> removeReasons, boolean removed, String removedBecause, Instant removedAt,
                             Instant purgeAt) {
    }

    /** "Gỡ nội dung": a reason from the report reasons, and an optional note the author also reads. */
    public record RemoveRequest(Long reasonId, String note) {
    }

    public record Done(String message) {
    }
}
