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

    public record Counts(int places, int posts, int questions, int total) {
    }

    /** Each list is newest saved first. */
    public record SavedResponse(List<SavedPlace> places, List<SavedBlog> posts, List<SavedBlog> questions,
                                Counts counts) {
    }
}
