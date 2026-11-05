package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.feature.phrase.PhraseDtos.CategoryRef;
import com.sep490.vsa.feature.phrase.PhraseDtos.TopicRef;

import java.time.Instant;
import java.util.List;

/** Requests and answers of the Academic Manager's pages (/api/academic/**). */
public final class AcademicDtos {

    private AcademicDtos() {
    }

    // ---------------------------------------------------------------- overview

    /** The tiles of Tổng quan: open reports, entries of kind PHRASE and of kind VOCAB. */
    public record Counts(int phraseReports, int phrases, int vocab) {
    }

    public record ThinTopic(String id, String name, int count) {
    }

    public record LogLine(String description, Instant at) {
    }

    /** Tổng quan: the work waiting, categories without any entry, topics with few entries, the manager's last actions. */
    public record Overview(Counts counts, List<CategoryRef> emptyCategories, List<ThinTopic> thinTopics,
                           List<LogLine> recent) {
    }

    // ---------------------------------------------------------------- learning topics

    /** A row of "Chủ đề học": its entries and the categories they belong to. */
    public record TopicRow(String id, String name, String icon, int count, List<String> categories) {
    }

    public record TopicRequest(String name, String icon) {
    }

    // ---------------------------------------------------------------- library

    public record Options(List<CategoryRef> categories, List<TopicRef> topics, List<String> icons) {
    }

    /** A library row with its open reports. */
    public record LibraryRow(long id, String kind, String japanese, String romaji, String meaningVi,
                             CategoryRef category, List<TopicRef> topics, int openReports) {
    }

    /** One page of the library; the form's choices come from /api/academic/options. */
    public record LibraryPage(List<LibraryRow> items, int page, int size, long total) {
    }

    /**
     * Thêm / sửa mục từ vựng. {@code kind} PHRASE or VOCAB; {@code confirmDuplicate}: save although the category
     * already has the same Japanese; {@code reportCaseId}: the edit answers that report ("Sửa mẫu câu này").
     */
    public record PhraseRequest(String categoryId, String kind, String japanese, String romaji, String meaningVi,
                                List<String> topicIds, boolean confirmDuplicate, Long reportCaseId) {
    }

    /** {@code saved} false with {@code duplicate}: the category has this Japanese already; ask before saving. */
    public record SaveResult(boolean saved, LibraryRow duplicate, String message) {
    }

    // ---------------------------------------------------------------- reports

    public record ReasonCount(String label, int count) {
    }

    public record Reporter(String displayName, String reason, String note, Instant at) {
    }

    /** An open report case on a phrase. */
    public record ReportCase(long caseId, String priority, int reportCount, Instant createdAt,
                             List<ReasonCount> reasons, List<Reporter> reporters, LibraryRow phrase) {
    }
}
