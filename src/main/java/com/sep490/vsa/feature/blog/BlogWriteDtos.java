package com.sep490.vsa.feature.blog;

import java.util.List;

/** JSON shapes of the Write Post screen (UC-11) and of "Đặt câu hỏi" in Hỏi-Đáp (feature/qa), which shares them. */
public final class BlogWriteDtos {

    private BlogWriteDtos() {
    }

    /**
     * The text part of a new post or question. Photos and the video travel as separate multipart parts. The kind
     * (POST or QA) comes from the endpoint: POST /api/blogs or POST /api/questions.
     *
     * @param title          the title, or the question
     * @param content        the content, or the detailed description of a question
     * @param placeIds         ASVSJ places ({@code id} of a place search result)
     * @param externalPlaceIds OpenStreetMap places ASVSJ does not have yet ({@code externalId} of a result without
     *                         id); they are added to ASVSJ when the post is saved. At most 5 places in all.
     */
    public record CreateRequest(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                List<String> externalPlaceIds) {
    }

    /**
     * The text part of an edited post or question ("Sửa bài"): the same fields as a new one plus the photos and
     * video already attached that the author keeps, in the order they should show. New files travel as multipart
     * parts like on create.
     *
     * @param keepMediaIds ids from {@link EditData#media()}; attached media not listed here is removed
     */
    public record UpdateRequest(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                List<String> externalPlaceIds, List<Long> keepMediaIds) {

        CreateRequest fields() {
            return new CreateRequest(title, content, topicIds, placeIds, externalPlaceIds);
        }
    }

    /**
     * Answer of create and update.
     *
     * @param status PUBLISHED, or PROCESSING (photos/video are being scanned, only the author sees it)
     */
    public record Created(Long id, String type, String status) {
    }

    /**
     * A photo or the video already attached to a post, shown on the edit form with a remove button.
     *
     * @param type       IMAGE or VIDEO
     * @param scanStatus PASSED, or PROCESSING while it is still being scanned
     */
    public record EditMedia(Long id, String type, String url, String scanStatus, int sortOrder) {
    }

    /**
     * The edit form ("Sửa bài" / "Sửa câu hỏi"): what the post holds now plus the same options as a new post.
     *
     * @param status PUBLISHED, PROCESSING or REJECTED
     */
    public record EditData(Long id, String type, String status, String title, String content, List<Long> topicIds,
                           List<BlogDtos.PlaceRef> places, List<EditMedia> media, ComposeOptions options) {
    }

    /**
     * @param other       the topic "Khác", for posts that fit no other topic
     * @param scamWarning "Cảnh báo lừa đảo": linked places get the "!" mark on the map
     */
    public record TopicOption(Long id, String name, boolean other, boolean scamWarning) {
    }

    /** The rules the page shows and checks before sending; the server checks them again. */
    public record Limits(int titleMax, int contentMax, int topicsMax, int placesMax, int imagesMax, int imageMaxMb,
                         int videosMax, int videoMaxSeconds, int videoMaxMb, boolean questionContentRequired) {
    }

    /**
     * Today's usage against the new-account limits (BR-07), shown as "Tài khoản mới, hôm nay: …".
     *
     * @param newAccount true while the account is younger than {@code newAccountDays}; the limits apply only then
     */
    public record Quota(boolean newAccount, int newAccountDays, int accountAgeDays, int postsUsed, int postsLimit,
                        int pinsUsed, int pinsLimit, int videosUsed, int videosLimit) {
    }

    /** @param canPostLinks the author has an identity tag (BR-22) */
    public record ComposeOptions(List<TopicOption> topics, Limits limits, Quota quota, boolean canPostLinks) {
    }

    /** A published "Cảnh báo lừa đảo" post (not a question) linked to a place: the Map shows "!" on that pin (BR-13). */
    public record ScamWarning(Long placeId, Long blogId, String title) {
    }
}
