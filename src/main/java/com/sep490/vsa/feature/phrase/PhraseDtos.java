package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.feature.report.ContentReportService.Reason;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Requests and answers of "Nhật ngữ" (/api/phrases). */
public final class PhraseDtos {

    private PhraseDtos() {
    }

    /**
     * A learning topic ("Thanh toán") or a place category ("Cửa hàng tiện lợi") with how many phrases it holds and
     * how many of them the signed-in Member keeps as "Đã thuộc" (0 for guests).
     */
    public record Group(String id, String name, String icon, int count, int learned) {
    }

    /**
     * One of the two parts of the start page, "Mẫu câu" (kind PHRASE) or "Từ vựng" (VOCAB): how many entries of that
     * kind there are and how many the Member keeps as "Đã thuộc", and its topics and place categories, each counted
     * over that kind only (only those holding at least one entry of it).
     */
    public record Part(String kind, String name, String icon, int count, int learned, List<Group> topics,
                       List<Group> categories) {
    }

    /**
     * The start page: the two parts (Mẫu câu, Từ vựng), whether the viewer may mark and report (signed-in Members),
     * the report reasons and how many entries the Member saved ("Đã lưu", 0 for others).
     */
    public record Overview(List<Part> parts, boolean member, List<Reason> reportReasons, int savedCount) {
    }

    public record CategoryRef(String id, String name, String icon) {
    }

    public record TopicRef(String id, String name) {
    }

    /**
     * {@code kind} PHRASE (Mẫu câu) or VOCAB (Từ vựng). {@code learning} / {@code learned}: the Member's last flash card
     * answer ("Chưa thuộc" / "Đã thuộc"; both false = not answered yet). {@code saved}: the Member saved it ("Lưu").
     */
    public record PhraseCard(long id, String kind, String japanese, String romaji, String meaningVi,
                             CategoryRef category, List<TopicRef> topics, boolean learning, boolean learned,
                             boolean saved) {
    }

    public record ReportRequest(@NotNull(message = "Hãy chọn lý do báo cáo.") Long reasonId, String note) {
    }

    /** A flash card answer: {@code learned} true = "Đã thuộc", false = "Chưa thuộc". */
    public record FlashRequest(@NotNull(message = "Hãy chọn Chưa thuộc hoặc Đã thuộc.") Boolean learned) {
    }

    /** The marks of one phrase after a change. */
    public record Marks(boolean learning, boolean learned) {
    }

    /** "Lưu" / "Bỏ lưu" of one phrase: saved or not after the change. */
    public record SaveState(boolean saved) {
    }
}
