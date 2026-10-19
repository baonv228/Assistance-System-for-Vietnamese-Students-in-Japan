package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.place.PlaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Field rules of Write Post (UC-11, step 1: required fields and lengths, MSG02 / MSG08 / MSG37).
 * Pure functions so they can be tested without a database. Values marked "cần chốt" in the spec are
 * constants here; change them in one place when the team decides.
 */
final class BlogWriteRules {

    /** Title or question, required. 150 is still "cần chốt". */
    static final int TITLE_MAX = 150;
    static final int CONTENT_MAX = 5000;
    static final int TOPICS_MAX = 3;
    static final int PLACES_MAX = 5;
    /** Photo rules for posts are "chờ chốt"; the review rule (5 photos, 20 MB each) is used meanwhile. */
    static final int IMAGES_MAX = 5;
    static final int VIDEOS_MAX = 1;
    /** Whether a question needs a detailed description is "cần chốt"; optional for now. */
    static final boolean QUESTION_CONTENT_REQUIRED = false;

    static final String MSG37 = "Hãy nhập tiêu đề và nội dung cho bài viết.";

    private BlogWriteRules() {
    }

    /** The request after trimming: type upper-cased, title on one line, ids without nulls, blanks or repeats. */
    record Draft(String type, String title, String content, List<Long> topicIds, List<Long> placeIds,
                 List<String> externalPlaceIds) {

        boolean question() {
            return "QA".equals(type);
        }
    }

    /** @param kind POST or QA, decided by the endpoint the request came to */
    static Draft clean(CreateRequest request, String kind) {
        String type = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        if (request == null) {
            return new Draft(type, "", "", List.of(), List.of(), List.of());
        }
        String title = request.title() == null ? "" : request.title().replaceAll("\\s+", " ").strip();
        String content = request.content() == null ? "" : request.content().replace("\r\n", "\n").strip();
        return new Draft(type, title, content, distinct(request.topicIds()), distinct(request.placeIds()),
                distinctText(request.externalPlaceIds()));
    }

    /**
     * @return field name → message, in the order the fields appear on the screen; empty when all is fine
     */
    static Map<String, String> check(Draft d, int imageCount, int videoCount) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean question = d.question();
        if (!"POST".equals(d.type()) && !question) {
            errors.put("type", "Loại bài không hợp lệ.");
            return errors;
        }
        String titleName = question ? "Câu hỏi" : "Tiêu đề";
        if (d.title().isEmpty()) {
            errors.put("title", question ? "Hãy nhập câu hỏi." : "Hãy nhập tiêu đề.");
        } else if (d.title().length() > TITLE_MAX) {
            errors.put("title", titleName + " tối đa " + TITLE_MAX + " ký tự.");
        }
        if (d.topicIds().isEmpty()) {
            errors.put("topicIds", "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”.");
        } else if (d.topicIds().size() > TOPICS_MAX) {
            errors.put("topicIds", "Chọn tối đa " + TOPICS_MAX + " chủ đề.");
        }
        if (d.placeIds().size() + d.externalPlaceIds().size() > PLACES_MAX) {
            errors.put("placeIds", "Chọn tối đa " + PLACES_MAX + " địa điểm.");
        } else if (!d.externalPlaceIds().stream().allMatch(PlaceService::validExternalId)) {
            errors.put("placeIds", "Có địa điểm không hợp lệ. Hãy chọn lại địa điểm.");
        }
        boolean contentRequired = !question || QUESTION_CONTENT_REQUIRED;
        if (d.content().isEmpty() && contentRequired) {
            errors.put("content", question ? "Hãy nhập mô tả chi tiết." : "Hãy nhập nội dung bài viết.");
        } else if (d.content().length() > CONTENT_MAX) {
            errors.put("content", (question ? "Mô tả chi tiết" : "Nội dung") + " tối đa 5.000 ký tự.");
        }
        if (imageCount > IMAGES_MAX) {
            errors.put("images", MediaService.IMAGE_RULE);
        }
        if (videoCount > VIDEOS_MAX) {
            errors.put("video", "Chỉ đính kèm được 1 video.");
        }
        return errors;
    }

    /** The line shown above the form: MSG37 when a post lacks its title or content, else the first error. */
    static String summary(Draft d, Map<String, String> errors) {
        boolean missingText = (d.title().isEmpty() && errors.containsKey("title"))
                || (d.content().isEmpty() && errors.containsKey("content"));
        if ("POST".equals(d.type()) && missingText) {
            return MSG37;
        }
        return errors.values().stream().findFirst().orElse("Dữ liệu không hợp lệ");
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
    }

    private static List<String> distinctText(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(v -> !v.isEmpty()).toList()));
    }
}
