package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.place.MapDtos.ReviewMedia;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Requests and answers of the Moderator's place pages (/api/moderator/pins|closed|places). */
public final class PlaceModerationDtos {

    private PlaceModerationDtos() {
    }

    public record CategoryRef(String id, String name, String icon) {
    }

    public record Reason(long id, String label) {
    }

    // ---------------------------------------------------------------- UC-M05 / UC-M06 "Ghim mới"

    /**
     * One pin waiting for approval.
     *
     * @param nearby             approved places within 200 m
     * @param possibleDuplicate  one of them has a similar name ("Có thể trùng")
     */
    public record PinRow(long id, String nameVi, String nameJa, String nameEn, CategoryRef category, long authorId,
                         String authorName, Instant createdAt, int nearby, boolean possibleDuplicate) {
    }

    public record PinQueue(List<PinRow> items, List<CategoryRef> categories) {
    }

    /** An approved place around a pin, nearest first; {@code similar} = the names look alike. */
    public record NearbyPlace(long id, String name, String nameJa, String categoryName, double lat, double lng,
                              int distance, boolean similar, int reviewCount) {
    }

    /** The pin's first review as the author sent it ({@code status} PUBLISHED, PROCESSING or REJECTED). */
    public record FirstReview(long id, int rating, String content, String status, List<ReviewMedia> media) {
    }

    /**
     * @param scanning the first review's files are still being scanned: "Duyệt" waits (UC-M06 AF3)
     */
    public record PinDetail(long id, String nameVi, String nameJa, String nameEn, String address, double lat,
                            double lng, CategoryRef category, long authorId, String authorName, Instant createdAt,
                            FirstReview review, List<NearbyPlace> nearby, List<Reason> rejectReasons,
                            List<CategoryRef> categories, boolean scanning) {
    }

    /** "Duyệt": the Vietnamese name and the category may be corrected first (empty = keep). */
    public record ApproveRequest(String nameVi, String categoryId) {
    }

    public record RejectRequest(Long reasonId) {
    }

    public record MergeRequest(Long targetId) {
    }

    /** @param placeId the place the Moderator should look at next (the approved pin, the merge target) */
    public record Done(String message, Long placeId) {
    }

    // ---------------------------------------------------------------- "Báo đóng cửa" (UC-M04 AF4, AF5)

    /**
     * @param reports      "đã đóng cửa" reports not handled yet
     * @param lastReviewAt the newest published review, to judge whether people still go there
     */
    public record ClosedRow(long id, String name, CategoryRef category, int reports, String address,
                            Instant lastReviewAt, Instant closedAt, String osmId, Double lat, Double lng) {
    }

    /** @param waiting places that reached the threshold; {@code closed} the ones labelled, newest first */
    public record ClosedQueue(List<ClosedRow> waiting, List<ClosedRow> closed, int threshold) {
    }

    public record ClosedLabel(Boolean closed) {
    }

    // ---------------------------------------------------------------- UC-M07 / UC-M08 "Địa điểm"

    public record AreaRef(long id, String name) {
    }

    /** A prefecture of the list filter; {@code name} = "Tokyo · 東京都". */
    public record PrefectureRef(String code, String name) {
    }

    /** One line of the places list (10 a page). */
    public record PlaceRow(long id, String nameVi, String nameJa, CategoryRef category, String source, String status,
                           boolean closed, String areaName, int reviewCount, Instant createdAt) {
    }

    public record PlacePage(List<PlaceRow> items, int page, int size, long total, List<CategoryRef> categories,
                            List<PrefectureRef> prefectures) {
    }

    /** One line of the place's "Lịch sử": {@code text} is ready to show ("Danh mục: Quán ăn → Siêu thị"). */
    public record HistoryItem(String action, String actorName, Instant at, String text) {
    }

    /**
     * Everything the place's details show, with the lists its forms need.
     *
     * @param removedReason why a Moderator took it off the map (status REMOVED)
     * @param mergedIntoId  with status MERGED, the place that was kept
     */
    public record PlaceInfo(long id, String nameVi, String nameJa, String nameEn, String address, String osmId,
                            Double lat, Double lng, CategoryRef category, Long areaId, String areaName, String source,
                            String status, boolean closed, Instant closedAt, String removedReason, String rejectReason,
                            Long mergedIntoId, String mergedIntoName, long createdBy, String authorName,
                            Instant createdAt, int reviewCount, int saveCount, int closedReports,
                            List<HistoryItem> history, List<CategoryRef> categories, List<Reason> removeReasons) {
    }

    /**
     * "Thêm địa điểm" and "Sửa thông tin". {@code osmId} only when adding a place found on OpenStreetMap ("N772389775");
     * {@code confirmDuplicate} = "Vẫn lưu" after the "Có thể trùng" warning.
     */
    public record PlaceForm(String osmId, String nameVi, String nameJa, String nameEn, String address,
                            String categoryId, Double lat, Double lng, boolean confirmDuplicate) {
    }

    /**
     * The answer of a save. With {@code duplicates} and no {@code placeId} nothing was saved: the form shows "Có thể
     * trùng với địa điểm đã có" with "Mở địa điểm đó" / "Vẫn lưu".
     */
    public record PlaceSaved(String message, Long placeId, List<NearbyPlace> duplicates) {
    }

    /**
     * A place picked in the OpenStreetMap results: {@code existingId} when Senpai ơi has it already ("Mở"), else
     * {@code prefill} for the form.
     */
    public record OsmPick(Long existingId, PlaceForm prefill) {
    }

    public record RemoveRequest(Long reasonId, String note) {
    }

    // ---------------------------------------------------------------- UC-M09 "Gộp trùng lặp"

    public record MergeSide(long id, String name, String nameJa, String source, int reviewCount) {
    }

    /** One pair of the queue; {@code level} HIGH ("Cao") or MEDIUM ("Trung bình"). */
    public record MergePair(MergeSide a, MergeSide b, int distance, String level) {
    }

    public record MergeQueue(List<MergePair> items, int page, int size, long total) {
    }

    /** What moves to the place that is kept: published reviews, saves, linked posts, guide items. */
    public record MergeCounts(int reviews, int saves, int posts, int guideItems) {
    }

    /** One side of "So sánh". */
    public record MergePlace(long id, String nameVi, String nameJa, String nameEn, String address, CategoryRef category,
                             Long areaId, String areaName, Double lat, Double lng, String source, String osmId,
                             boolean closed, MergeCounts counts) {
    }

    /** @param sameAuthors people who reviewed both places: only their newer review is kept */
    public record MergeCompare(MergePlace a, MergePlace b, int distance, String level, int sameAuthors) {
    }

    /**
     * "Gộp": {@code fields} says, for each of nameVi, nameJa, nameEn, address, categoryId, areaId, position, whether
     * the kept place takes the value of the other place ("MERGED") or keeps its own ("KEPT", also when missing).
     * {@code confirmed} = the box "Tôi đã kiểm tra…".
     */
    public record MergeDecision(Long keptId, Long mergedId, Map<String, String> fields, boolean confirmed) {
    }

    public record DismissRequest(Long aId, Long bId) {
    }

    /** A place found by "Chọn địa điểm để so sánh", with its distance from the other place when there is one. */
    public record MergePick(long id, String name, String categoryName, String address, Integer distance) {
    }
}
