package com.sep490.vsa.usecase.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.11 Submit Content Report (Gửi báo cáo nội dung). The SRS (Report 3) has no section of its own: the rules come
 * from the use case list (UC-16 "Report Post / Comment"), View Post Details 7a / 7b, View Question Details 5c, the
 * messages MSG38 "Cảm ơn bạn. Báo cáo đã được gửi.", MSG39 "Bạn đã báo cáo nội dung này rồi.", MSG40 "Hãy chọn lý do
 * báo cáo.", the data rule "Valid Report Target", and CLAUDE.md ("Key business rules": 1–5 reports → "Cần xử lý";
 * more than 5 → auto-hidden and "Khẩn"; more than 5 false reports → reporting locked 7 days;
 * {@code feature/report/ContentReportService}).
 * <p>
 * API: {@code POST /api/blogs/{id}/report}, {@code POST /api/questions/{id}/report} and
 * {@code POST /api/comments/{id}/report} (comments and answers) with {@code {reasonId, note}}. The report dialog,
 * its "Hủy" button and the toast are page work and not tested here.
 */
@DisplayName("UC 1.11 – Submit Content Report (Gửi báo cáo nội dung)")
class SubmitContentReportTest extends UseCaseTestBase {

    private static final String LOCK_PREFIX = "Bạn bị khóa báo cáo đến ";
    private static final String LOCK_SUFFIX = " vì có quá nhiều báo cáo sai.";

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.11-01 [Luồng chính] – Thành viên báo cáo bài viết với lý do và ghi chú → MSG38; mở hồ sơ \"Cần xử lý\" (NORMAL) cho Moderator, bài vẫn hiển thị")
    void memberReportsAPost() throws Exception {
        long author = member();
        long reporter = member();
        long post = publishedPost(author, "Cho thuê nhà giá rẻ " + token(), "Chuyển khoản cọc trước.");

        postAs(reporter, Map.of("reasonId", reason("Lừa đảo"), "note", "  Đòi chuyển cọc trước khi xem nhà  "),
                "/api/blogs/{id}/report", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(MSG38));

        Map<String, Object> c = openCase("BLOG", post);
        assertThat(c.get("handler_role")).isEqualTo("MODERATOR");
        assertThat(c.get("priority")).isEqualTo("NORMAL");
        assertThat(((Number) c.get("report_count")).intValue()).isEqualTo(1);
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM reports WHERE case_id = ? AND reporter_id = ?",
                c.get("id"), reporter);
        assertThat(((Number) r.get("reason_id")).longValue()).isEqualTo(reason("Lừa đảo"));
        assertThat(r.get("note")).isEqualTo("Đòi chuyển cọc trước khi xem nhà");
        assertThat(blogStatus(post)).isEqualTo("PUBLISHED");
        getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-1.11-02 [Luồng chính] – Báo cáo câu hỏi trên trang Hỏi-Đáp → MSG38 (hồ sơ loại BLOG); gửi nhầm địa chỉ bài viết cho câu hỏi → 404 MSG19")
    void memberReportsAQuestion() throws Exception {
        long asker = member();
        long reporter = member();
        long question = publishedQuestion(asker, "Có ai cần mua bằng lái không " + token(), "Giá rẻ.");

        postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/questions/{id}/report", question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
        assertThat(((Number) openCase("BLOG", question).get("report_count")).intValue()).isEqualTo(1);

        // The kind is part of the address: a question is not reported through the Blog address, and the reverse
        long post = publishedPost(asker, "Bài viết " + token(), "x");
        postAs(member(), Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", question)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        postAs(member(), Map.of("reasonId", reason("Spam")), "/api/questions/{id}/report", post)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
    }

    @Test
    @DisplayName("TC-1.11-03 [Luồng chính] – Báo cáo một bình luận → MSG38, trả lại bài để trang vẽ lại; hồ sơ loại COMMENT")
    void memberReportsAComment() throws Exception {
        long author = member();
        long writer = member();
        long reporter = member();
        long post = publishedPost(author, "Bài có bình luận xấu " + token(), "x");
        postAs(writer, Map.of("content", "Bình luận có lời lẽ khó nghe"), "/api/blogs/{id}/comments", post)
                .andExpect(status().isOk());
        long comment = lastCommentId(post, writer);

        JsonNode answer = body(postAs(reporter, Map.of("reasonId", reason("Xúc phạm")), "/api/comments/{id}/report", comment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38)));

        assertThat(answer.get("data").get("id").asLong()).isEqualTo(post);
        assertThat(((Number) openCase("COMMENT", comment).get("report_count")).intValue()).isEqualTo(1);
        // The same member may still report the post itself: one report per item
        postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-1.11-04 [Luồng chính] – Báo cáo một câu trả lời của câu hỏi → MSG38, hồ sơ loại COMMENT, trả lại câu hỏi (type QA)")
    void memberReportsAnAnswer() throws Exception {
        long asker = member();
        long answerer = member();
        long reporter = member();
        long question = publishedQuestion(asker, "Làm thẻ ngân hàng ở đâu " + token(), "x");
        postAs(answerer, Map.of("content", "Bạn ra Yucho gần nhà là được."), "/api/questions/{id}/answers", question)
                .andExpect(status().isOk());
        long answer = lastCommentId(question, answerer);

        postAs(reporter, Map.of("reasonId", reason("Sai sự thật"), "note", "Yucho cần thẻ cư trú"),
                "/api/comments/{id}/report", answer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38))
                .andExpect(jsonPath("$.data.type").value("QA"));
        assertThat(openCase("COMMENT", answer).get("handler_role")).isEqualTo("MODERATOR");
    }

    // ================================================================ invalid report information

    @Test
    @DisplayName("TC-1.11-05 [Ngoại lệ MSG40] – Không chọn lý do, hoặc lý do không thuộc danh sách báo cáo → 400 \"Hãy chọn lý do báo cáo.\"; không lưu gì")
    void reasonIsRequiredAndMustBeOffered() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "x");
        long reporter = member();

        postAs(reporter, Map.of("note", "Không chọn lý do"), "/api/blogs/{id}/report", post)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG40))
                .andExpect(jsonPath("$.data.reasonId").value(MSG40));
        long pinReason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'PIN_REJECT'", Long.class);
        long phraseReason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'ACADEMIC_REPORT'", Long.class);
        for (long wrong : new long[]{pinReason, phraseReason, 987654321L}) {
            postAs(reporter, Map.of("reasonId", wrong), "/api/blogs/{id}/report", post)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(MSG40));
        }
        assertThat(caseCount("BLOG", post)).isZero();
    }

    @Test
    @DisplayName("TC-1.11-06 [Biên] – Ghi chú đúng 500 ký tự được nhận; 501 ký tự bị từ chối \"Ghi chú tối đa 500 ký tự.\"; ghi chú trống lưu là không có")
    void noteOfFiveHundredCharactersPassesAndFiveHundredOneIsRefused() throws Exception {
        long author = member();
        long post = publishedPost(author, "Bài " + token(), "x");
        long other = publishedPost(author, "Bài khác " + token(), "x");
        long longNote = member();
        long tooLong = member();
        long blank = member();

        postAs(tooLong, Map.of("reasonId", reason("Spam"), "note", chars(501)), "/api/blogs/{id}/report", post)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ghi chú tối đa 500 ký tự."));
        assertThat(caseCount("BLOG", post)).isZero();

        postAs(longNote, Map.of("reasonId", reason("Spam"), "note", chars(500)), "/api/blogs/{id}/report", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(note) FROM reports WHERE reporter_id = ?", Integer.class, longNote))
                .isEqualTo(500);

        postAs(blank, Map.of("reasonId", reason("Spam"), "note", "   "), "/api/blogs/{id}/report", other)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT note FROM reports WHERE reporter_id = ?", String.class, blank)).isNull();
    }

    @Test
    @DisplayName("TC-1.11-07 [Ngoại lệ MSG39] – Báo cáo lần hai cùng một nội dung → 409 \"Bạn đã báo cáo nội dung này rồi.\", kể cả khi hồ sơ đã được Moderator xử lý")
    void sameMemberReportsAnItemOnlyOnce() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "x");
        long reporter = member();
        postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isOk());

        postAs(reporter, Map.of("reasonId", reason("Lừa đảo")), "/api/blogs/{id}/report", post)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MSG39));
        assertThat(((Number) openCase("BLOG", post).get("report_count")).intValue()).isEqualTo(1);

        // The Moderator closes the case as a false report; the same member still cannot report it again
        long caseId = ((Number) openCase("BLOG", post).get("id")).longValue();
        postAs(account("MODERATOR", 30), null, "/api/moderator/reports/{id}/dismiss", caseId).andExpect(status().isOk());
        postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MSG39));
    }

    @Test
    @DisplayName("TC-1.11-08 [Luồng thay thế 7b] – Không báo cáo được nội dung của chính mình (bài viết, câu hỏi, bình luận) → 400")
    void ownContentCannotBeReported() throws Exception {
        long me = member();
        long post = publishedPost(me, "Bài của tôi " + token(), "x");
        long question = publishedQuestion(me, "Câu hỏi của tôi " + token(), "x");
        postAs(me, Map.of("content", "Bình luận của tôi"), "/api/blogs/{id}/comments", post).andExpect(status().isOk());
        long comment = lastCommentId(post, me);
        String own = "Bạn không thể báo cáo nội dung của chính mình.";

        postAs(me, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(own));
        postAs(me, Map.of("reasonId", reason("Spam")), "/api/questions/{id}/report", question)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(own));
        postAs(me, Map.of("reasonId", reason("Spam")), "/api/comments/{id}/report", comment)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(own));
        assertThat(caseCount("BLOG", post) + caseCount("BLOG", question) + caseCount("COMMENT", comment)).isZero();
    }

    // ================================================================ thresholds

    @Test
    @DisplayName("TC-1.11-09 [Quy tắc báo cáo / Biên] – 5 báo cáo: hồ sơ \"Cần xử lý\", bài vẫn hiện; báo cáo thứ 6: bài tự ẩn (HIDDEN), hồ sơ \"Khẩn\" (URGENT); người khác không còn thấy hay báo cáo được bài, tác giả vẫn thấy")
    void moreThanFiveReportsHideThePost() throws Exception {
        String tk = token();
        long author = member();
        long post = publishedPost(author, "Bài bị nhiều người báo cáo " + tk, "x");

        for (int i = 1; i <= 5; i++) {
            postAs(member(), Map.of("reasonId", reason("Lừa đảo")), "/api/blogs/{id}/report", post)
                    .andExpect(status().isOk());
        }
        Map<String, Object> afterFive = openCase("BLOG", post);
        assertThat(((Number) afterFive.get("report_count")).intValue()).isEqualTo(5);
        assertThat(afterFive.get("priority")).isEqualTo("NORMAL");
        assertThat(blogStatus(post)).isEqualTo("PUBLISHED");

        postAs(member(), Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
        Map<String, Object> afterSix = openCase("BLOG", post);
        assertThat(((Number) afterSix.get("report_count")).intValue()).isEqualTo(6);
        assertThat(afterSix.get("priority")).isEqualTo("URGENT");
        assertThat(blogStatus(post)).isEqualTo("HIDDEN");

        getAs(null, "/api/blogs/{id}", post).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(MSG19));
        assertThat(data(getAs(null, "/api/blogs?q={q}", tk)).get("totalItems").asLong()).isZero();
        getAs(author, "/api/blogs/{id}", post).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HIDDEN"));
        postAs(member(), Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
    }

    @Test
    @DisplayName("TC-1.11-10 [Quy tắc báo cáo] – Bình luận có hơn 5 báo cáo tự ẩn: không còn trong bài, số bình luận giảm, hồ sơ \"Khẩn\"")
    void moreThanFiveReportsHideAComment() throws Exception {
        long author = member();
        long writer = member();
        long post = publishedPost(author, "Bài " + token(), "x");
        postAs(writer, Map.of("content", "Bình luận bị báo cáo nhiều"), "/api/blogs/{id}/comments", post)
                .andExpect(status().isOk());
        long comment = lastCommentId(post, writer);
        assertThat(commentCount(post)).isEqualTo(1);

        for (int i = 1; i <= 6; i++) {
            postAs(member(), Map.of("reasonId", reason("Xúc phạm")), "/api/comments/{id}/report", comment)
                    .andExpect(status().isOk());
        }

        assertThat(commentRow(comment).get("status")).isEqualTo("HIDDEN");
        assertThat(commentCount(post)).isZero();
        assertThat(openCase("COMMENT", comment).get("priority")).isEqualTo("URGENT");
        JsonNode d = data(getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk()));
        assertThat(d.get("comments")).isEmpty();
        postAs(member(), Map.of("reasonId", reason("Spam")), "/api/comments/{id}/report", comment)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy bình luận này."));
    }

    // ================================================================ who may report what

    @Test
    @DisplayName("TC-1.11-11 [Phân quyền] – Khách → 401; tài khoản nhân viên → 403 MSG18; không lưu báo cáo")
    void guestsAndStaffCannotReport() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "x");

        postAs(null, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isUnauthorized());
        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            postAs(account(role, 30), Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
        assertThat(caseCount("BLOG", post)).isZero();
    }

    @Test
    @DisplayName("TC-1.11-12 [Đối tượng hợp lệ] – Nội dung không còn hiển thị (không tồn tại, đang quét, của tài khoản bị khóa/đã xóa, bình luận của tài khoản bị khóa) → 404")
    void onlyVisibleContentCanBeReported() throws Exception {
        long reporter = member();
        long missing = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000000 FROM blogs", Long.class);
        long author = member();
        long scanning = insertBlog(author, "POST", "Đang quét " + token(), "x", "PROCESSING", Instant.now(),
                topic("Kinh nghiệm"));
        long lockedAuthor = member();
        long ofLocked = publishedPost(lockedAuthor, "Bài người bị khóa " + token(), "x");
        long deletedAuthor = member();
        long ofDeleted = publishedPost(deletedAuthor, "Bài người đã xóa " + token(), "x");
        long post = publishedPost(author, "Bài " + token(), "x");
        long lockedWriter = member();
        postAs(lockedWriter, Map.of("content", "Bình luận sẽ bị ẩn theo tác giả"), "/api/blogs/{id}/comments", post)
                .andExpect(status().isOk());
        long commentOfLocked = lastCommentId(post, lockedWriter);
        setStatus(lockedAuthor, "LOCKED");
        setStatus(deletedAuthor, "DELETED");
        setStatus(lockedWriter, "LOCKED");

        for (long id : new long[]{missing, scanning, ofLocked, ofDeleted}) {
            postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
        }
        postAs(reporter, Map.of("reasonId", reason("Spam")), "/api/comments/{id}/report", commentOfLocked)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy bình luận này."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE reporter_id = ?", Integer.class, reporter)).isZero();
    }

    // ================================================================ reporting lock after false reports

    @Test
    @DisplayName("TC-1.11-13 [Khóa báo cáo] – Thành viên đang bị khóa báo cáo → 403 \"Bạn bị khóa báo cáo đến dd/MM/yyyy vì có quá nhiều báo cáo sai.\"; khóa đã hết hạn thì báo cáo được")
    void mutedMemberCannotReportUntilTheLockEnds() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "x");
        long muted = member();
        Instant until = Instant.now().plus(Duration.ofDays(3));
        jdbc.update("UPDATE users SET report_muted_until = ? WHERE id = ?", Timestamp.from(until), muted);

        postAs(muted, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(LOCK_PREFIX + day(until) + LOCK_SUFFIX));
        assertThat(caseCount("BLOG", post)).isZero();

        jdbc.update("UPDATE users SET report_muted_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), muted);
        postAs(muted, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
    }

    @Test
    @DisplayName("TC-1.11-14 [Khóa báo cáo / Biên] – Báo cáo sai thứ 5 chưa khóa; báo cáo sai thứ 6 (hơn 5) khóa báo cáo 7 ngày, đếm lại từ 0 và thông báo cho thành viên")
    void moreThanFiveFalseReportsLockReportingForSevenDays() throws Exception {
        long author = member();
        long moderator = account("MODERATOR", 30);
        long fifth = member();
        long sixth = member();
        jdbc.update("UPDATE users SET false_report_count = 4 WHERE id = ?", fifth);
        jdbc.update("UPDATE users SET false_report_count = 5 WHERE id = ?", sixth);
        long post = publishedPost(author, "Bài bình thường " + token(), "x");
        postAs(fifth, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isOk());
        postAs(sixth, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isOk());

        long caseId = ((Number) openCase("BLOG", post).get("id")).longValue();
        postAs(moderator, null, "/api/moderator/reports/{id}/dismiss", caseId).andExpect(status().isOk());

        // Exactly 5 false reports: not locked yet
        Map<String, Object> five = jdbc.queryForMap("SELECT false_report_count, report_muted_until FROM users WHERE id = ?", fifth);
        assertThat(((Number) five.get("false_report_count")).intValue()).isEqualTo(5);
        assertThat(five.get("report_muted_until")).isNull();

        // 6 false reports: locked mute_days (7) days, the count starts over, the member is told
        Map<String, Object> six = jdbc.queryForMap("SELECT false_report_count, report_muted_until FROM users WHERE id = ?", sixth);
        assertThat(((Number) six.get("false_report_count")).intValue()).isZero();
        Instant until = jdbc.queryForObject("SELECT report_muted_until FROM users WHERE id = ?", Timestamp.class, sixth)
                .toInstant();
        assertThat(until).isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(2, ChronoUnit.MINUTES));
        assertThat(jdbc.queryForObject(
                "SELECT content FROM notifications WHERE user_id = ? AND type = 'REPORT_MUTED' ORDER BY id DESC LIMIT 1",
                String.class, sixth)).isEqualTo(LOCK_PREFIX + day(until) + LOCK_SUFFIX);

        long next = publishedPost(author, "Bài khác " + token(), "x");
        postAs(sixth, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", next)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(LOCK_PREFIX + day(until) + LOCK_SUFFIX));
        postAs(fifth, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", next).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-1.11-15 [Luồng chính] – Nhiều thành viên báo cáo cùng một nội dung: một hồ sơ mở duy nhất, số báo cáo tăng dần, mỗi người một lý do riêng")
    void reportsOfManyMembersShareOneCase() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "x");
        Map<Long, Long> reasonOf = new HashMap<>();
        for (String label : List.of("Lừa đảo", "Sai sự thật", "Xúc phạm")) {
            long reporter = member();
            reasonOf.put(reporter, reason(label));
            postAs(reporter, Map.of("reasonId", reason(label)), "/api/blogs/{id}/report", post).andExpect(status().isOk());
        }

        assertThat(caseCount("BLOG", post)).isEqualTo(1);
        Map<String, Object> c = openCase("BLOG", post);
        assertThat(((Number) c.get("report_count")).intValue()).isEqualTo(3);
        for (Map.Entry<Long, Long> e : reasonOf.entrySet()) {
            assertThat(jdbc.queryForObject("SELECT reason_id FROM reports WHERE case_id = ? AND reporter_id = ?",
                    Long.class, c.get("id"), e.getKey())).isEqualTo(e.getValue());
        }
    }

    // ================================================================ helpers

    private long reason(String label) {
        return jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'REPORT' AND label = ?", Long.class, label);
    }

    private Map<String, Object> openCase(String type, long targetId) {
        return jdbc.queryForMap("SELECT * FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN'",
                type, targetId);
    }

    private int caseCount(String type, long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_cases WHERE target_type = ? AND target_id = ?",
                Integer.class, type, targetId);
    }

    private static String day(Instant instant) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy").format(instant.atZone(JAPAN));
    }
}
