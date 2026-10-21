package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlogFeedQueryTest {

    @Test
    void foldsVietnameseAccentsCaseAndSpaces() {
        assertThat(BlogFeedQuery.fold("  Phân loại RÁC   ở Đà Nẵng ")).isEqualTo("phan loai rac o da nang");
        assertThat(BlogFeedQuery.fold("Ưu đãi")).isEqualTo("uu dai");
    }

    @Test
    void defaultsWhenNothingIsGiven() {
        BlogFeedQuery q = BlogFeedQuery.of(null, null, null, null, null, null);
        assertThat(q.keyword()).isNull();
        assertThat(q.topicIds()).isEmpty();
        assertThat(q.sort()).isEqualTo(BlogFeedQuery.Sort.NEWEST);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(BlogFeedQuery.DEFAULT_SIZE);
        assertThat(q.filtered()).isFalse();
    }

    @Test
    void blankKeywordIsNoSearch() {
        assertThat(BlogFeedQuery.of("   ", null, null, null, null, null).keyword()).isNull();
    }

    @Test
    void keywordOver100CharactersIsRejected() {
        String ok = "a".repeat(100);
        assertThat(BlogFeedQuery.of(ok, null, null, null, null, null).keyword()).hasSize(100);
        assertThatThrownBy(() -> BlogFeedQuery.of(ok + "b", null, null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parsesSortIgnoringCaseAndSortingIsNotAFilter() {
        BlogFeedQuery q = BlogFeedQuery.of(null, null, "likes", null, null, null);
        assertThat(q.sort()).isEqualTo(BlogFeedQuery.Sort.LIKES);
        assertThat(q.filtered()).isFalse();
        assertThat(BlogFeedQuery.of(null, List.of(2L), null, null, null, null).filtered()).isTrue();
        assertThatThrownBy(() -> BlogFeedQuery.of(null, null, "VIDEO", null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void clampsPagingAndCleansTopicIds() {
        BlogFeedQuery q = BlogFeedQuery.of(null, List.of(3L, 3L, -1L, 5L), null, -2, 500, 7L);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(BlogFeedQuery.MAX_SIZE);
        assertThat(q.topicIds()).containsExactly(3L, 5L);
        assertThat(q.viewerId()).isEqualTo(7L);
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(BlogFeedRepository.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    void excerptCutsAtAWord() {
        assertThat(BlogFeedService.excerpt("ngắn", 180)).isEqualTo("ngắn");
        String text = "một hai ba bốn năm sáu bảy tám chín mười";
        String cut = BlogFeedService.excerpt(text, 20);
        assertThat(cut).endsWith("…").doesNotContain("  ");
        assertThat(cut.length()).isLessThanOrEqualTo(21);
        assertThat(text).startsWith(cut.substring(0, cut.length() - 1));
    }

    @Test
    void featuredKeepsOnePostPerAuthorAndAtMostThree() {
        List<Candidate> sorted = List.of(
                new Candidate(1L, 10L, 40), new Candidate(2L, 10L, 35), new Candidate(3L, 11L, 30),
                new Candidate(4L, 12L, 20), new Candidate(5L, 13L, 15));
        assertThat(FeaturedSnapshotService.top(sorted, 3, 10)).extracting(Candidate::blogId).containsExactly(1L, 3L, 4L);
        // featured_top_n = 1 (Admin setting)
        assertThat(FeaturedSnapshotService.top(sorted, 1, 10)).extracting(Candidate::blogId).containsExactly(1L);
    }

    @Test
    void featuredIgnoresScoresBelowTheMinimum() {
        List<Candidate> sorted = List.of(new Candidate(1L, 10L, 12), new Candidate(2L, 11L, 9));
        assertThat(FeaturedSnapshotService.top(sorted, 3, 10)).extracting(Candidate::blogId).containsExactly(1L);
        // featured_min_score (Admin setting) lowered to 5, raised to 13
        assertThat(FeaturedSnapshotService.top(sorted, 3, 5)).extracting(Candidate::blogId).containsExactly(1L, 2L);
        assertThat(FeaturedSnapshotService.top(sorted, 3, 13)).isEmpty();
    }
}
