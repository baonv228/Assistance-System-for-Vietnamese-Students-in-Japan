package com.sep490.vsa.feature.tag;

import java.time.Instant;
import java.util.List;

/** Requests and answers of the identity tags: UC-U09 (Tôi → "Xin tag định danh") and UC-M01 / UC-M02 (Moderator). */
public final class TagDtos {

    private TagDtos() {
    }

    public record ProofType(long id, String name) {
    }

    /**
     * A tag the user can ask for. {@code selfieOnly}: only a portrait photo, no proof type to choose ("Người Nhật").
     * {@code owned}: the user has it already (cannot be chosen).
     */
    public record TagOption(long id, String name, String description, String color, boolean selfieOnly,
                            List<ProofType> proofTypes, boolean owned) {
    }

    /** The request waiting for a Moderator, if any: the page shows it instead of the form (UC-U09 AF4). */
    public record PendingRequest(String tagName, Instant submittedAt, Instant expiresAt) {
    }

    public record TagCompose(List<TagOption> tags, PendingRequest pending, int keepDays, int photoMaxMb) {
    }

    /** Part "request" of the multipart form; the photo is the part "photo". */
    public record TagRequestForm(Long tagId, Long proofTypeId, Boolean consent) {
    }

    public record Submitted(String message, PendingRequest pending) {
    }

    // ---------------------------------------------------------------- Moderator

    /**
     * One line of "Yêu cầu tag".
     *
     * @param daysLeft whole days before the photo is deleted by itself (red under 3)
     */
    public record RequestRow(long id, long userId, String userName, String tagName, String tagColor,
                             String proofType, Instant submittedAt, long daysLeft) {
    }

    public record RequestPage(List<RequestRow> items, int page, int size, long total, List<TagOption> tags) {
    }

    /** @param photoUrl the page loads the photo through the Moderator API (no public address) */
    public record RequestDetail(long id, long userId, String userName, String tagName, String tagColor,
                                boolean selfieOnly, String proofType, Instant submittedAt, Instant expiresAt,
                                long daysLeft, String photoUrl, List<String> rejectReasons) {
    }

    public record RejectForm(String reason) {
    }

    public record Done(String message) {
    }
}
