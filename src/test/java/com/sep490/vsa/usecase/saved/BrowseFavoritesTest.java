package com.sep490.vsa.usecase.saved;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.13 Browse Favorites (page "Đã lưu", {@code GET /api/me/saved}). The SRS has no section of its own for it: the
 * tests follow UC-25 "View Saved Location, blog", the profile screen spec ("Đã lưu ({n})" button), View Post Details /
 * View Question Details (a post is opened "from Blog, Home or 'Đã lưu'") and CLAUDE.md ("Đã lưu": ACTIVE places, a
 * merged place shown as the place it was merged into, PUBLISHED posts and questions of active accounts, published and
 * not deleted guides with the summary cut to 160, not deleted "Nhật ngữ" entries, newest saved first; Members only;
 * {@code savedCount} on {@code /api/me/profile} counts the same items).
 */
@DisplayName("UC 1.13 – Browse Favorites (Duyệt mục yêu thích)")
class BrowseFavoritesTest extends SavedTestSupport {

    @Test
    @DisplayName("TC-1.13-01 [Luồng chính] Trang \"Đã lưu\" liệt kê địa điểm, bài viết, câu hỏi, cẩm nang và mẫu câu đã lưu cùng số lượng từng loại")
    void listsEveryKindOfSavedItem() throws Exception {
        String t = token();
        long me = member();
        long author = member();
        long place = place("Quán " + t);
        long post = publishedPost(author, "Bài " + t, "Nội dung");
        long question = publishedQuestion(author, "Hỏi " + t, "Mô tả");
        long guide = sampleGuides(1).get(0);
        long phrase = samplePhrases(1).get(0);
        // Saved through the real "Lưu" buttons
        postAs(me, null, "/api/map/places/{id}/save", place).andExpect(status().isOk());
        postAs(me, null, "/api/blogs/{id}/save", post).andExpect(status().isOk());
        postAs(me, null, "/api/questions/{id}/save", question).andExpect(status().isOk());
        postAs(me, null, "/api/guides/{id}/save", guide).andExpect(status().isOk());
        postAs(me, null, "/api/phrases/{id}/save", phrase).andExpect(status().isOk());

        JsonNode saved = data(getAs(me, "/api/me/saved")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        JsonNode p = saved.get("places").get(0);
        assertThat(p.get("id").asLong()).isEqualTo(place);
        assertThat(p.get("name").asText()).isEqualTo("Quán " + t);
        assertThat(p.get("categoryName").asText()).isNotBlank();
        assertThat(p.get("closed").asBoolean()).isFalse();
        JsonNode b = saved.get("posts").get(0);
        assertThat(b.get("id").asLong()).isEqualTo(post);
        assertThat(b.get("type").asText()).isEqualTo("POST");
        assertThat(b.get("title").asText()).isEqualTo("Bài " + t);
        assertThat(b.get("author").get("displayName").asText()).isEqualTo(displayName(author));
        assertThat(b.get("topics").get(0).get("name").asText()).isEqualTo("Kinh nghiệm");
        assertThat(b.get("savedAt").isNull()).isFalse();
        JsonNode q = saved.get("questions").get(0);
        assertThat(q.get("id").asLong()).isEqualTo(question);
        assertThat(q.get("type").asText()).isEqualTo("QA");
        assertThat(q.get("solved").asBoolean()).isFalse();
        assertThat(saved.get("guides").get(0).get("id").asLong()).isEqualTo(guide);
        assertThat(saved.get("guides").get(0).get("title").asText()).isNotBlank();
        JsonNode ph = saved.get("phrases").get(0);
        assertThat(ph.get("id").asLong()).isEqualTo(phrase);
        assertThat(ph.get("japanese").asText()).isNotBlank();
        assertThat(ph.get("kind").asText()).isIn("PHRASE", "VOCAB");
        JsonNode counts = saved.get("counts");
        assertThat(counts.get("places").asInt()).isEqualTo(1);
        assertThat(counts.get("posts").asInt()).isEqualTo(1);
        assertThat(counts.get("questions").asInt()).isEqualTo(1);
        assertThat(counts.get("guides").asInt()).isEqualTo(1);
        assertThat(counts.get("phrases").asInt()).isEqualTo(1);
        assertThat(counts.get("total").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-1.13-02 [Luồng chính] Mỗi danh sách xếp mục lưu gần nhất lên đầu")
    void newestSavedFirst() throws Exception {
        String t = token();
        long me = member();
        long author = member();
        long olderPost = publishedPost(author, "Cũ " + t, "x");
        long newerPost = publishedPost(author, "Mới " + t, "x");
        savePost(me, newerPost, minutesAgo(30));
        savePost(me, olderPost, minutesAgo(5));          // written first, saved last
        long placeA = place("A " + t);
        long placeB = place("B " + t);
        savePlace(me, placeA, minutesAgo(1));
        savePlace(me, placeB, minutesAgo(60));
        List<Long> guides = sampleGuides(2);
        saveGuide(me, guides.get(0), minutesAgo(50));
        saveGuide(me, guides.get(1), minutesAgo(2));
        List<Long> phrases = samplePhrases(2);
        savePhrase(me, phrases.get(0), minutesAgo(40));
        savePhrase(me, phrases.get(1), minutesAgo(3));

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));

        assertThat(ids(saved.get("posts"))).containsExactly(olderPost, newerPost);
        assertThat(ids(saved.get("places"))).containsExactly(placeA, placeB);
        assertThat(ids(saved.get("guides"))).containsExactly(guides.get(1), guides.get(0));
        assertThat(ids(saved.get("phrases"))).containsExactly(phrases.get(1), phrases.get(0));
    }

    @Test
    @DisplayName("TC-1.13-03 [Luồng chính] Nút \"Đã lưu (N)\" trên trang Tôi đếm đúng các mục đang hiện trên trang \"Đã lưu\"")
    void profileSavedCountMatchesTheList() throws Exception {
        String t = token();
        long me = member();
        long author = member();
        savePost(me, publishedPost(author, "Bài " + t, "x"), minutesAgo(3));
        savePost(me, publishedQuestion(author, "Hỏi " + t, "x"), minutesAgo(2));
        savePlace(me, place("Quán " + t), minutesAgo(1));
        saveGuide(me, sampleGuides(1).get(0), minutesAgo(1));
        savePhrase(me, samplePhrases(1).get(0), minutesAgo(1));
        long hiddenPost = insertBlog(author, "POST", "Ẩn " + t, "x", "HIDDEN", Instant.now(), topic("Kinh nghiệm"));
        savePost(me, hiddenPost, minutesAgo(1));                 // saved but no longer shown: not counted

        int total = data(getAs(me, "/api/me/saved")).get("counts").get("total").asInt();
        int savedCount = data(getAs(me, "/api/me/profile").andExpect(status().isOk())).get("savedCount").asInt();

        assertThat(total).isEqualTo(5);
        assertThat(savedCount).isEqualTo(total);
    }

    @Test
    @DisplayName("TC-1.13-04 [Luồng thay thế] Chưa lưu gì: mọi danh sách rỗng, tổng 0 (trang hiện trạng thái trống)")
    void emptyForANewMember() throws Exception {
        long me = member();

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));

        for (String list : List.of("places", "posts", "questions", "guides", "phrases")) {
            assertThat(saved.get(list)).as(list).isEmpty();
        }
        assertThat(saved.get("counts").get("total").asInt()).isZero();
        getAs(me, "/api/me/profile").andExpect(jsonPath("$.data.savedCount").value(0));
    }

    @Test
    @DisplayName("TC-1.13-05 [Luồng thay thế] Bài/câu hỏi bị ẩn, bị gỡ, đang quét hoặc của tài khoản bị khóa/đã xóa không hiện, mục lưu vẫn được giữ")
    void contentOthersCannotSeeIsLeftOut() throws Exception {
        String t = token();
        long me = member();
        long author = member();
        long locked = member();
        long deleted = member();
        long shown = publishedPost(author, "Hiện " + t, "x");
        long hidden = insertBlog(author, "POST", "Ẩn " + t, "x", "HIDDEN", Instant.now(), topic("Kinh nghiệm"));
        long removed = insertBlog(author, "POST", "Gỡ " + t, "x", "REMOVED", Instant.now(), topic("Kinh nghiệm"));
        long scanning = insertBlog(author, "QA", "Quét " + t, "x", "PROCESSING", Instant.now(), topic("Kinh nghiệm"));
        long ofLocked = publishedPost(locked, "Khóa " + t, "x");
        long ofDeleted = publishedQuestion(deleted, "Xóa " + t, "x");
        for (long id : List.of(shown, hidden, removed, scanning, ofLocked, ofDeleted)) {
            savePost(me, id, minutesAgo(1));
        }
        setStatus(locked, "LOCKED");
        setStatus(deleted, "DELETED");

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));

        assertThat(ids(saved.get("posts"))).containsExactly(shown);
        assertThat(saved.get("questions")).isEmpty();
        assertThat(saved.get("counts").get("total").asInt()).isEqualTo(1);
        // Nothing is deleted: the saved rows are still there
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE user_id = ?", Integer.class, me))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("TC-1.13-06 [Luồng thay thế] Tài khoản tác giả được mở khóa thì bài đã lưu hiện lại")
    void unlockedAuthorsPostShowsAgain() throws Exception {
        long me = member();
        long author = member();
        long post = publishedPost(author, "Bài " + token(), "x");
        savePost(me, post, minutesAgo(1));
        setStatus(author, "LOCKED");
        assertThat(data(getAs(me, "/api/me/saved")).get("posts")).isEmpty();

        setStatus(author, "ACTIVE");

        assertThat(ids(data(getAs(me, "/api/me/saved")).get("posts"))).containsExactly(post);
    }

    @Test
    @DisplayName("TC-1.13-07 [Luồng thay thế – Bản đồ] Địa điểm bị gỡ không hiện; địa điểm đã gộp hiện thành địa điểm giữ lại và chỉ một lần; địa điểm đóng cửa vẫn hiện kèm nhãn")
    void placesFollowTheMap() throws Exception {
        String t = token();
        long me = member();
        long kept = place("Giữ " + t);
        long merged = mergedInto(kept, "Gộp " + t);
        long removed = place("Gỡ " + t);
        setPlaceStatus(removed, "REMOVED");
        long closed = place("Đóng " + t);
        jdbc.update("UPDATE places SET is_closed = TRUE, closed_at = NOW() WHERE id = ?", closed);
        savePlace(me, kept, minutesAgo(10));
        savePlace(me, merged, minutesAgo(5));
        savePlace(me, removed, minutesAgo(4));
        savePlace(me, closed, minutesAgo(3));

        JsonNode places = data(getAs(me, "/api/me/saved").andExpect(status().isOk())).get("places");

        assertThat(ids(places)).containsExactly(closed, kept);
        assertThat(places.get(0).get("closed").asBoolean()).isTrue();
        assertThat(places.get(1).get("name").asText()).isEqualTo("Giữ " + t);
        getAs(me, "/api/me/profile").andExpect(jsonPath("$.data.savedCount").value(2));
    }

    @Test
    @DisplayName("TC-1.13-08 [Luồng thay thế – Cẩm nang, Nhật ngữ] Cẩm nang đã xóa/chưa đăng và mẫu câu đã xóa không hiện")
    void deletedGuidesAndPhrasesAreLeftOut() throws Exception {
        String t = token();
        long me = member();
        long visibleGuide = sampleGuides(1).get(0);
        long deletedGuide = guide("Xóa " + t, "Tóm tắt");
        long draftGuide = guide("Nháp " + t, "Tóm tắt");
        long visiblePhrase = samplePhrases(1).get(0);
        long deletedPhrase = phrase("削除" + t);
        saveGuide(me, visibleGuide, minutesAgo(3));
        saveGuide(me, deletedGuide, minutesAgo(2));
        saveGuide(me, draftGuide, minutesAgo(1));
        savePhrase(me, visiblePhrase, minutesAgo(2));
        savePhrase(me, deletedPhrase, minutesAgo(1));
        jdbc.update("UPDATE guide_items SET deleted_at = NOW() WHERE id = ?", deletedGuide);
        jdbc.update("UPDATE guide_items SET published = FALSE WHERE id = ?", draftGuide);
        deletePhrase(deletedPhrase);

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));

        assertThat(ids(saved.get("guides"))).containsExactly(visibleGuide);
        assertThat(ids(saved.get("phrases"))).containsExactly(visiblePhrase);
        assertThat(saved.get("counts").get("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-1.13-09 [Luồng chính] Câu hỏi đã lưu hiện \"Đã giải quyết\" khi câu trả lời hay nhất còn hiện; không còn khi người trả lời bị khóa")
    void savedQuestionShowsTheSolvedState() throws Exception {
        long me = member();
        long asker = member();
        long answerer = member();
        long question = publishedQuestion(asker, "Hỏi " + token(), "Mô tả");
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, 'Câu trả lời', 'PUBLISHED')""",
                question, answerer);
        long answer = lastCommentId(question, answerer);
        jdbc.update("UPDATE blogs SET best_comment_id = ?, comment_count = 1 WHERE id = ?", answer, question);
        savePost(me, question, minutesAgo(1));

        JsonNode solved = data(getAs(me, "/api/me/saved")).get("questions").get(0);
        assertThat(solved.get("solved").asBoolean()).isTrue();

        setStatus(answerer, "LOCKED");
        JsonNode unsolved = data(getAs(me, "/api/me/saved")).get("questions").get(0);
        assertThat(unsolved.get("solved").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.13-10 [Biên] Tóm tắt cẩm nang dài hơn 160 ký tự được cắt còn 160 ký tự kèm \"…\"; đúng 160 ký tự thì giữ nguyên")
    void guideSummaryIsCutAt160() throws Exception {
        String t = token();
        long me = member();
        long exact = guide("Đúng " + t, "b".repeat(160));
        long longer = guide("Dài " + t, "c".repeat(161));
        saveGuide(me, exact, minutesAgo(2));
        saveGuide(me, longer, minutesAgo(1));

        JsonNode guides = data(getAs(me, "/api/me/saved").andExpect(status().isOk())).get("guides");

        assertThat(guides.get(0).get("id").asLong()).isEqualTo(longer);
        assertThat(guides.get(0).get("summary").asText()).isEqualTo("c".repeat(159) + "…");
        assertThat(guides.get(1).get("summary").asText()).isEqualTo("b".repeat(160));
        assertThat(guides.get(0).get("topicName").asText()).isEqualTo("Nhà ở");
    }

    @Test
    @DisplayName("TC-1.13-11 [Luồng chính] Chỉ thấy mục do chính mình lưu, không thấy mục của thành viên khác")
    void onlyOwnSavedItems() throws Exception {
        String t = token();
        long me = member();
        long other = member();
        long author = member();
        long mine = publishedPost(author, "Của tôi " + t, "x");
        long theirs = publishedPost(author, "Của bạn " + t, "x");
        savePost(me, mine, minutesAgo(1));
        savePost(other, theirs, minutesAgo(1));
        savePlace(other, place("Quán " + t), minutesAgo(1));

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));

        assertThat(ids(saved.get("posts"))).containsExactly(mine);
        assertThat(saved.get("places")).isEmpty();
    }

    @Test
    @DisplayName("TC-1.13-12 [Luồng chính] Bỏ lưu một mục thì mục đó biến khỏi trang \"Đã lưu\"")
    void unsavedItemLeavesTheList() throws Exception {
        String t = token();
        long me = member();
        long post = publishedPost(member(), "Bài " + t, "x");
        long place = place("Quán " + t);
        postAs(me, null, "/api/blogs/{id}/save", post).andExpect(status().isOk());
        postAs(me, null, "/api/map/places/{id}/save", place).andExpect(status().isOk());

        deleteAs(me, "/api/blogs/{id}/save", post).andExpect(status().isOk());

        JsonNode saved = data(getAs(me, "/api/me/saved").andExpect(status().isOk()));
        assertThat(saved.get("posts")).isEmpty();
        assertThat(ids(saved.get("places"))).containsExactly(place);
        assertThat(saved.get("counts").get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.13-13 [Phân quyền] Khách phải đăng nhập (401), tài khoản nhân viên nhận MSG18, tài khoản bị khóa bị đăng xuất (401)")
    void onlyActiveMembersBrowseSavedItems() throws Exception {
        long moderator = account("MODERATOR", 30);
        long locked = member();
        setStatus(locked, "LOCKED");

        getAs(null, "/api/me/saved").andExpect(status().isUnauthorized());
        getAs(moderator, "/api/me/saved")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        getAs(locked, "/api/me/saved").andExpect(status().isUnauthorized());
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.get("id").asLong()));
        return ids;
    }
}
