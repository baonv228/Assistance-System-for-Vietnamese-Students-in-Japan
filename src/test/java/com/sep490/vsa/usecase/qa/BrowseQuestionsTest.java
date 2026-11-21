package com.sep490.vsa.usecase.qa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.17 Browse Questions ("Duyệt câu hỏi") – SRS Report 3, II.1.14. Later rules from CLAUDE.md ("Hỏi-Đáp"):
 * Solved = the best answer is still PUBLISHED and its writer's account ACTIVE ({@code QaRepository.SOLVED_SQL});
 * Unanswered = no published answer by an active account and not solved (a reply alone does not count, the card
 * badge reads the same rule, not {@code answerCount}); questions of LOCKED or DELETED accounts are hidden
 * ({@code ActiveAuthors}). Every list call is scoped by a unique token in the keyword, because the test database is
 * shared and keeps old rows.
 * <p>
 * Not testable on the server (UI only): the "Thử lại" / "Xóa bộ lọc" buttons, typing-pause search, filters kept in
 * the page address, the MSG17 loading error, the "Hỏi sao cho dễ được trả lời" card (static text).
 */
@DisplayName("UC 1.17 – Browse Questions (Duyệt câu hỏi)")
class BrowseQuestionsTest extends QuestionTestSupport {

    @Test
    @DisplayName("TC-1.17-01 [Luồng chính 1-2, 6] – Khách xem danh sách: câu hỏi mới nhất trước, mỗi thẻ có tiêu đề, trích đoạn, chủ đề, địa điểm, người hỏi với tag, thời gian, lượt thích, số trả lời")
    void guestSeesNewestQuestionsWithTheirCards() throws Exception {
        String t = token();
        long asker = member();
        grantMentorTag(asker);
        long place = place("Ga Shinjuku " + t);
        long older = insertBlog(asker, "QA", "Câu hỏi cũ " + t, "Mô tả cũ", "PUBLISHED", daysAgo(3), topic("Nhà ở"));
        long newer = ask(asker, "Câu hỏi mới " + t + "?", "Mình cần hỏi về ga.", topics("Đi lại"), List.of(place));

        JsonNode page = data(getAs(null, "/api/questions?q={q}", t).andExpect(status().isOk()));

        assertThat(ids(page.get("items"))).containsExactly(newer, older);
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        assertThat(page.get("size").asInt()).isEqualTo(10);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("filtered").asBoolean()).isTrue();
        JsonNode card = page.at("/items/0");
        assertThat(card.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(card.get("title").asText()).isEqualTo("Câu hỏi mới " + t + "?");
        assertThat(card.get("excerpt").asText()).isEqualTo("Mình cần hỏi về ga.");
        assertThat(card.get("topics").findValuesAsText("name")).containsExactly("Đi lại");
        assertThat(card.get("places").findValues("id")).extracting(JsonNode::asLong).containsExactly(place);
        assertThat(card.at("/author/id").asLong()).isEqualTo(asker);
        assertThat(card.at("/author/displayName").asText()).isEqualTo(displayName(asker));
        assertThat(card.at("/author/tags").findValuesAsText("name")).contains("Mentor");
        assertThat(card.get("likeCount").asInt()).isZero();
        assertThat(card.get("answerCount").asInt()).isZero();
        assertThat(card.get("unanswered").asBoolean()).isTrue();
        assertThat(card.get("solved").asBoolean()).isFalse();
        assertThat(card.get("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("TC-1.17-02 [Luồng chính 6] – Trích đoạn dài được cắt ở ranh giới từ (≤ 180 ký tự, kết thúc bằng \"…\")")
    void longDescriptionsAreCutIntoAnExcerpt() throws Exception {
        String t = token();
        long asker = member();
        String description = "Mình muốn hỏi về thủ tục chuyển nhà ở Tokyo. ".repeat(10);
        long id = ask(asker, "Trích đoạn " + t, description);

        String excerpt = card(null, t, id).get("excerpt").asText();

        assertThat(excerpt).endsWith("…").startsWith("Mình muốn hỏi về thủ tục chuyển nhà");
        assertThat(excerpt.length()).isLessThanOrEqualTo(181);
    }

    @Test
    @DisplayName("TC-1.17-03 [Luồng chính 2] – Câu hỏi \"Đã ghim\" nằm trên cùng khi xếp \"Mới nhất\"")
    void pinnedQuestionsStayOnTop() throws Exception {
        String t = token();
        long asker = member();
        long pinned = insertBlog(asker, "QA", "Ghim " + t, "", "PUBLISHED", daysAgo(5), topic("Khác"));
        jdbc.update("UPDATE blogs SET is_pinned = TRUE WHERE id = ?", pinned);
        long recent = ask(asker, "Mới đăng " + t, "");

        JsonNode items = data(getAs(null, "/api/questions?q={q}", t)).get("items");

        assertThat(ids(items)).containsExactly(pinned, recent);
        assertThat(items.at("/0/pinned").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.17-04 [Luồng chính 2 / Luồng thay thế 4] – 10 câu hỏi mỗi trang; trang vượt quá cuối trở thành trang cuối")
    void pagesOfTenAndAPagePastTheEndBecomesTheLast() throws Exception {
        String t = token();
        long asker = member();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            created.add(insertBlog(asker, "QA", "Trang " + i + " " + t, "", "PUBLISHED", daysAgo(20 - i), topic("Khác")));
        }

        JsonNode first = data(getAs(null, "/api/questions?q={q}&page=0", t));
        assertThat(first.get("items")).hasSize(10);
        assertThat(first.get("totalItems").asLong()).isEqualTo(11);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.at("/items/0/id").asLong()).isEqualTo(created.get(10));

        JsonNode second = data(getAs(null, "/api/questions?q={q}&page=1", t));
        assertThat(ids(second.get("items"))).containsExactly(created.get(0));

        JsonNode pastTheEnd = data(getAs(null, "/api/questions?q={q}&page=7", t).andExpect(status().isOk()));
        assertThat(pastTheEnd.get("page").asInt()).isEqualTo(1);
        assertThat(ids(pastTheEnd.get("items"))).containsExactly(created.get(0));
    }

    @Test
    @DisplayName("TC-1.17-05 [Tiền điều kiện] – Chỉ liệt kê câu hỏi đã đăng của tài khoản đang hoạt động: không có câu hỏi ẩn, bị gỡ, bị từ chối, của tài khoản bị khóa/xóa, và không có bài viết Blog")
    void onlyPublishedQuestionsOfActiveAccountsAreListed() throws Exception {
        String t = token();
        long asker = member();
        long locked = member();
        long deleted = member();
        long shown = ask(asker, "Hiện " + t, "");
        insertBlog(asker, "QA", "Ẩn " + t, "", "HIDDEN", daysAgo(1), topic("Khác"));
        insertBlog(asker, "QA", "Gỡ " + t, "", "REMOVED", daysAgo(1), topic("Khác"));
        insertBlog(asker, "QA", "Từ chối " + t, "", "REJECTED", daysAgo(1), topic("Khác"));
        insertBlog(asker, "POST", "Bài Blog " + t, "nội dung", "PUBLISHED", daysAgo(1), topic("Khác"));
        ask(locked, "Người bị khóa " + t, "");
        ask(deleted, "Người đã xóa " + t, "");
        setStatus(locked, "LOCKED");
        setStatus(deleted, "DELETED");

        assertThat(listIds(null, t, null, null)).containsExactly(shown);
        assertThat(listIds(member(), t, null, null)).containsExactly(shown);
        // The asker does not see their own hidden, removed or rejected questions in the list either
        assertThat(listIds(asker, t, null, null)).containsExactly(shown);
    }

    @Test
    @DisplayName("TC-1.17-06 [Luồng thay thế 2b] – Người hỏi thấy câu hỏi của mình đang quét ảnh/video (trạng thái PROCESSING); người khác không thấy")
    void askerAlsoSeesOwnQuestionsBeingScanned() throws Exception {
        String t = token();
        long asker = member();
        long scanning = insertBlog(asker, "QA", "Đang quét " + t, "", "PROCESSING", daysAgo(0), topic("Khác"));

        JsonNode own = card(asker, t, scanning);
        assertThat(own).isNotNull();
        assertThat(own.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(listIds(member(), t, null, null)).isEmpty();
        assertThat(listIds(null, t, null, null)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.17-07 [Luồng chính 3, 5] – Từ khóa tìm trong câu hỏi, mô tả và tên địa điểm liên kết; không phân biệt dấu và chữ hoa")
    void keywordMatchesQuestionDescriptionAndPlaceIgnoringAccentsAndCase() throws Exception {
        String t = token();
        long asker = member();
        long byTitle = ask(asker, "Gia hạn thẻ cư trú " + t, "");
        long byDescription = ask(asker, "Câu hỏi khác", "Mình hỏi về " + t + " ở quận.");
        String placeWord = "Chùa " + t + "x";
        long byPlace = ask(asker, "Đi chùa nào", "", topics("Khác"), List.of(place(placeWord)));
        ask(asker, "Không liên quan", "Không có từ khóa");

        assertThat(listIds(null, t, null, null)).containsExactlyInAnyOrder(byTitle, byDescription, byPlace);
        // Accents and letter case are ignored: "THE CU TRU" finds "thẻ cư trú"
        assertThat(listIds(null, ("THE CU TRU " + t).toUpperCase(), null, null))
                .containsExactly(byTitle);
        assertThat(listIds(null, (t + "x").toUpperCase(), null, null)).containsExactly(byPlace);
    }

    @Test
    @DisplayName("TC-1.17-08 [Luồng chính 3, 5] – Lọc trạng thái: \"Chưa ai trả lời\", \"Chưa giải quyết\", \"Đã giải quyết\", \"Tất cả\"")
    void statusFilters() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long unanswered = ask(asker, "Chưa ai trả lời " + t, "");
        long answered = ask(asker, "Đã có trả lời " + t, "");
        long solved = ask(asker, "Đã giải quyết " + t, "");
        answerQuestion(helper, answered, "Một câu trả lời.");
        long best = answerQuestion(helper, solved, "Câu trả lời hay nhất.");
        chooseBest(asker, solved, best).andExpect(status().isOk());

        assertThat(listIds(null, t, "ALL", null)).containsExactlyInAnyOrder(unanswered, answered, solved);
        assertThat(listIds(null, t, "UNANSWERED", null)).containsExactly(unanswered);
        assertThat(listIds(null, t, "UNSOLVED", null)).containsExactlyInAnyOrder(unanswered, answered);
        assertThat(listIds(null, t, "SOLVED", null)).containsExactly(solved);
        assertThat(listIds(null, t.toUpperCase(), "solved", null)).containsExactly(solved);   // value case ignored
        JsonNode solvedCard = card(null, t, solved);
        assertThat(solvedCard.get("solved").asBoolean()).isTrue();
        assertThat(solvedCard.get("unanswered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.17-09 [Luồng chính 5 – CLAUDE.md] – Đã giải quyết chỉ khi câu trả lời hay nhất còn hiển thị và người viết còn hoạt động; trả lời của tài khoản bị khóa hay chỉ có phản hồi không làm câu hỏi \"đã có trả lời\"")
    void solvedAndUnansweredFollowVisibleAnswersOnly() throws Exception {
        String t = token();
        long asker = member();
        long bestWriter = member();
        long lockedHelper = member();
        long replier = member();
        long solved = ask(asker, "Có câu trả lời hay nhất " + t, "");
        long best = answerQuestion(bestWriter, solved, "Hay nhất.");
        chooseBest(asker, solved, best).andExpect(status().isOk());
        long onlyLocked = ask(asker, "Chỉ người bị khóa trả lời " + t, "");
        long lockedAnswer = answerQuestion(lockedHelper, onlyLocked, "Mình trả lời trước khi bị khóa.");
        reply(replier, onlyLocked, lockedAnswer, "Phản hồi vẫn hiện.");

        setStatus(bestWriter, "LOCKED");
        setStatus(lockedHelper, "LOCKED");

        assertThat(listIds(null, t, "SOLVED", null)).isEmpty();
        assertThat(listIds(null, t, "UNSOLVED", null)).containsExactlyInAnyOrder(solved, onlyLocked);
        assertThat(listIds(null, t, "UNANSWERED", null)).containsExactlyInAnyOrder(solved, onlyLocked);
        JsonNode card = card(null, t, onlyLocked);
        assertThat(card.get("unanswered").asBoolean()).isTrue();
        assertThat(card.get("solved").asBoolean()).isFalse();

        // Unlocked again: the question is solved again (nothing was deleted)
        setStatus(bestWriter, "ACTIVE");
        assertThat(listIds(null, t, "SOLVED", null)).containsExactly(solved);
    }

    @Test
    @DisplayName("TC-1.17-10 [Luồng chính 6] – Số câu trả lời trên thẻ gồm cả phản hồi")
    void answerCountIncludesReplies() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long other = member();
        long id = ask(asker, "Đếm trả lời " + t, "");
        long answer = answerQuestion(helper, id, "Câu trả lời.");
        reply(other, id, answer, "Phản hồi 1.");
        reply(asker, id, answer, "Phản hồi 2.");

        JsonNode card = card(null, t, id);
        assertThat(card.get("answerCount").asInt()).isEqualTo(3);
        assertThat(card.get("unanswered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.17-11 [Luồng chính 3] – Sắp xếp \"Nhiều thích\" và \"Nhiều trả lời\" (ghim không ưu tiên ở hai cách này)")
    void sortByLikesAndByAnswers() throws Exception {
        String t = token();
        long asker = member();
        long a = insertBlog(asker, "QA", "A " + t, "", "PUBLISHED", daysAgo(3), topic("Khác"));
        long b = insertBlog(asker, "QA", "B " + t, "", "PUBLISHED", daysAgo(2), topic("Khác"));
        long c = insertBlog(asker, "QA", "C " + t, "", "PUBLISHED", daysAgo(1), topic("Khác"));
        jdbc.update("UPDATE blogs SET is_pinned = TRUE WHERE id = ?", c);
        long fan1 = member();
        long fan2 = member();
        postAs(fan1, null, "/api/questions/{id}/like", a).andExpect(status().isOk());
        postAs(fan2, null, "/api/questions/{id}/like", a).andExpect(status().isOk());
        postAs(fan1, null, "/api/questions/{id}/like", b).andExpect(status().isOk());
        answerQuestion(fan1, b, "Trả lời B 1.");
        answerQuestion(fan2, b, "Trả lời B 2.");
        answerQuestion(fan1, c, "Trả lời C.");

        assertThat(listIds(null, t, null, "LIKES")).containsExactly(a, b, c);
        assertThat(listIds(null, t, null, "ANSWERS")).containsExactly(b, c, a);
        assertThat(listIds(null, t, null, "NEWEST")).containsExactly(c, b, a);
        assertThat(listIds(null, t, null, "likes")).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("TC-1.17-12 [Luồng chính 3-4] – Lọc theo chủ đề (một hoặc nhiều chủ đề); chủ đề không tồn tại hoặc đã tắt bị bỏ qua")
    void topicFilterIgnoresUnknownAndInactiveTopics() throws Exception {
        String t = token();
        long asker = member();
        long off = inactiveTopic(t);
        long housing = ask(asker, "Nhà " + t, "", topics("Nhà ở"), List.of());
        long health = ask(asker, "Bệnh viện " + t, "", topics("Y tế"), List.of());
        long both = insertBlog(asker, "QA", "Cả hai " + t, "", "PUBLISHED", daysAgo(1), topic("Nhà ở"), off);
        long other = ask(asker, "Đi lại " + t, "", topics("Đi lại"), List.of());

        JsonNode one = data(getAs(null, "/api/questions?q={q}&topicIds={ids}", t, topic("Nhà ở")));
        assertThat(ids(one.get("items"))).containsExactlyInAnyOrder(housing, both);
        assertThat(one.get("filtered").asBoolean()).isTrue();
        JsonNode two = data(getAs(null, "/api/questions?q={q}&topicIds={a},{b}", t, topic("Nhà ở"), topic("Y tế")));
        assertThat(ids(two.get("items"))).containsExactlyInAnyOrder(housing, health, both);
        // Unknown (999999999) and switched-off topics are ignored: the list is not narrowed by them
        JsonNode ignored = data(getAs(null, "/api/questions?q={q}&topicIds={a},{b}", t, 999_999_999L, off)
                .andExpect(status().isOk()));
        assertThat(ids(ignored.get("items"))).containsExactlyInAnyOrder(housing, health, both, other);
    }

    @Test
    @DisplayName("TC-1.17-13 [Biên / Luồng thay thế 4a] – Từ khóa 100 ký tự được nhận; 101 ký tự báo \"Từ khóa tìm kiếm tối đa 100 ký tự.\"")
    void keywordLengthBoundary() throws Exception {
        String t = token();
        String keyword100 = t + chars(100 - t.length());
        assertThat(keyword100).hasSize(100);

        getAs(null, "/api/questions?q={q}", keyword100).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
        getAs(null, "/api/questions?q={q}", keyword100 + "b").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(KEYWORD_TOO_LONG));
        // Spaces around the keyword are not counted
        getAs(null, "/api/questions?q={q}", "  " + keyword100 + "  ").andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-1.17-14 [Luồng thay thế 4a] – Trạng thái hoặc cách sắp xếp không hợp lệ: \"Giá trị lọc không hợp lệ: <giá trị>\"")
    void unknownStatusOrSortIsRefused() throws Exception {
        getAs(null, "/api/questions?status=CLOSED").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giá trị lọc không hợp lệ: CLOSED"));
        getAs(null, "/api/questions?sort=OLDEST").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giá trị lọc không hợp lệ: OLDEST"));
    }

    @Test
    @DisplayName("TC-1.17-15 [Luồng thay thế 6a] – Không có câu hỏi khớp: danh sách rỗng, tổng 0, filtered = true; chỉ sắp xếp thì filtered = false")
    void noMatchingQuestions() throws Exception {
        String t = token();

        JsonNode none = data(getAs(null, "/api/questions?q={q}", t).andExpect(status().isOk()));
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("totalItems").asLong()).isZero();
        assertThat(none.get("filtered").asBoolean()).isTrue();
        assertThat(data(getAs(null, "/api/questions?status=UNANSWERED&q={q}", t)).get("filtered").asBoolean()).isTrue();
        assertThat(data(getAs(null, "/api/questions?sort=LIKES")).get("filtered").asBoolean()).isFalse();
        assertThat(data(getAs(null, "/api/questions")).get("filtered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.17-16 [Luồng chính 2] – Thẻ bên: chủ đề đang dùng, \"Cộng đồng hỏi đáp\" (tổng, chưa ai trả lời, đã giải quyết), \"Chủ đề hay được hỏi\", \"Senpai giải đáp nhiều\" 30 ngày")
    void sideCards() throws Exception {
        String t = token();
        long asker = member();
        long senpai = member();
        // One best answer more than today's top answerer (at least 6) in the last 30 days puts this member first
        // among the top answerers, whatever the database already holds; an answer to one's own question never
        // counts there. Both accounts end locked, so later runs never meet this one.
        JsonNode before = data(getAs(null, "/api/questions/overview").andExpect(status().isOk())).get("topAnswerers");
        int best = Math.max(6, before.isEmpty() ? 0 : before.get(0).get("bestCount").asInt() + 1);
        try {
            sideCardsWith(t, asker, senpai, best);
        } finally {
            setStatus(senpai, "LOCKED");
            setStatus(asker, "LOCKED");
        }
    }

    private void sideCardsWith(String t, long asker, long senpai, int best) throws Exception {
        for (int i = 0; i < best; i++) {
            long q = ask(asker, "Hỏi senpai " + i + " " + t, "");
            long a = answerQuestion(senpai, q, "Trả lời " + i);
            chooseBest(asker, q, a).andExpect(status().isOk());
        }
        long own = ask(senpai, "Tự hỏi " + t, "");
        answerQuestion(senpai, own, "Tự trả lời.");

        JsonNode overview = data(getAs(null, "/api/questions/overview").andExpect(status().isOk()));

        assertThat(overview.get("topics").findValuesAsText("name")).contains("Kinh nghiệm", "Khác");
        long total = overview.at("/stats/total").asLong();
        assertThat(total).isPositive();
        assertThat(overview.at("/stats/unanswered").asLong()).isBetween(0L, total);
        assertThat(overview.at("/stats/solved").asLong()).isBetween((long) best, total);
        assertThat(overview.get("popularTopics").size()).isBetween(1, 5);
        List<Long> counts = overview.get("popularTopics").findValues("postCount").stream().map(JsonNode::asLong).toList();
        assertThat(counts).isSortedAccordingTo((x, y) -> Long.compare(y, x));
        assertThat(overview.get("topAnswerersDays").asInt()).isEqualTo(30);
        assertThat(overview.get("topAnswerers").size()).isLessThanOrEqualTo(5);
        // Ranked by best answers, then answers: the member with the most best answers comes first
        JsonNode mine = overview.get("topAnswerers").get(0);
        assertThat(mine.at("/author/id").asLong()).as("the member with the most best answers leads \"Senpai giải đáp nhiều\"")
                .isEqualTo(senpai);
        assertThat(mine.get("bestCount").asLong()).isEqualTo(best);
        assertThat(mine.get("answerCount").asLong()).as("the answer to the member's own question does not count")
                .isEqualTo(best);

        // A locked account leaves the card at once
        setStatus(senpai, "LOCKED");
        JsonNode after = data(getAs(null, "/api/questions/overview").andExpect(status().isOk())).get("topAnswerers");
        assertThat(after.findValues("author").stream().map(a -> a.get("id").asLong()).toList()).doesNotContain(senpai);
    }
}
