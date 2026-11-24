package com.sep490.vsa.usecase.qa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.18 View Question Details ("Xem chi tiết câu hỏi") – SRS Report 3, II.1.15, with the report rules of View
 * Post Details 7a (MSG38/39/40). CLAUDE.md ("Hỏi-Đáp", "Post Detail"): {@code GET /api/questions/{id}} is public,
 * {@code PUT /{id}/best-answer} is for the asker only (+5 points moved when changed), a best answer only counts while
 * it is PUBLISHED and its writer ACTIVE, content of LOCKED/DELETED accounts is hidden (404), a post id answers 404.
 * <p>
 * Not testable on the server (UI only): the badges' wording, "Chỉ đường" / "Hiện bản đồ" (map tiles), 2b MSG17,
 * 4b the text "Chưa có câu trả lời…" (the server gives an empty list, TC-1.18-03), the toast of 5a (the page adds
 * "Người trả lời được +5 điểm."; the server answers "Đã chọn câu trả lời hay nhất.").
 */
@DisplayName("UC 1.18 – View Question Details (Xem chi tiết câu hỏi)")
class ViewQuestionDetailsTest extends QuestionTestSupport {

    static final String BEST_CHOSEN = "Đã chọn câu trả lời hay nhất.";

    @Test
    @DisplayName("TC-1.18-01 [Luồng chính 1-4] – Khách xem câu hỏi: tiêu đề, mô tả, người hỏi với tag và hạng, chủ đề, địa điểm liên quan, lượt thích, lý do báo cáo")
    void guestReadsTheQuestion() throws Exception {
        String t = token();
        long asker = member();
        grantMentorTag(asker);
        long place = place("Ga Ikebukuro " + t);
        long id = ask(asker, "Đi Ikebukuro " + t + " thế nào?", "Mình ở Arakawa.", topics("Đi lại"), List.of(place));

        JsonNode q = data(getAs(null, "/api/questions/{id}", id).andExpect(status().isOk()));

        assertThat(q.get("id").asLong()).isEqualTo(id);
        assertThat(q.get("type").asText()).isEqualTo("QA");
        assertThat(q.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(q.get("title").asText()).isEqualTo("Đi Ikebukuro " + t + " thế nào?");
        assertThat(q.get("content").asText()).isEqualTo("Mình ở Arakawa.");
        assertThat(q.get("edited").asBoolean()).isFalse();
        assertThat(q.at("/author/id").asLong()).isEqualTo(asker);
        assertThat(q.at("/author/displayName").asText()).isEqualTo(displayName(asker));
        assertThat(q.at("/author/tags").findValuesAsText("name")).contains("Mentor");
        assertThat(q.at("/author/rankName").asText()).isEqualTo("Người mới");
        assertThat(q.get("topics").findValuesAsText("name")).containsExactly("Đi lại");
        assertThat(q.at("/places/0/id").asLong()).isEqualTo(place);
        assertThat(q.at("/places/0/name").asText()).isEqualTo("Ga Ikebukuro " + t);
        assertThat(q.at("/places/0/lat").isNumber()).isTrue();
        assertThat(q.get("likeCount").asInt()).isZero();
        assertThat(q.get("bestCommentId").isNull()).isTrue();
        assertThat(q.get("reportReasons").findValuesAsText("label"))
                .containsExactly("Lừa đảo", "Sai sự thật", "Xúc phạm", "Spam");
        // 4a: a Guest may only read; the page turns the actions into sign-in links
        assertThat(q.at("/viewer/signedIn").asBoolean()).isFalse();
        assertThat(q.at("/viewer/canInteract").asBoolean()).isFalse();
        assertThat(q.at("/viewer/author").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.18-02 [Luồng chính 4] – Thứ tự câu trả lời: câu trả lời hay nhất trước, rồi của \"Senpai tin cậy\", rồi các câu khác cũ trước; phản hồi nằm dưới câu trả lời, cũ trước")
    void answersAreOrderedBestThenSenpaiThenOldest() throws Exception {
        String t = token();
        long asker = member();
        long first = member();
        long senpai = member();
        long third = member();
        long bestWriter = member();
        long replier1 = member();
        long replier2 = member();
        jdbc.update("UPDATE users SET points = ? WHERE id = ?", senpaiPoints(), senpai);
        long id = ask(asker, "Thứ tự trả lời " + t, "");
        long a1 = answerQuestion(first, id, "Trả lời đầu tiên.");
        long aSenpai = answerQuestion(senpai, id, "Trả lời của senpai.");
        long a3 = answerQuestion(third, id, "Trả lời thứ ba.");
        long aBest = answerQuestion(bestWriter, id, "Trả lời hay nhất.");
        long r1 = reply(replier1, id, a1, "Phản hồi 1.");
        long r2 = reply(replier2, id, a1, "Phản hồi 2.");
        chooseBest(asker, id, aBest).andExpect(status().isOk());

        JsonNode q = data(getAs(null, "/api/questions/{id}", id));
        JsonNode answers = q.get("comments");

        assertThat(ids(answers)).containsExactly(aBest, aSenpai, a1, a3);
        assertThat(answers.at("/0/best").asBoolean()).isTrue();
        assertThat(answers.at("/1/best").asBoolean()).isFalse();
        assertThat(answers.at("/1/author/rankName").asText()).isEqualTo("Senpai tin cậy");
        assertThat(ids(answers.at("/2/replies"))).containsExactly(r1, r2);
        assertThat(answers.at("/2/replies/0/parentId").asLong()).isEqualTo(a1);
        assertThat(q.get("commentCount").asInt()).isEqualTo(6);
        assertThat(q.get("bestCommentId").asLong()).isEqualTo(aBest);
    }

    @Test
    @DisplayName("TC-1.18-03 [Luồng thay thế 4b] – Chưa có câu trả lời: danh sách câu trả lời rỗng, số câu trả lời 0")
    void questionWithoutAnswers() throws Exception {
        String t = token();
        long id = ask(member(), "Chưa ai trả lời " + t, "");

        JsonNode q = data(getAs(member(), "/api/questions/{id}", id).andExpect(status().isOk()));

        assertThat(q.get("comments")).isEmpty();
        assertThat(q.get("commentCount").asInt()).isZero();
        assertThat(q.get("bestCommentId").isNull()).isTrue();
    }

    @Test
    @DisplayName("TC-1.18-04 [Luồng thay thế 2a] – Không tìm thấy (MSG19): id không có, câu hỏi đã xóa, bị gỡ, bị ẩn với người khác, người hỏi bị khóa/xóa, id của bài viết Blog")
    void notFoundCases() throws Exception {
        String t = token();
        long asker = member();
        long lockedAsker = member();
        long deletedAsker = member();
        long viewer = member();
        long deleted = ask(asker, "Đã xóa " + t, "");
        deleteAs(asker, "/api/questions/{id}", deleted).andExpect(status().isOk());
        long removed = insertBlog(asker, "QA", "Bị gỡ " + t, "", "REMOVED", daysAgo(1), topic("Khác"));
        long hidden = insertBlog(asker, "QA", "Bị ẩn " + t, "", "HIDDEN", daysAgo(1), topic("Khác"));
        long ofLocked = ask(lockedAsker, "Người hỏi bị khóa " + t, "");
        long ofDeleted = ask(deletedAsker, "Người hỏi đã xóa tài khoản " + t, "");
        setStatus(lockedAsker, "LOCKED");
        setStatus(deletedAsker, "DELETED");
        long post = publishedPost(asker, "Bài Blog " + t, "nội dung");

        for (long id : new long[]{999_999_999L, deleted, removed, hidden, ofLocked, ofDeleted, post}) {
            getAs(viewer, "/api/questions/{id}", id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG19));
            getAs(null, "/api/questions/{id}", id).andExpect(status().isNotFound());
        }
        // A removed question is not shown even to its asker
        getAs(asker, "/api/questions/{id}", removed).andExpect(status().isNotFound());
        // ...and the question opens only on its own page: the Blog page answers 404 for its id
        long question = ask(asker, "Chỉ ở Hỏi-Đáp " + t, "");
        getAs(viewer, "/api/blogs/{id}", question).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TC-1.18-05 [Tiền điều kiện / Luồng thay thế 3a] – Người hỏi xem được câu hỏi của mình đang quét, bị ẩn hoặc bị từ chối (kèm trạng thái); không tương tác được khi chưa đăng")
    void askerSeesOwnUnpublishedQuestion() throws Exception {
        String t = token();
        long asker = member();
        for (String state : new String[]{"PROCESSING", "HIDDEN", "REJECTED"}) {
            long id = insertBlog(asker, "QA", state + " " + t, "", state, daysAgo(1), topic("Khác"));

            JsonNode q = data(getAs(asker, "/api/questions/{id}", id).andExpect(status().isOk()));
            assertThat(q.get("status").asText()).isEqualTo(state);
            assertThat(q.at("/viewer/author").asBoolean()).isTrue();
            assertThat(q.at("/viewer/canInteract").asBoolean()).as("comments closed while %s", state).isFalse();
            getAs(member(), "/api/questions/{id}", id).andExpect(status().isNotFound());
        }
        long published = ask(asker, "Đã đăng " + t, "");
        JsonNode own = data(getAs(asker, "/api/questions/{id}", published));
        assertThat(own.at("/viewer/author").asBoolean()).isTrue();
        assertThat(own.at("/viewer/canInteract").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.18-06 [Luồng chính 4 / Luồng thay thế 4a] – Quyền của người xem: thành viên tương tác được (đã thích, đã lưu); nhân viên chỉ xem")
    void viewerFlags() throws Exception {
        String t = token();
        long id = ask(member(), "Quyền người xem " + t, "");
        long member = member();
        long moderator = account("MODERATOR", 30);

        JsonNode before = data(getAs(member, "/api/questions/{id}", id)).get("viewer");
        assertThat(before.get("signedIn").asBoolean()).isTrue();
        assertThat(before.get("canInteract").asBoolean()).isTrue();
        assertThat(before.get("liked").asBoolean()).isFalse();
        assertThat(before.get("saved").asBoolean()).isFalse();

        postAs(member, null, "/api/questions/{id}/like", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.count").value(1));
        postAs(member, null, "/api/questions/{id}/save", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu câu hỏi."));
        JsonNode after = data(getAs(member, "/api/questions/{id}", id));
        assertThat(after.at("/viewer/liked").asBoolean()).isTrue();
        assertThat(after.at("/viewer/saved").asBoolean()).isTrue();
        assertThat(after.get("likeCount").asInt()).isEqualTo(1);

        JsonNode staff = data(getAs(moderator, "/api/questions/{id}", id).andExpect(status().isOk())).get("viewer");
        assertThat(staff.get("signedIn").asBoolean()).isTrue();
        assertThat(staff.get("canInteract").asBoolean()).isFalse();
        postAs(moderator, null, "/api/questions/{id}/like", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        postAs(moderator, answer("Nhân viên trả lời", null), "/api/questions/{id}/answers", id)
                .andExpect(status().isForbidden());
        postAs(null, null, "/api/questions/{id}/like", id).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-1.18-07 [Luồng thay thế 5a] – Người hỏi chọn câu trả lời hay nhất: đánh dấu, +5 điểm cho người viết, thông báo cho họ, câu hỏi thành \"Đã giải quyết\"")
    void askerChoosesTheBestAnswer() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        String title = "Chọn hay nhất " + t;
        long id = ask(asker, title, "");
        long answer = answerQuestion(helper, id, "Câu trả lời hữu ích.");

        chooseBest(asker, id, answer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(BEST_CHOSEN))
                .andExpect(jsonPath("$.data.bestCommentId").value(answer))
                .andExpect(jsonPath("$.data.comments[0].id").value(answer))
                .andExpect(jsonPath("$.data.comments[0].best").value(true));

        assertThat(points(helper)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'BEST_ANSWER' "
                + "AND ref_type = 'COMMENT' AND ref_id = ? AND delta = 5", Integer.class, helper, answer)).isEqualTo(1);
        assertThat(notificationsOf(helper)).contains(
                "Câu trả lời của bạn cho “" + title + "” được chọn là câu trả lời hay nhất. +5 điểm.");
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notifications WHERE user_id = ? AND type = 'BEST_ANSWER' "
                + "ORDER BY id DESC LIMIT 1", String.class, helper)).isEqualTo("QUESTION");
        assertThat(listIds(null, t, "SOLVED", null)).containsExactly(id);
        assertThat(points(asker)).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-1.18-08 [Luồng thay thế 5a] – Đổi câu trả lời hay nhất: +5 chuyển từ người cũ sang người mới; chọn lại cùng câu không cộng thêm")
    void changingTheBestAnswerMovesThePoints() throws Exception {
        String t = token();
        long asker = member();
        long helper1 = member();
        long helper2 = member();
        long id = ask(asker, "Đổi hay nhất " + t, "");
        long a1 = answerQuestion(helper1, id, "Câu trả lời một.");
        long a2 = answerQuestion(helper2, id, "Câu trả lời hai.");

        chooseBest(asker, id, a1).andExpect(status().isOk());
        chooseBest(asker, id, a2).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bestCommentId").value(a2));

        assertThat(points(helper1)).isZero();
        assertThat(points(helper2)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? "
                + "AND reason = 'BEST_ANSWER_CHANGED' AND delta = -5", Integer.class, helper1)).isEqualTo(1);

        chooseBest(asker, id, a2).andExpect(status().isOk());
        assertThat(points(helper2)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'BEST_ANSWER'",
                Integer.class, helper2)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.18-09 [Luồng thay thế 5b] – Chọn câu trả lời không hợp lệ: không tìm thấy, là phản hồi, của chính mình, chưa chọn; người khác nhận MSG18")
    void invalidBestAnswerChoices() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long other = member();
        long id = ask(asker, "Chọn sai " + t, "");
        long otherQuestion = ask(other, "Câu hỏi khác " + t, "");
        long answer = answerQuestion(helper, id, "Câu trả lời trực tiếp.");
        long replyId = reply(other, id, answer, "Một phản hồi.");
        long own = answerQuestion(asker, id, "Tự trả lời câu hỏi của mình.");
        long elsewhere = answerQuestion(helper, otherQuestion, "Trả lời câu hỏi khác.");
        long hiddenAnswer = answerQuestion(other, id, "Câu trả lời sẽ bị ẩn.");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hiddenAnswer);

        chooseBest(asker, id, 999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy câu trả lời này."));
        chooseBest(asker, id, elsewhere).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy câu trả lời này."));
        chooseBest(asker, id, hiddenAnswer).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy câu trả lời này."));
        chooseBest(asker, id, replyId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Chỉ chọn được câu trả lời trực tiếp cho câu hỏi, không chọn phần trả lời bình luận."));
        chooseBest(asker, id, own).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể chọn câu trả lời của chính mình."));
        chooseBest(asker, id, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy chọn câu trả lời."));
        chooseBest(other, id, answer).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        chooseBest(helper, id, answer).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        chooseBest(account("MODERATOR", 30), id, answer).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        chooseBest(null, id, answer).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT best_comment_id FROM blogs WHERE id = ?", Long.class, id)).isNull();
        assertThat(points(helper)).isZero();
    }

    @Test
    @DisplayName("TC-1.18-10 [Luồng thay thế 5c] – Người hỏi không thích/báo cáo câu hỏi của mình; người khác thích, bỏ thích, báo cáo (MSG38), báo cáo lại (MSG39), thiếu lý do (MSG40)")
    void likeAndReportRules() throws Exception {
        String t = token();
        long asker = member();
        long reader = member();
        long id = ask(asker, "Thích và báo cáo " + t, "");

        postAs(asker, null, "/api/questions/{id}/like", id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể thích bài viết của chính mình."));
        reportQuestion(asker, id, "Spam").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể báo cáo nội dung của chính mình."));

        postAs(reader, null, "/api/questions/{id}/like", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        deleteAs(reader, "/api/questions/{id}/like", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.count").value(0));

        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("reasonId", null);
        noReason.put("note", "Không chọn lý do");
        postAs(reader, noReason, "/api/questions/{id}/report", id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG40));
        reportQuestion(reader, id, "Sai sự thật").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
        reportQuestion(reader, id, "Spam").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MSG39));

        assertThat(jdbc.queryForObject("SELECT report_count FROM report_cases WHERE target_type = 'BLOG' AND target_id = ? "
                + "AND status = 'OPEN'", Integer.class, id)).isEqualTo(1);
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("TC-1.18-11 [Luồng thay thế 7a của View Post Details] – Quá 5 báo cáo thì câu hỏi tự ẩn: người khác nhận MSG19, người hỏi vẫn thấy với trạng thái HIDDEN")
    void moreThanFiveReportsHideTheQuestion() throws Exception {
        String t = token();
        long asker = member();
        long id = ask(asker, "Bị báo cáo nhiều " + t, "");
        List<Long> reporters = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reporters.add(member());
        }

        for (int i = 0; i < 5; i++) {
            reportQuestion(reporters.get(i), id, "Spam").andExpect(status().isOk());
        }
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        reportQuestion(reporters.get(5), id, "Spam").andExpect(status().isOk());

        assertThat(blogStatus(id)).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("SELECT priority FROM report_cases WHERE target_type = 'BLOG' AND target_id = ? "
                + "AND status = 'OPEN'", String.class, id)).isEqualTo("URGENT");
        getAs(member(), "/api/questions/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(asker, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));
    }

    @Test
    @DisplayName("TC-1.18-12 [CLAUDE.md] – Câu trả lời hay nhất bị ẩn hoặc người viết bị khóa: không còn là câu trả lời hay nhất, câu hỏi \"Câu hỏi chờ trả lời\"")
    void aBestAnswerThatIsNoLongerVisibleDoesNotCount() throws Exception {
        String t = token();
        long asker = member();
        long hiddenWriter = member();
        long lockedWriter = member();
        long q1 = ask(asker, "Hay nhất bị ẩn " + t, "");
        long q2 = ask(asker, "Hay nhất bị khóa " + t, "");
        long hidden = answerQuestion(hiddenWriter, q1, "Sẽ bị ẩn do báo cáo.");
        long locked = answerQuestion(lockedWriter, q2, "Người viết sẽ bị khóa.");
        chooseBest(asker, q1, hidden).andExpect(status().isOk());
        chooseBest(asker, q2, locked).andExpect(status().isOk());

        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        setStatus(lockedWriter, "LOCKED");

        for (long id : new long[]{q1, q2}) {
            JsonNode q = data(getAs(null, "/api/questions/{id}", id).andExpect(status().isOk()));
            assertThat(q.get("bestCommentId").isNull()).as("question %s", id).isTrue();
            assertThat(q.get("comments")).isEmpty();
        }
        assertThat(listIds(null, t, "SOLVED", null)).isEmpty();
        assertThat(listIds(null, t, "UNANSWERED", null)).containsExactlyInAnyOrder(q1, q2);
    }

    @Test
    @DisplayName("TC-1.18-13 [Luồng chính 1] – Câu hỏi mở từ \"Đã lưu\" của người đã lưu")
    void questionOpensFromSaved() throws Exception {
        String t = token();
        long asker = member();
        long reader = member();
        long id = ask(asker, "Đã lưu để xem lại " + t, "");
        postAs(reader, null, "/api/questions/{id}/save", id).andExpect(status().isOk());

        assertThat(data(getAs(reader, "/api/me/saved").andExpect(status().isOk())).toString()).contains(String.valueOf(id));
        getAs(reader, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewer.saved").value(true));
    }
}
