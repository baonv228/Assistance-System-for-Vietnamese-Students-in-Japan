package com.sep490.vsa.usecase.saved;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.12 Add Item to Favorites ("Lưu"). The SRS has no section of its own for it: the tests follow the "Lưu" action of
 * View Post Details (II.1.4, step 5/7) and View Question Details (II.1.15, step 4/5), the data rule "Unique Saved
 * Location" (a user cannot save the same item twice), UC-25 "View Saved Location, blog", the Map's "Lưu" button of the
 * screen spec, and CLAUDE.md ("Đã lưu": posts, questions, places, guides through {@code POST|DELETE
 * /api/guides/{id}/save}, "Nhật ngữ" through {@code POST|DELETE /api/phrases/{id}/save}; Members only, staff MSG18;
 * saving one's own post is allowed; only content others can see can be saved).
 */
@DisplayName("UC 1.12 – Add Item to Favorites (Thêm vào mục yêu thích)")
class AddItemToFavoritesTest extends SavedTestSupport {

    private static final String PLACE_GONE = "Địa điểm này không còn trên Senpai ơi.";
    private static final String GUIDE_GONE = "Không tìm thấy cẩm nang.";
    private static final String PHRASE_GONE = "Không tìm thấy mẫu câu này.";

    @Test
    @DisplayName("TC-1.12-01 [Luồng chính] Lưu bài viết đã đăng: báo \"Đã lưu bài viết.\", nút chuyển sang \"Đã lưu\"")
    void saveAPost() throws Exception {
        long me = member();
        long post = publishedPost(member(), "Bài " + token(), "Nội dung");

        postAs(me, null, "/api/blogs/{id}/save", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã lưu bài viết."))
                .andExpect(jsonPath("$.data.saved").value(true));

        assertThat(savedBlogRows(me, post)).isEqualTo(1);
        getAs(me, "/api/blogs/{id}", post).andExpect(jsonPath("$.data.viewer.saved").value(true));
    }

    @Test
    @DisplayName("TC-1.12-02 [Luồng chính] Bấm lại để bỏ lưu bài viết: báo \"Đã bỏ lưu bài viết.\", mục biến khỏi danh sách đã lưu")
    void unsaveAPost() throws Exception {
        long me = member();
        long post = publishedPost(member(), "Bài " + token(), "Nội dung");
        postAs(me, null, "/api/blogs/{id}/save", post).andExpect(status().isOk());

        deleteAs(me, "/api/blogs/{id}/save", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ lưu bài viết."))
                .andExpect(jsonPath("$.data.saved").value(false));

        assertThat(savedBlogRows(me, post)).isZero();
        getAs(me, "/api/blogs/{id}", post).andExpect(jsonPath("$.data.viewer.saved").value(false));
    }

    @Test
    @DisplayName("TC-1.12-03 [Quy tắc dữ liệu] Lưu hai lần chỉ giữ một mục; bỏ lưu mục chưa lưu không báo lỗi")
    void savingTwiceKeepsOneRow() throws Exception {
        long me = member();
        long post = publishedPost(member(), "Bài " + token(), "Nội dung");

        postAs(me, null, "/api/blogs/{id}/save", post).andExpect(status().isOk());
        postAs(me, null, "/api/blogs/{id}/save", post).andExpect(status().isOk());
        assertThat(savedBlogRows(me, post)).isEqualTo(1);

        deleteAs(me, "/api/blogs/{id}/save", post).andExpect(status().isOk());
        deleteAs(me, "/api/blogs/{id}/save", post)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedBlogRows(me, post)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-04 [Luồng chính] Thành viên được lưu cả bài viết của chính mình")
    void ownPostCanBeSaved() throws Exception {
        long me = member();
        long own = publishedPost(me, "Bài " + token(), "Nội dung");

        postAs(me, null, "/api/blogs/{id}/save", own)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(true));

        assertThat(savedBlogRows(me, own)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.12-05 [Luồng chính] Lưu và bỏ lưu câu hỏi: \"Đã lưu câu hỏi.\" / \"Đã bỏ lưu câu hỏi.\"")
    void saveAndUnsaveAQuestion() throws Exception {
        long me = member();
        long question = publishedQuestion(member(), "Hỏi " + token(), "Mô tả");

        postAs(me, null, "/api/questions/{id}/save", question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu câu hỏi."))
                .andExpect(jsonPath("$.data.saved").value(true));
        assertThat(savedBlogRows(me, question)).isEqualTo(1);
        getAs(me, "/api/questions/{id}", question).andExpect(jsonPath("$.data.viewer.saved").value(true));

        deleteAs(me, "/api/questions/{id}/save", question)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ lưu câu hỏi."))
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedBlogRows(me, question)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-06 [Ngoại lệ] Sai loại nội dung (lưu câu hỏi qua đường dẫn Blog và ngược lại): MSG19, không lưu")
    void wrongKindIsNotFound() throws Exception {
        long me = member();
        long author = member();
        long post = publishedPost(author, "Bài " + token(), "Nội dung");
        long question = publishedQuestion(author, "Hỏi " + token(), "Mô tả");

        postAs(me, null, "/api/blogs/{id}/save", question)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        postAs(me, null, "/api/questions/{id}/save", post)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));

        assertThat(savedBlogRows(me, post) + savedBlogRows(me, question)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-07 [Ngoại lệ] Bài chưa đăng, bị ẩn, bị gỡ, bị từ chối, không tồn tại hoặc của tài khoản bị khóa/đã xóa: MSG19, không lưu")
    void contentOthersCannotSeeCannotBeSaved() throws Exception {
        long me = member();
        long author = member();
        long locked = member();
        long deleted = member();
        Instant now = Instant.now();
        List<Long> blocked = List.of(
                insertBlog(author, "POST", "Quét " + token(), "x", "PROCESSING", now, topic("Kinh nghiệm")),
                insertBlog(author, "POST", "Ẩn " + token(), "x", "HIDDEN", now, topic("Kinh nghiệm")),
                insertBlog(author, "POST", "Gỡ " + token(), "x", "REMOVED", now, topic("Kinh nghiệm")),
                insertBlog(author, "POST", "Từ chối " + token(), "x", "REJECTED", now, topic("Kinh nghiệm")),
                publishedPost(locked, "Khóa " + token(), "x"),
                publishedPost(deleted, "Xóa " + token(), "x"));
        setStatus(locked, "LOCKED");
        setStatus(deleted, "DELETED");

        for (long id : blocked) {
            postAs(me, null, "/api/blogs/{id}/save", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
            assertThat(savedBlogRows(me, id)).isZero();
        }
        long missing = jdbc.queryForObject("SELECT MAX(id) + 1000 FROM blogs", Long.class);
        postAs(me, null, "/api/blogs/{id}/save", missing)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
    }

    @Test
    @DisplayName("TC-1.12-08 [Phân quyền] Khách phải đăng nhập (401); tài khoản nhân viên và tài khoản bị khóa không lưu được bài")
    void onlyActiveMembersSavePosts() throws Exception {
        long post = publishedPost(member(), "Bài " + token(), "Nội dung");
        long moderator = account("MODERATOR", 30);
        long admin = account("ADMIN", 30);
        long locked = member();
        setStatus(locked, "LOCKED");

        postAs(null, null, "/api/blogs/{id}/save", post).andExpect(status().isUnauthorized());
        postAs(moderator, null, "/api/blogs/{id}/save", post)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        postAs(admin, null, "/api/questions/{id}/save", publishedQuestion(member(), "Hỏi " + token(), "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        postAs(locked, null, "/api/blogs/{id}/save", post).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE blog_id = ?", Integer.class, post))
                .isZero();
    }

    @Test
    @DisplayName("TC-1.12-09 [Luồng chính – Bản đồ] Lưu địa điểm \"Đã lưu địa điểm.\", lưu hai lần chỉ một mục, bỏ lưu \"Đã bỏ lưu địa điểm.\"")
    void saveAndUnsaveAPlace() throws Exception {
        long me = member();
        long place = place("Quán " + token());

        postAs(me, null, "/api/map/places/{id}/save", place)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu địa điểm."))
                .andExpect(jsonPath("$.data.saved").value(true));
        postAs(me, null, "/api/map/places/{id}/save", place).andExpect(status().isOk());
        assertThat(savedPlaceRows(me, place)).isEqualTo(1);

        deleteAs(me, "/api/map/places/{id}/save", place)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ lưu địa điểm."))
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedPlaceRows(me, place)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-10 [Luồng chính – Bản đồ] Lưu địa điểm đã được gộp thì lưu vào địa điểm giữ lại")
    void savingAMergedPlaceSavesThePlaceKept() throws Exception {
        long me = member();
        long kept = place("Giữ " + token());
        long merged = mergedInto(kept, "Gộp " + token());

        postAs(me, null, "/api/map/places/{id}/save", merged).andExpect(status().isOk());

        assertThat(savedPlaceRows(me, kept)).isEqualTo(1);
        assertThat(savedPlaceRows(me, merged)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-11 [Ngoại lệ – Bản đồ] Địa điểm đã gỡ, bị từ chối, ghim chờ duyệt của người khác hoặc không tồn tại: \"Địa điểm này không còn trên Senpai ơi.\"")
    void placesNotOnTheMapCannotBeSaved() throws Exception {
        long me = member();
        long removed = place("Gỡ " + token());
        setPlaceStatus(removed, "REMOVED");
        long rejected = place("Từ chối " + token());
        setPlaceStatus(rejected, "REJECTED");
        long othersPin = place("Ghim " + token());
        setPlaceStatus(othersPin, "PENDING");
        long missing = jdbc.queryForObject("SELECT MAX(id) + 1000 FROM places", Long.class);

        for (long id : List.of(removed, rejected, othersPin, missing)) {
            postAs(me, null, "/api/map/places/{id}/save", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(PLACE_GONE));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_places WHERE user_id = ?", Integer.class, me))
                .isZero();
    }

    @Test
    @DisplayName("TC-1.12-12 [Luồng chính – Bản đồ] Người cắm ghim được lưu ghim của mình khi ghim còn chờ duyệt")
    void ownPendingPinCanBeSaved() throws Exception {
        long me = member();
        String name = "Ghim của tôi " + token();
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, address, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), 'Tokyo', 35.70, 139.70, 'USER', 'PENDING', ?)""",
                name, me);
        long pin = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);

        postAs(me, null, "/api/map/places/{id}/save", pin).andExpect(status().isOk());

        assertThat(savedPlaceRows(me, pin)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.12-13 [Luồng chính – Cẩm nang] Lưu cẩm nang \"Đã lưu cẩm nang.\", lưu hai lần chỉ một mục, bỏ lưu \"Đã bỏ lưu cẩm nang.\"")
    void saveAndUnsaveAGuide() throws Exception {
        long me = member();
        long guide = sampleGuides(1).get(0);

        postAs(me, null, "/api/guides/{id}/save", guide)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu cẩm nang."))
                .andExpect(jsonPath("$.data.saved").value(true));
        postAs(me, null, "/api/guides/{id}/save", guide).andExpect(status().isOk());
        assertThat(savedGuideRows(me, guide)).isEqualTo(1);
        getAs(me, "/api/guides/{id}/viewer", guide).andExpect(jsonPath("$.data.saved").value(true));

        deleteAs(me, "/api/guides/{id}/save", guide)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ lưu cẩm nang."))
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedGuideRows(me, guide)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-14 [Ngoại lệ – Cẩm nang] Cẩm nang đã xóa hoặc chưa đăng: \"Không tìm thấy cẩm nang.\", không lưu")
    void hiddenGuidesCannotBeSaved() throws Exception {
        long me = member();
        long deleted = guide("Đã xóa " + token(), "Tóm tắt");
        jdbc.update("UPDATE guide_items SET deleted_at = NOW() WHERE id = ?", deleted);
        long draft = guide("Bản nháp " + token(), "Tóm tắt");
        jdbc.update("UPDATE guide_items SET published = FALSE WHERE id = ?", draft);

        for (long id : List.of(deleted, draft)) {
            postAs(me, null, "/api/guides/{id}/save", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(GUIDE_GONE));
            assertThat(savedGuideRows(me, id)).isZero();
        }
    }

    @Test
    @DisplayName("TC-1.12-15 [Luồng chính – Nhật ngữ] Lưu mẫu câu \"Đã lưu.\", lưu hai lần chỉ một mục, bỏ lưu \"Đã bỏ lưu.\"")
    void saveAndUnsaveAPhrase() throws Exception {
        long me = member();
        long phrase = samplePhrases(1).get(0);

        postAs(me, null, "/api/phrases/{id}/save", phrase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu."))
                .andExpect(jsonPath("$.data.saved").value(true));
        postAs(me, null, "/api/phrases/{id}/save", phrase).andExpect(status().isOk());
        assertThat(savedPhraseRows(me, phrase)).isEqualTo(1);

        deleteAs(me, "/api/phrases/{id}/save", phrase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ lưu."))
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedPhraseRows(me, phrase)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-16 [Ngoại lệ – Nhật ngữ] Mẫu câu đã bị xóa: \"Không tìm thấy mẫu câu này.\", không lưu")
    void deletedPhraseCannotBeSaved() throws Exception {
        long me = member();
        long phrase = phrase("消えた" + token());
        deletePhrase(phrase);

        postAs(me, null, "/api/phrases/{id}/save", phrase)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(PHRASE_GONE));

        assertThat(savedPhraseRows(me, phrase)).isZero();
    }

    @Test
    @DisplayName("TC-1.12-17 [Phân quyền] Địa điểm, cẩm nang, mẫu câu: Khách phải đăng nhập (401), tài khoản nhân viên nhận MSG18")
    void onlyMembersSavePlacesGuidesAndPhrases() throws Exception {
        long place = place("Quán " + token());
        long guide = sampleGuides(1).get(0);
        long phrase = samplePhrases(1).get(0);
        long editor = account("ACADEMIC_MANAGER", 30);

        postAs(null, null, "/api/map/places/{id}/save", place).andExpect(status().isUnauthorized());
        postAs(null, null, "/api/guides/{id}/save", guide).andExpect(status().isUnauthorized());
        postAs(null, null, "/api/phrases/{id}/save", phrase).andExpect(status().isUnauthorized());

        postAs(editor, null, "/api/map/places/{id}/save", place)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        postAs(editor, null, "/api/guides/{id}/save", guide)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        postAs(editor, null, "/api/phrases/{id}/save", phrase)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));

        assertThat(savedPlaceRows(editor, place) + savedGuideRows(editor, guide) + savedPhraseRows(editor, phrase))
                .isZero();
    }
}
