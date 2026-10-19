package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteRules.Draft;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlogWriteRulesTest {

    private static Draft draft(String type, String title, String content, List<Long> topics, List<Long> places) {
        return draft(type, title, content, topics, places, List.of());
    }

    private static Draft draft(String type, String title, String content, List<Long> topics, List<Long> places,
                               List<String> mapPlaces) {
        return BlogWriteRules.clean(new CreateRequest(title, content, topics, places, mapPlaces), type);
    }

    @Test
    void postNeedsTitleTopicAndContentAndSummaryIsMsg37() {
        Draft d = draft("POST", "  ", "", List.of(), List.of());
        Map<String, String> errors = BlogWriteRules.check(d, 0, 0);
        assertThat(errors).containsOnlyKeys("title", "topicIds", "content");
        assertThat(errors.get("title")).isEqualTo("Hãy nhập tiêu đề.");
        assertThat(BlogWriteRules.summary(d, errors)).isEqualTo(BlogWriteRules.MSG37);
    }

    @Test
    void questionNeedsOnlyTheQuestionAndATopic() {
        Draft d = draft("qa", "Gia hạn visa cần gì?", "", List.of(4L), List.of());
        assertThat(d.type()).isEqualTo("QA");
        assertThat(BlogWriteRules.check(d, 0, 0)).isEmpty();

        Draft empty = draft("QA", "", "", List.of(4L), List.of());
        Map<String, String> errors = BlogWriteRules.check(empty, 0, 0);
        assertThat(errors).containsOnlyKeys("title");
        assertThat(BlogWriteRules.summary(empty, errors)).isEqualTo("Hãy nhập câu hỏi.");
    }

    @Test
    void lengthsAreLimited() {
        Draft d = draft("POST", "a".repeat(151), "b".repeat(5001), List.of(1L), List.of());
        Map<String, String> errors = BlogWriteRules.check(d, 0, 0);
        assertThat(errors.get("title")).isEqualTo("Tiêu đề tối đa 150 ký tự.");
        assertThat(errors.get("content")).isEqualTo("Nội dung tối đa 5.000 ký tự.");

        Draft atLimit = draft("POST", "a".repeat(150), "b".repeat(5000), List.of(1L), List.of());
        assertThat(BlogWriteRules.check(atLimit, 0, 0)).isEmpty();
    }

    @Test
    void oneToThreeTopicsAndAtMostFivePlaces() {
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L, 2L, 3L, 4L), List.of()), 0, 0))
                .containsOnlyKeys("topicIds");
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L, 2L, 3L), List.of(1L, 2L, 3L, 4L, 5L, 6L)), 0, 0))
                .containsOnlyKeys("placeIds");
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L, 2L, 3L), List.of(1L, 2L, 3L, 4L, 5L)), 0, 0))
                .isEmpty();
    }

    @Test
    void mapPlacesCountTowardsTheFiveAndMustLookLikeOpenStreetMapIds() {
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L), List.of(1L, 2L, 3L),
                List.of("N772389775", "R4247312")), 0, 0)).isEmpty();
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L), List.of(1L, 2L, 3L),
                List.of("W253844168", "DEV-tokyo-tower")), 0, 0)).isEmpty();
        assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L), List.of(1L, 2L, 3L, 4L),
                List.of("N1", "N2")), 0, 0)).containsOnlyKeys("placeIds");
        for (String bad : List.of("ChIJN1t_tDeuEmsRUsoyG83frY4", "N0", "X123", "n123", "N12a", "node/123", "DEV-a b")) {
            assertThat(BlogWriteRules.check(draft("POST", "t", "c", List.of(1L), List.of(), List.of(bad)), 0, 0)
                    .get("placeIds")).as(bad).isEqualTo("Có địa điểm không hợp lệ. Hãy chọn lại địa điểm.");
        }

        Draft d = draft("POST", "t", "c", List.of(1L), List.of(), Arrays.asList(" N5 ", "N5", "", null));
        assertThat(d.externalPlaceIds()).containsExactly("N5");
    }

    @Test
    void repeatedOrMissingIdsAreDroppedBeforeCounting() {
        Draft d = draft("POST", "t", "c", Arrays.asList(1L, 1L, null, 2L, 2L, 3L), Arrays.asList(5L, 5L));
        assertThat(d.topicIds()).containsExactly(1L, 2L, 3L);
        assertThat(d.placeIds()).containsExactly(5L);
        assertThat(BlogWriteRules.check(d, 0, 0)).isEmpty();
    }

    @Test
    void titleIsKeptOnOneLineAndContentKeepsItsParagraphs() {
        Draft d = draft("POST", "  Năm điều\n  mình ước  ", "\r\nĐoạn 1\r\n\r\nĐoạn 2\n ", List.of(1L), List.of());
        assertThat(d.title()).isEqualTo("Năm điều mình ước");
        assertThat(d.content()).isEqualTo("Đoạn 1\n\nĐoạn 2");
    }

    @Test
    void atMostFivePhotosAndOneVideo() {
        Draft d = draft("POST", "t", "c", List.of(1L), List.of());
        assertThat(BlogWriteRules.check(d, 5, 1)).isEmpty();
        assertThat(BlogWriteRules.check(d, 6, 0)).containsOnlyKeys("images");
        assertThat(BlogWriteRules.check(d, 0, 2)).containsOnlyKeys("video");
    }

    @Test
    void unknownTypeIsRefused() {
        assertThat(BlogWriteRules.check(draft("NEWS", "t", "c", List.of(1L), List.of()), 0, 0))
                .containsOnlyKeys("type");
        assertThat(BlogWriteRules.check(BlogWriteRules.clean(null, null), 0, 0)).containsOnlyKeys("type");
    }
}
