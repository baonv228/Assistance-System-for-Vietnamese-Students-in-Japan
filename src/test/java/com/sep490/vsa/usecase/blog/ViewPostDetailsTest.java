package com.sep490.vsa.usecase.blog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.4 View Post Details (SRS Report 3, II.1.4 "View Post Details"; CLAUDE.md "Blog feed": Post Detail (UC-10) and
 * the hidden-author rule of "Key business rules").
 * <p>
 * API: {@code GET /api/blogs/{id}} (public) with {@code viewer} flags, and the actions of the action bar
 * {@code POST|DELETE /api/blogs/{id}/like}, {@code POST /api/blogs/{id}/report}. Saving ("Lưu") belongs to UC 1.12 and
 * the report rules to UC 1.11; Add Comment to UC 1.8. The map ("Hiện bản đồ" / "Ẩn bản đồ", 6a), clickable links,
 * "Về Blog", "Thử lại" (2b) and the texts of the notes are drawn by the page and are not tested here.
 */
@DisplayName("UC 1.4 – View Post Details (Xem chi tiết bài viết)")
class ViewPostDetailsTest extends UseCaseTestBase {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.4-01 [Luồng chính] – Khách đọc bài đã đăng: tiêu đề, nội dung, tác giả có tag và hạng, chủ đề, ảnh/video đã qua kiểm duyệt, địa điểm liên quan có tọa độ, lý do báo cáo; khách không có quyền thao tác")
    void guestReadsAPublishedPostWithEverythingAround() throws Exception {
        String tk = token();
        long author = member();
        grantMentorTag(author);
        jdbc.update("UPDATE users SET points = 150 WHERE id = ?", author);
        long post = insertBlog(author, "POST", "Kinh nghiệm làm thẻ cư trú " + tk, "Mang theo hộ chiếu và ảnh 3x4.",
                "PUBLISHED", Instant.now(), topic("Thủ tục giấy tờ"), topic("Visa và cư trú"));
        long place = place("Phòng hành chính quận " + tk);
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", post, place);
        long passed = media(author, post, "PASSED", 0);
        long scanning = media(author, post, "PROCESSING", 1);
        long rejected = media(author, post, "REJECTED", 2);

        JsonNode d = data(getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        assertThat(d.get("id").asLong()).isEqualTo(post);
        assertThat(d.get("type").asText()).isEqualTo("POST");
        assertThat(d.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(d.get("title").asText()).isEqualTo("Kinh nghiệm làm thẻ cư trú " + tk);
        assertThat(d.get("content").asText()).isEqualTo("Mang theo hộ chiếu và ảnh 3x4.");
        assertThat(d.get("edited").asBoolean()).isFalse();
        JsonNode a = d.get("author");
        assertThat(a.get("id").asLong()).isEqualTo(author);
        assertThat(a.get("displayName").asText()).isEqualTo(displayName(author));
        assertThat(names(a.get("tags"), "name")).containsExactly("Mentor");
        assertThat(a.get("rankName").asText()).isEqualTo("Người chỉ đường");
        assertThat(names(d.get("topics"), "name")).containsExactlyInAnyOrder("Thủ tục giấy tờ", "Visa và cư trú");

        // "Địa điểm liên quan": name and coordinates for the map and "Chỉ đường"
        JsonNode p = d.get("places").get(0);
        assertThat(d.get("places")).hasSize(1);
        assertThat(p.get("id").asLong()).isEqualTo(place);
        assertThat(p.get("name").asText()).isEqualTo("Phòng hành chính quận " + tk);
        assertThat(p.get("lat").asDouble()).isEqualTo(35.728);
        assertThat(p.get("lng").asDouble()).isEqualTo(139.77);

        // Only files that passed the scan are shown to others
        assertThat(idsOf(d.get("media"))).containsExactly(passed).doesNotContain(scanning, rejected);
        assertThat(d.get("media").get(0).get("type").asText()).isEqualTo("IMAGE");
        assertThat(d.get("media").get(0).get("url").asText()).startsWith("/media/");

        assertThat(d.get("likeCount").asInt()).isZero();
        assertThat(d.get("commentCount").asInt()).isZero();
        assertThat(d.get("comments")).isEmpty();
        assertThat(names(d.get("reportReasons"), "label")).containsExactly("Lừa đảo", "Sai sự thật", "Xúc phạm", "Spam");

        JsonNode viewer = d.get("viewer");
        assertThat(viewer.get("signedIn").asBoolean()).isFalse();
        assertThat(viewer.get("author").asBoolean()).isFalse();
        assertThat(viewer.get("canInteract").asBoolean()).isFalse();
        assertThat(viewer.get("liked").asBoolean()).isFalse();
        assertThat(viewer.get("saved").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.4-02 [Luồng chính 3, 5] – Thành viên xem bài: \"· đã sửa\", số bình luận, bình luận và trả lời cũ trước; thành viên được thao tác (canInteract)")
    void memberSeesEditedMarkAndCommentsOldestFirst() throws Exception {
        String tk = token();
        long author = member();
        long reader = member();
        long post = publishedPost(author, "Bài đã sửa " + tk, "Nội dung.");
        jdbc.update("UPDATE blogs SET is_edited = TRUE WHERE id = ?", post);
        long c1 = member();
        long c2 = member();
        long c3 = member();
        // Through the API, so comment_count follows the comments (three different writers: no 15-second wait)
        postAs(c1, Map.of("content", "Bình luận thứ nhất"), "/api/blogs/{id}/comments", post).andExpect(status().isOk());
        long first = lastCommentId(post, c1);
        postAs(c2, Map.of("content", "Bình luận thứ hai"), "/api/blogs/{id}/comments", post).andExpect(status().isOk());
        long second = lastCommentId(post, c2);
        postAs(c3, Map.of("content", "Trả lời bình luận thứ nhất", "parentId", first), "/api/blogs/{id}/comments", post)
                .andExpect(status().isOk());
        long reply = lastCommentId(post, c3);
        // Make the order of creation visible to the second-precision timestamps
        jdbc.update("UPDATE comments SET created_at = DATE_SUB(created_at, INTERVAL 3 MINUTE) WHERE id = ?", first);
        jdbc.update("UPDATE comments SET created_at = DATE_SUB(created_at, INTERVAL 2 MINUTE) WHERE id = ?", second);
        jdbc.update("UPDATE comments SET is_edited = TRUE WHERE id = ?", second);

        JsonNode d = data(getAs(reader, "/api/blogs/{id}", post).andExpect(status().isOk()));

        assertThat(d.get("edited").asBoolean()).isTrue();
        assertThat(d.get("commentCount").asInt()).isEqualTo(3);
        JsonNode comments = d.get("comments");
        assertThat(idsOf(comments)).containsExactly(first, second);
        assertThat(idsOf(comments.get(0).get("replies"))).containsExactly(reply);
        assertThat(comments.get(0).get("content").asText()).isEqualTo("Bình luận thứ nhất");
        assertThat(comments.get(0).get("author").get("id").asLong()).isEqualTo(c1);
        assertThat(comments.get(1).get("edited").asBoolean()).isTrue();
        assertThat(comments.get(0).get("mine").asBoolean()).isFalse();

        JsonNode viewer = d.get("viewer");
        assertThat(viewer.get("signedIn").asBoolean()).isTrue();
        assertThat(viewer.get("author").asBoolean()).isFalse();
        assertThat(viewer.get("canInteract").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.4-03 [Luồng chính 5] – Bình luận đã bị gỡ/ẩn hoặc của tài khoản bị khóa mà còn trả lời hiện thành chỗ trống \"Bình luận đã bị xóa.\"; không còn trả lời thì không hiện")
    void removedOrHiddenCommentWithRepliesStaysAsPlaceholder() throws Exception {
        String tk = token();
        long author = member();
        long post = publishedPost(author, "Bài có bình luận bị gỡ " + tk, "x");
        long w1 = member();
        long w2 = member();
        long locked = member();
        long removedWithReply = comment(post, w1, null, "Bình luận bị gỡ", "REMOVED", 10);
        long replyKept = comment(post, w2, removedWithReply, "Trả lời vẫn còn", "PUBLISHED", 9);
        long hiddenAlone = comment(post, w1, null, "Bình luận bị ẩn, không có trả lời", "HIDDEN", 8);
        long lockedWithReply = comment(post, locked, null, "Của tài khoản bị khóa", "PUBLISHED", 7);
        long replyToLocked = comment(post, w2, lockedWithReply, "Trả lời bình luận của người bị khóa", "PUBLISHED", 6);
        long lockedAlone = comment(post, locked, null, "Bình luận khác của người bị khóa", "PUBLISHED", 5);
        setStatus(locked, "LOCKED");

        JsonNode comments = data(getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk())).get("comments");

        assertThat(idsOf(comments)).containsExactly(removedWithReply, lockedWithReply)
                .doesNotContain(hiddenAlone, lockedAlone);
        for (JsonNode placeholder : List.of(comments.get(0), comments.get(1))) {
            assertThat(placeholder.get("removed").asBoolean()).isTrue();
            assertThat(placeholder.get("content").asText()).isEmpty();
            assertThat(placeholder.get("author").isNull()).isTrue();
        }
        assertThat(idsOf(comments.get(0).get("replies"))).containsExactly(replyKept);
        assertThat(idsOf(comments.get(1).get("replies"))).containsExactly(replyToLocked);
        assertThat(comments.get(0).get("replies").get(0).get("content").asText()).isEqualTo("Trả lời vẫn còn");
    }

    @Test
    @DisplayName("TC-1.4-04 [Luồng chính 4] – Địa điểm đã bị gỡ không hiện; địa điểm đã gộp hiện địa điểm được gộp vào")
    void relatedPlacesFollowRemovalsAndMerges() throws Exception {
        String tk = token();
        long author = member();
        long post = publishedPost(author, "Bài có địa điểm " + tk, "x");
        long kept = place("Nơi giữ lại " + tk);
        long merged = place("Nơi bị gộp " + tk);
        long removed = place("Nơi bị gỡ " + tk);
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", kept, merged);
        jdbc.update("UPDATE places SET status = 'REMOVED', removed_reason = 'test' WHERE id = ?", removed);
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?), (?, ?)", post, merged, post, removed);

        JsonNode places = data(getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk())).get("places");

        assertThat(idsOf(places)).containsExactly(kept);
        assertThat(places.get(0).get("name").asText()).isEqualTo("Nơi giữ lại " + tk);
    }

    // ================================================================ 2a: not found

    @Test
    @DisplayName("TC-1.4-05 [Luồng thay thế 2a] – Bài không tồn tại, bị gỡ, bị ẩn, đang quét hoặc bị từ chối (với người khác) → 404 MSG19")
    void missingOrUnpublishedPostAnswersMsg19() throws Exception {
        long author = member();
        long other = member();
        long missing = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000000 FROM blogs", Long.class);
        getAs(null, "/api/blogs/{id}", missing)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG19));

        for (String state : List.of("HIDDEN", "PROCESSING", "REJECTED", "REMOVED")) {
            long post = insertBlog(author, "POST", "Bài " + state + " " + token(), "x", state, Instant.now(),
                    topic("Kinh nghiệm"));
            for (Long who : new Long[]{null, other}) {
                getAs(who, "/api/blogs/{id}", post)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message").value(MSG19));
            }
        }
        // A post removed by a Moderator is not shown to its author either
        long removed = insertBlog(author, "POST", "Bị gỡ " + token(), "x", "REMOVED", Instant.now(), topic("Kinh nghiệm"));
        getAs(author, "/api/blogs/{id}", removed).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
    }

    @Test
    @DisplayName("TC-1.4-06 [Luồng thay thế 2a] – Tác giả bị khóa hoặc đã xóa tài khoản → 404 MSG19; mở khóa thì bài hiện lại")
    void postOfLockedOrDeletedAuthorIsNotFound() throws Exception {
        long lockedAuthor = member();
        long deletedAuthor = member();
        long ofLocked = publishedPost(lockedAuthor, "Bài của người bị khóa " + token(), "x");
        long ofDeleted = publishedPost(deletedAuthor, "Bài của người đã xóa " + token(), "x");
        setStatus(lockedAuthor, "LOCKED");
        setStatus(deletedAuthor, "DELETED");
        long reader = member();

        for (long post : new long[]{ofLocked, ofDeleted}) {
            getAs(null, "/api/blogs/{id}", post).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(MSG19));
            getAs(reader, "/api/blogs/{id}", post).andExpect(status().isNotFound());
        }
        // Acting on it answers "not found" too
        postAs(reader, null, "/api/blogs/{id}/like", ofLocked).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));

        setStatus(lockedAuthor, "ACTIVE");
        getAs(null, "/api/blogs/{id}", ofLocked).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ofLocked));
    }

    @Test
    @DisplayName("TC-1.4-07 [Luồng thay thế 2a] – Mã của một câu hỏi không mở như bài viết (404 MSG19); trang Hỏi-Đáp mở được câu hỏi đó")
    void questionIdDoesNotOpenAsAPost() throws Exception {
        long asker = member();
        long question = publishedQuestion(asker, "Câu hỏi " + token(), "Mô tả");

        getAs(null, "/api/blogs/{id}", question)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(null, "/api/questions/{id}", question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("QA"));
    }

    // ================================================================ 3a: author views own post

    @Test
    @DisplayName("TC-1.4-08 [Luồng thay thế 3a] – Tác giả xem bài của mình: viewer.author = true; bài đang quét, bị ẩn do báo cáo hoặc bị từ chối vẫn mở được với trạng thái tương ứng và không nhận thao tác")
    void authorOpensOwnPostInEveryShownState() throws Exception {
        long author = member();
        long published = publishedPost(author, "Bài của tôi " + token(), "x");
        JsonNode own = data(getAs(author, "/api/blogs/{id}", published).andExpect(status().isOk()));
        assertThat(own.get("viewer").get("author").asBoolean()).isTrue();
        assertThat(own.get("viewer").get("canInteract").asBoolean()).isTrue();

        for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
            long post = insertBlog(author, "POST", "Bài " + state + " " + token(), "x", state, Instant.now(),
                    topic("Kinh nghiệm"));
            JsonNode d = data(getAs(author, "/api/blogs/{id}", post).andExpect(status().isOk()));
            assertThat(d.get("status").asText()).isEqualTo(state);
            assertThat(d.get("viewer").get("author").asBoolean()).isTrue();
            // Comments are closed while the post is not published
            assertThat(d.get("viewer").get("canInteract").asBoolean()).as(state).isFalse();
        }
    }

    @Test
    @DisplayName("TC-1.4-09 [Luồng thay thế 3a] – Tác giả thấy cả ảnh đang quét của bài mình; người khác chỉ thấy ảnh đã qua kiểm duyệt")
    void authorAlsoSeesOwnFilesBeingScanned() throws Exception {
        long author = member();
        long post = publishedPost(author, "Bài có ảnh đang quét " + token(), "x");
        long passed = media(author, post, "PASSED", 0);
        long scanning = media(author, post, "PROCESSING", 1);

        assertThat(idsOf(data(getAs(author, "/api/blogs/{id}", post).andExpect(status().isOk())).get("media")))
                .containsExactly(passed, scanning);
        assertThat(idsOf(data(getAs(member(), "/api/blogs/{id}", post).andExpect(status().isOk())).get("media")))
                .containsExactly(passed);
    }

    // ================================================================ 5a / 5b: guest and staff

    @Test
    @DisplayName("TC-1.4-10 [Luồng thay thế 5a] – Khách: các nút thao tác dẫn tới đăng nhập (API trả 401 cho thích, bình luận, báo cáo)")
    void guestActionsNeedSignIn() throws Exception {
        long post = publishedPost(member(), "Bài cho khách " + token(), "x");

        postAs(null, null, "/api/blogs/{id}/like", post).andExpect(status().isUnauthorized());
        postAs(null, Map.of("content", "Xin chào"), "/api/blogs/{id}/comments", post).andExpect(status().isUnauthorized());
        postAs(null, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT like_count FROM blogs WHERE id = ?", Integer.class, post)).isZero();
    }

    @Test
    @DisplayName("TC-1.4-11 [Luồng thay thế 5b] – Tài khoản nhân viên (Moderator, Admin) chỉ xem được: canInteract = false, thích/bình luận bị từ chối MSG18")
    void staffAccountsOnlyRead() throws Exception {
        long post = publishedPost(member(), "Bài cho nhân viên " + token(), "x");

        for (String role : List.of("MODERATOR", "ADMIN", "ACADEMIC_MANAGER")) {
            long staff = account(role, 30);
            JsonNode viewer = data(getAs(staff, "/api/blogs/{id}", post).andExpect(status().isOk())).get("viewer");
            assertThat(viewer.get("signedIn").asBoolean()).isTrue();
            assertThat(viewer.get("canInteract").asBoolean()).as(role).isFalse();

            postAs(staff, null, "/api/blogs/{id}/like", post)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
            postAs(staff, Map.of("content", "Nhân viên bình luận"), "/api/blogs/{id}/comments", post)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
    }

    // ================================================================ 7: like, report

    @Test
    @DisplayName("TC-1.4-12 [Luồng chính 7] – Thành viên thích rồi bỏ thích bài: số lượt thích tăng/giảm, thích lần hai không đếm trùng")
    void memberLikesAndUnlikesAPost() throws Exception {
        long author = member();
        long reader = member();
        long another = member();
        long post = publishedPost(author, "Bài được thích " + token(), "x");

        postAs(reader, null, "/api/blogs/{id}/like", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.count").value(1));
        postAs(reader, null, "/api/blogs/{id}/like", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        postAs(another, null, "/api/blogs/{id}/like", post).andExpect(jsonPath("$.data.count").value(2));

        JsonNode d = data(getAs(reader, "/api/blogs/{id}", post).andExpect(status().isOk()));
        assertThat(d.get("likeCount").asInt()).isEqualTo(2);
        assertThat(d.get("viewer").get("liked").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_likes WHERE blog_id = ?", Integer.class, post)).isEqualTo(2);

        deleteAs(reader, "/api/blogs/{id}/like", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.count").value(1));
        assertThat(data(getAs(reader, "/api/blogs/{id}", post)).get("viewer").get("liked").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TC-1.4-13 [Luồng thay thế 7b] – Tác giả không thích và không báo cáo được bài của chính mình")
    void authorCannotLikeOrReportOwnPost() throws Exception {
        long author = member();
        long post = publishedPost(author, "Bài của tôi " + token(), "x");

        postAs(author, null, "/api/blogs/{id}/like", post)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể thích bài viết của chính mình."));
        postAs(author, Map.of("reasonId", reason("Spam")), "/api/blogs/{id}/report", post)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể báo cáo nội dung của chính mình."));
        assertThat(jdbc.queryForObject("SELECT like_count FROM blogs WHERE id = ?", Integer.class, post)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_cases WHERE target_type = 'BLOG' AND target_id = ?",
                Integer.class, post)).isZero();
    }

    @Test
    @DisplayName("TC-1.4-14 [Luồng thay thế 7a] – Báo cáo bài từ thanh thao tác: chọn lý do + ghi chú → MSG38 \"Cảm ơn bạn. Báo cáo đã được gửi.\" (chi tiết ở UC 1.11)")
    void memberReportsThePostFromTheActionBar() throws Exception {
        long post = publishedPost(member(), "Bài bị báo cáo " + token(), "x");
        long reader = member();

        postAs(reader, Map.of("reasonId", reason("Lừa đảo"), "note", "Lừa tiền cọc nhà"), "/api/blogs/{id}/report", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG38));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reports r JOIN report_cases c ON c.id = r.case_id
                WHERE c.target_type = 'BLOG' AND c.target_id = ? AND r.reporter_id = ?""", Integer.class, post, reader))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.4-15 [Hậu điều kiện / Ngoại lệ] – Xem bài không thay đổi dữ liệu; thích bài chưa đăng (đang quét, bị ẩn) → 404 MSG19")
    void viewingChangesNothingAndUnpublishedPostsTakeNoLikes() throws Exception {
        long author = member();
        long reader = member();
        long post = publishedPost(author, "Bài chỉ để xem " + token(), "x");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT like_count, comment_count, updated_at, is_edited FROM blogs WHERE id = ?", post);

        for (int i = 0; i < 3; i++) {
            getAs(reader, "/api/blogs/{id}", post).andExpect(status().isOk());
            getAs(null, "/api/blogs/{id}", post).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForMap("SELECT like_count, comment_count, updated_at, is_edited FROM blogs WHERE id = ?", post))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_likes WHERE blog_id = ?", Integer.class, post)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE blog_id = ?", Integer.class, post)).isZero();

        for (String state : List.of("PROCESSING", "HIDDEN")) {
            long unpublished = insertBlog(author, "POST", "Chưa đăng " + token(), "x", state, Instant.now(),
                    topic("Kinh nghiệm"));
            postAs(reader, null, "/api/blogs/{id}/like", unpublished)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
        }
    }

    // ================================================================ helpers

    /** A file attached to the post, with the given scan state. */
    private long media(long owner, long blogId, String scanStatus, int sortOrder) {
        String key = "blog/2026/11/" + UUID.randomUUID() + ".jpg";
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, width, height, scan_status)
                VALUES (?, 'IMAGE', ?, 'image/jpeg', 1024, 64, 48, ?)""", owner, key, scanStatus);
        long id = jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, key);
        jdbc.update("INSERT INTO blog_media (blog_id, media_id, sort_order) VALUES (?, ?, ?)", blogId, id, sortOrder);
        return id;
    }

    /** A comment stored without the API, {@code minutesAgo} minutes old. */
    private long comment(long blogId, long authorId, Long parentId, String content, String status, int minutesAgo) {
        Timestamp at = Timestamp.from(Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", blogId, authorId, parentId, content, status, at, at);
        return jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE blog_id = ? AND author_id = ?", Long.class,
                blogId, authorId);
    }

    private long reason(String label) {
        return jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'REPORT' AND label = ?", Long.class, label);
    }

    private static List<Long> idsOf(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.get(field).asText()));
        return values;
    }
}
