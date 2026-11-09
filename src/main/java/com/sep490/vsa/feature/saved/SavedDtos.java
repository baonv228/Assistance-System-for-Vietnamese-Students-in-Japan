package com.sep490.vsa.feature.saved;

import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;

import java.time.Instant;
import java.util.List;

/** JSON shapes of "Đã lưu" (UC Browse Favorites): GET /api/me/saved. */
public final class SavedDtos {

    private SavedDtos() {
    }

    /**
     * A saved place that is still on the map. A place merged into another one shows the place it was merged into.
     *
     * @param closed the Moderator set the "Đã đóng cửa" label (the place stays saved)
     */
    public record SavedPlace(Long id, String name, String categoryId, String categoryName, String icon, String address,
                             boolean closed, Instant savedAt) {
    }

    public record SavedAuthor(Long id, String displayName, String avatarUrl) {
    }

    /**
     * A saved post or question that is still published.
     *
     * @param type   POST (opens /blog/{id}) or QA (opens /qa/{id})
     * @param solved questions only: the best answer is still published and its writer's account active
     */
    public record SavedBlog(Long id, String type, String title, SavedAuthor author, List<TopicRef> topics,
                            int likeCount, int commentCount, boolean solved, Instant createdAt, Instant savedAt) {
    }

    /**
     * A saved guide ("Cẩm nang") that is still published and not deleted; opens /guides/{id}.
     *
     * @param summary   plain text, cut to {@link SavedRepository#SUMMARY_MAX} characters with "…"; null when empty
     * @param stageName the guide's stage ("Giai đoạn")
     * @param topicName the guide's topic, else its stage (as on the guide list)
     */
    public record SavedGuide(Long id, String title, String summary, String stageName, String topicName,
                             Instant savedAt) {
    }

    public record Counts(int places, int posts, int questions, int guides, int total) {
    }

    /** Each list is newest saved first. */
    public record SavedResponse(List<SavedPlace> places, List<SavedBlog> posts, List<SavedBlog> questions,
                                List<SavedGuide> guides, Counts counts) {
    }
}
