package com.sep490.vsa.feature.saved;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogFeedRepository;
import com.sep490.vsa.feature.saved.SavedDtos.Counts;
import com.sep490.vsa.feature.saved.SavedDtos.SavedBlog;
import com.sep490.vsa.feature.saved.SavedDtos.SavedPlace;
import com.sep490.vsa.feature.saved.SavedDtos.SavedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "Đã lưu" (UC Browse Favorites): the places, posts and questions a Member saved. */
@Service
public class SavedService {

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";   // MSG18

    private final SavedRepository repository;
    private final BlogFeedRepository feed;

    public SavedService(SavedRepository repository, BlogFeedRepository feed) {
        this.repository = repository;
        this.feed = feed;
    }

    @Transactional(readOnly = true)
    public SavedResponse list(long userId) {
        member(userId);
        List<SavedPlace> places = repository.places(userId);
        List<SavedBlog> posts = repository.blogs(userId, "POST");
        List<SavedBlog> questions = repository.blogs(userId, "QA");

        List<Long> blogIds = new ArrayList<>();
        posts.forEach(b -> blogIds.add(b.id()));
        questions.forEach(b -> blogIds.add(b.id()));
        Map<Long, List<TopicRef>> topics = feed.topicsOf(blogIds);
        posts = withTopics(posts, topics);
        questions = withTopics(questions, topics);

        return new SavedResponse(places, posts, questions, new Counts(places.size(), posts.size(), questions.size(),
                places.size() + posts.size() + questions.size()));
    }

    /** Only signed-in Users (Members) have saved items, like the save action itself; staff roles get MSG18. */
    private void member(long userId) {
        var account = repository.account(userId).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
    }

    private static List<SavedBlog> withTopics(List<SavedBlog> blogs, Map<Long, List<TopicRef>> topics) {
        return blogs.stream()
                .map(b -> new SavedBlog(b.id(), b.type(), b.title(), b.author(), topics.getOrDefault(b.id(), List.of()),
                        b.likeCount(), b.commentCount(), b.solved(), b.createdAt(), b.savedAt()))
                .toList();
    }
}
